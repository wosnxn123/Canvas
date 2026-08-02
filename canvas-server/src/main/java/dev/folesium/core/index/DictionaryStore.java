/*
 * Folesium
 * Copyright (C) 2026 Folesium contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.folesium.core.index;

import dev.folesium.core.FolesiumException;
import dev.folesium.core.util.ZstdNative;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-keyspace single trained dictionary (immutable): {@code <store>/idx/<keyspace>/dict.bin}.
 *
 * <p>Codec {@code 3} (ZSTD_DICT) records compress against this dictionary, so it is load-once
 * and cached for the lifetime of the store: the first build never retrains (a different
 * dictionary would make existing codec-3 records undecodable). Deleting {@code dict.bin} while
 * codec-3 records exist means those records cannot be decoded: {@link #load} treats a missing
 * file as the "no dictionary" case and returns {@code null} (it throws a clear
 * {@link FolesiumException} only when a file is present but invalid, see its javadoc), so a
 * deletion is not misreported as corruption and the loss of decodeability surfaces where
 * codec-3 records are actually read.</p>
 *
 * <p>On-disk format: exactly what {@code Zstd.trainFromBuffer} produced (a zstd dictionary,
 * identified by its magic header). Written atomically with a unique {@code .tmp-<uuid>} sibling
 * and {@link FileChannel#force(boolean)}, mirroring {@link WatermarkFile}.</p>
 *
 * <p>Training is serialized across processes with an advisory lock on the sibling
 * {@code dict.bin.lock} file (see {@link #acquireTrainLock}): the whole
 * check-train-move sequence runs under the lock, so two processes converting the same
 * keyspace can never both train and silently overwrite each other's dictionary. The
 * lock is what enforces no-replace - {@link Files#move} with {@code ATOMIC_MOVE}
 * replaces an existing target silently on POSIX and Windows, so the move itself cannot
 * be relied on to refuse - and a {@code dict.bin} a peer just finished training is
 * visible to the next acquirer as an already-existing file.</p>
 */
public final class DictionaryStore {
    /**
     * zstd dictionary magic {@code ZSTD_MAGIC_DICTIONARY = 0xEC30A437}. Like every other zstd
     * magic number, the value is stored <em>little-endian</em> on disk, so the first four bytes
     * of a real dictionary are {@code 37 A4 30 EC}. Validating against the actual byte sequence
     * (rather than the big-endian rendering of the constant) is what lets {@code train -> load}
     * round-trip accept genuinely trained dictionaries.
     */
    private static final int ZSTD_DICT_MAGIC = 0xEC30A437;

    /** Trained dictionary size (contract: {@code 1024 * 16} bytes). */
    private static final int DICT_SIZE = 1024 * 16;

    /**
     * Smallest plausible zstd dictionary: a real trained dictionary (or even a hand-built
     * raw-content one) is well over 64 bytes; anything shorter is a truncated or foreign file.
     */
    private static final int MIN_DICT_BYTES = 64;

    /**
     * Upper bound for a plausible dictionary file. A trained dictionary is exactly
     * {@link #DICT_SIZE} bytes and hand-built raw-content dictionaries are small as well;
     * anything near 64 MiB is not a dictionary. The check runs before the read (mirroring
     * {@code RegionPage.read}'s size pre-check) so a stray huge file is rejected instead of
     * being read into memory whole.
     */
    private static final long MAX_DICT_BYTES = 64L * 1024 * 1024;

    /** {@code Zstd.trainFromBuffer} rejects fewer than 11 samples. */
    private static final int MIN_SAMPLES = 11;

    /**
     * Bound on how often {@link #acquireTrainLock} retries when the lock file keeps being
     * deleted and recreated underneath it. The retry only triggers on the delete race (the
     * previous holder deleting the lock file mid-acquisition), which resolves within
     * microseconds; a bounded loop keeps a pathological race from spinning forever.
     * Exhaustion is reported as an {@link IOException} rather than silently conflated with
     * contention (see {@link #acquireTrainLock}).
     */
    private static final int LOCK_ACQUIRE_ATTEMPTS = 3;

    private DictionaryStore() {
    }

    /**
     * Loads and validates the dictionary file.
     *
     * <p>A dictionary whose magic validates but that is truncated below what the zstd format
     * needs is <em>not</em> rejected here - only the 64-byte floor and the magic are checked,
     * because {@code load} reads arbitrary existing dictionaries - and instead fails loudly at
     * the first decode with the zstd error code. Keeping the truncated file registered (rather
     * than misreporting it as "no dictionary") preserves that loud decode-time attribution.</p>
     *
     * @return the dictionary bytes, or {@code null} if {@code dictFile} does not exist - the
     *         "no dictionary" case, distinct from "dictionary is corrupt"
     * @throws FolesiumException if the file exists but is not a valid zstd dictionary (a
     *                           directory or other non-regular file, a short file, or a wrong
     *                           magic header): codec-3 records of this keyspace cannot be
     *                           decoded without a valid dictionary
     * @throws IOException       if the file cannot be read
     */
    public static byte[] load(Path dictFile) throws IOException {
        if (!Files.exists(dictFile)) {
            // The "no dictionary" case: no codec-3 record can exist without a dict.bin, so a
            // missing file means plain compression. This is deliberately distinct from "the
            // dictionary is corrupt" below, which fails the open loudly.
            return null;
        }
        if (!Files.isRegularFile(dictFile)) {
            // Exists but is not a regular file (e.g. a directory where dict.bin should be):
            // something replaced the dictionary with a foreign object. That is corruption, not
            // "no dictionary" - any codec-3 record is undecodable either way - so fail loudly
            // instead of silently opening the store as if no dictionary existed.
            throw new FolesiumException(
                    "Dictionary path " + dictFile + " is corrupt: it exists but is not a regular file"
                            + " (a directory or special file where dict.bin should be). Restore dict.bin"
                            + " from a backup, or delete it and re-run the conversion; codec-3 records"
                            + " in this keyspace are not decodable without the dictionary.");
        }
        // Pre-check the size before reading the bytes: a file this big is never a dictionary,
        // and loading it whole would be an OOM for what is a corrupt/foreign file anyway
        // (normal dictionaries are well under 1 MiB - trained ones are exactly DICT_SIZE).
        long size = Files.size(dictFile);
        if (size > MAX_DICT_BYTES) {
            throw new FolesiumException(
                    "Dictionary file " + dictFile + " is corrupt: " + size
                            + " bytes, but a valid zstd dictionary is a small trained blob (well under 1 MiB;"
                            + " trained ones are exactly " + DICT_SIZE + " bytes). Restore dict.bin from a"
                            + " backup, or delete it and re-run the conversion; codec-3 records in this"
                            + " keyspace are not decodable without the dictionary.");
        }
        // Second size check immediately before the read: narrows the TOCTOU window in which a
        // concurrently growing file could slip past the pre-check and be read (and allocated)
        // whole - the second guard of the RegionPage.read triple (pre-check, re-check,
        // post-check). The post-read length check below still guards the remaining gap.
        long size2 = Files.size(dictFile);
        if (size2 > MAX_DICT_BYTES) {
            throw new FolesiumException(
                    "Dictionary file " + dictFile + " is corrupt: " + size2
                            + " bytes, but a valid zstd dictionary is a small trained blob (well under 1 MiB;"
                            + " trained ones are exactly " + DICT_SIZE + " bytes). Restore dict.bin from a"
                            + " backup, or delete it and re-run the conversion; codec-3 records in this"
                            + " keyspace are not decodable without the dictionary.");
        }
        byte[] bytes = Files.readAllBytes(dictFile);
        // Post-read size re-check: the file may have grown between the re-check above and
        // the read (TOCTOU), so the bytes actually held must satisfy the same bound - the
        // third guard of the RegionPage.read triple (pre-check, re-check, post-check). A
        // huge file that grew past the re-check would otherwise be read into memory whole.
        if (bytes.length > MAX_DICT_BYTES) {
            throw new FolesiumException(
                    "Dictionary file " + dictFile + " is corrupt: " + bytes.length
                            + " bytes, but a valid zstd dictionary is a small trained blob (well under 1 MiB;"
                            + " trained ones are exactly " + DICT_SIZE + " bytes). Restore dict.bin from a"
                            + " backup, or delete it and re-run the conversion; codec-3 records in this"
                            + " keyspace are not decodable without the dictionary.");
        }
        if (bytes.length < MIN_DICT_BYTES) {
            throw new FolesiumException(
                    "Dictionary file " + dictFile + " is corrupt: only " + bytes.length
                            + " bytes, but a valid zstd dictionary is at least " + MIN_DICT_BYTES
                            + " bytes (truncated header?). Restore dict.bin from a backup, or delete "
                            + "it and re-run the conversion; codec-3 records in this keyspace are "
                            + "not decodable without the dictionary.");
        }
        if (!hasDictionaryMagic(bytes)) {
            throw new FolesiumException(
                    "Dictionary file " + dictFile + " is corrupt: not a valid zstd dictionary "
                            + "(missing or wrong magic header). Restore dict.bin from a backup, or delete "
                            + "it and re-run the conversion; codec-3 records in this keyspace are "
                            + "not decodable without the dictionary.");
        }
        return bytes;
    }

    /**
     * Trains a dictionary from {@code samples} (at least {@link #MIN_SAMPLES} of them), writes it
     * atomically to {@code dictFile}, and returns the trained bytes so the caller can cache them
     * without re-reading the file. Refuses to overwrite an existing dictionary: a different
     * dictionary would make existing codec-3 records undecodable, so retraining requires
     * deleting {@code dictFile} first.
     *
     * <p>Runs entirely under the cross-process training lock (see {@link #acquireTrainLock}), so
     * the check-train-move sequence is serialized with any other process training the same
     * keyspace: a peer that started first either produced the dictionary (which this call then
     * sees as an existing file and refuses to overwrite) or still holds the lock, in which case
     * this call fails loudly instead of racing it.</p>
     *
     * @throws FolesiumException if {@code dictFile} already exists, if another process is
     *                           currently training it, or if fewer than
     *                           {@link #MIN_SAMPLES} samples are provided
     * @throws IOException       if the file cannot be written or the training lock cannot be
     *                           acquired
     */
    public static byte[] train(Path dictFile, List<byte[]> samples) throws IOException {
        FileChannel lockChannel = acquireTrainLock(dictFile);
        if (lockChannel == null) {
            throw new FolesiumException("Another process is currently training the dictionary for "
                    + dictFile + "; refusing to race it (two concurrently trained dictionaries would "
                    + "leave one set of codec-3 records undecodable). Retry once the other training "
                    + "finishes.");
        }
        try {
            return trainHoldingLock(dictFile, samples);
        } finally {
            releaseTrainLock(lockChannel, dictFile);
        }
    }

    /**
     * Trains and persists a dictionary only when none exists yet - the conversion pipeline's
     * post-conversion bootstrap path ({@code WorldConverter}). Unlike {@link #train}, an
     * existing dictionary is not an error: it is left untouched, because a different
     * dictionary would make existing codec-3 records undecodable (the immutable-once-minted
     * contract).
     *
     * <p>Like {@link #train}, the check-train-move sequence runs under the cross-process
     * training lock, but contention is not an error here: a peer training the same keyspace
     * produces the same outcome as an already-present dictionary, so a <em>contended</em>
     * lock acquisition (another process holds it) is reported as {@code null}. An acquisition
     * that fails outright - an I/O error, or {@link #acquireTrainLock}'s delete-race retries
     * exhausting - still throws {@link IOException}, which callers treat as a failure to
     * degrade from (the dictionary is simply not trained).</p>
     *
     * @return the trained bytes when a new dictionary was written, or {@code null} when
     *         {@code dictFile} already exists or another process is currently training it
     * @throws FolesiumException if fewer than {@link #MIN_SAMPLES} samples are provided
     * @throws IOException       if the file cannot be written or the training lock cannot be
     *                           acquired
     */
    public static byte[] trainIfMissing(Path dictFile, List<byte[]> samples) throws IOException {
        if (Files.exists(dictFile)) {
            return null;
        }
        FileChannel lockChannel = acquireTrainLock(dictFile);
        if (lockChannel == null) {
            // Another process is (or just was) training this dictionary: under the
            // no-overwrite contract the result is the same as finding dict.bin already
            // present - the dictionary exists or will exist once that trainer finishes - so
            // report the 'not an error' case instead of racing it.
            return null;
        }
        try {
            if (Files.exists(dictFile)) {
                // The exists() check above ran before the lock; a completed concurrent train
                // (or an external actor) created the file in the meantime. Under the lock this
                // is the definitive answer - no trainer can be mid-flight - so report the same
                // 'already exists' outcome as a pre-existing file.
                return null;
            }
            try {
                return trainHoldingLock(dictFile, samples);
            } catch (FolesiumException e) {
                // Only an external (non-lock-taking) actor can still race the move now; a
                // dict.bin it created mid-train is the documented 'existing dictionary is not
                // an error' case, so report it as such instead of surfacing a spurious refusal.
                if (Files.exists(dictFile)) {
                    return null;
                }
                throw e;
            }
        } finally {
            releaseTrainLock(lockChannel, dictFile);
        }
    }

    /**
     * The check-train-move body shared by {@link #train} and {@link #trainIfMissing}. The
     * caller must already hold the cross-process training lock (see {@link #acquireTrainLock}):
     * the {@link Files#exists} check at the top is only authoritative under it, because the
     * move at the bottom (see {@link #moveIntoPlace}) cannot itself refuse to replace a
     * concurrent {@code dict.bin} - {@code ATOMIC_MOVE} replaces silently on POSIX/Windows.
     */
    private static byte[] trainHoldingLock(Path dictFile, List<byte[]> samples) throws IOException {
        if (Files.exists(dictFile)) {
            throw new FolesiumException("Dictionary file " + dictFile + " already exists; refusing to "
                    + "overwrite it (existing codec-3 records may depend on the trained dictionary). "
                    + "Delete dict.bin first to retrain.");
        }
        if (samples.size() < MIN_SAMPLES) {
            throw new FolesiumException("Cannot train a dictionary from " + samples.size()
                    + " samples; zstd requires at least " + MIN_SAMPLES);
        }
        byte[] trained = ZstdNative.trainDict(samples.toArray(new byte[0][]), DICT_SIZE);
        // Symmetric with load(): never persist a dictionary that load() would reject, or
        // every existing codec-3 record of this keyspace would become undecodable.
        if (trained.length < MIN_DICT_BYTES || !hasDictionaryMagic(trained)) {
            throw new FolesiumException("Dictionary training for " + dictFile + " produced an invalid dictionary ("
                    + trained.length + " bytes, missing or wrong magic header); refusing to write it.");
        }
        Path parent = dictFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = dictFile.resolveSibling(dictFile.getFileName() + ".tmp-" + UUID.randomUUID());
        Throwable primary = null;
        try {
            ByteBuffer buf = ByteBuffer.wrap(trained);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                channel.force(true);
            }
            moveIntoPlace(tmp, dictFile);
            // The rename is the commit: fsync the parent directory so the new dict.bin name
            // survives a crash (mirrors FolesiumDatabase.writeAtomically / the directory-fsync
            // pattern of the other stores).
            fsyncDirectory(parent);
        } catch (FileAlreadyExistsException e) {
            // The exists() check above ran before training; a dictionary created in the
            // meantime must not be silently overwritten (the immutable-once-minted
            // contract), so surface the race as the same refusal as a pre-existing file.
            FolesiumException failure = new FolesiumException("Dictionary file " + dictFile + " appeared concurrently; refusing to "
                    + "overwrite it (existing codec-3 records may depend on the trained dictionary). "
                    + "Delete dict.bin first to retrain.", e);
            primary = failure;
            throw failure;
        } catch (IOException | RuntimeException | Error e) {
            // Any other failure propagating out of the write is remembered so the cleanup
            // below attaches to it instead of masking it.
            primary = e;
            throw e;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e2) {
                // A failed cleanup must not mask the exception the try block is already
                // propagating: attach the delete failure to it as suppressed. When the try
                // block itself succeeded (the file was moved away), the delete failure is
                // the only error to report.
                if (primary != null) {
                    primary.addSuppressed(e2);
                } else {
                    throw e2;
                }
            }
        }
        return trained;
    }

    /**
     * Acquires the per-keyspace cross-process training lock: an exclusive {@link FileLock} over
     * the {@code <dict>.lock} sibling file, so two processes training the same dictionary
     * serialize their whole check-train-move sequence instead of racing it.
     *
     * <p>Cross-process semantics: the lock is a plain OS advisory lock on the lock file, so it
     * coordinates separate JVMs on any shared filesystem (local or network). It is what enforces
     * no-replace - {@code Files.move} with {@code ATOMIC_MOVE} silently replaces an existing
     * target on POSIX and Windows, so the move itself cannot refuse - and a peer's finished
     * {@code dict.bin} is visible to the next acquirer as an already-existing file. Acquisition
     * is non-blocking ({@link FileChannel#tryLock}): a process that finds the lock held gets
     * {@code null} immediately and the caller decides between 'already handled'
     * ({@link #trainIfMissing} returns {@code null}) and 'refuse loudly' ({@link #train} throws).
     * A crash releases the OS lock automatically, leaving at most a stale zero-byte lock file
     * behind; that is harmless - only the OS lock matters, never the file's contents, and the
     * next trainer simply locks it again.</p>
     *
     * <p>Because the lock file is deleted on release (see {@link #releaseTrainLock}), an acquirer
     * can end up locking an inode the path no longer names. The probe below makes that harmless:
     * after {@code tryLock} succeeds the path is re-opened, and the identity of the file the path
     * named at open time (its file key, captured before the channel open) is compared with the
     * file the path names at probe time. A mismatch proves our first lock is on an orphaned inode
     * (the path now names a different file) and the acquisition is released and retried. The
     * probe lock result is only auxiliary: a {@code null} probe lock means another process holds
     * the current file (contention), a second successful lock means the current file is unlocked
     * (orphaned inode - retry), and an {@link OverlappingFileLockException} is treated as
     * consistent only when the file identities match - the exception is JVM-wide rather than
     * inode-scoped, so in the same JVM it also fires when a peer thread holds a live lock on a
     * recreated file while we hold the orphaned inode, which must retry instead of being misread
     * as our own lock. When either file key is unavailable (some filesystems return {@code null}
     * file keys, and a file deleted between the anchor and the probe has no key either) a
     * mismatch cannot be proven, so the {@code tryLock} result is accepted as consistent rather
     * than burning the retries on a race that cannot be detected. Orphaned acquisitions are
     * released and retried, bounded by
     * {@link #LOCK_ACQUIRE_ATTEMPTS}.
     * Retries that exhaust without resolving are reported as an {@link IOException}: after three
     * attempts the delete churn is indistinguishable from a real race, and reporting it as
     * contention would make {@link #trainIfMissing} silently claim the dictionary exists when it
     * was never trained.</p>
     *
     * @return the channel holding the lock, or {@code null} when another process (or this JVM)
     *         already holds it
     * @throws IOException if the lock file cannot be opened or locked, or the delete-race
     *                     retries are exhausted (after {@link #LOCK_ACQUIRE_ATTEMPTS} attempts
     *                     the churn is indistinguishable from a real race, so it fails loudly
     *                     instead of being silently conflated with contention)
     */
    private static FileChannel acquireTrainLock(Path dictFile) throws IOException {
        Path parent = dictFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path lockFile = lockFileOf(dictFile);
        IOException lastFailure = null;
        for (int attempt = 0; attempt < LOCK_ACQUIRE_ATTEMPTS; attempt++) {
            // Ensure the lock file exists before anchoring its identity: the previous holder
            // deletes it on release (see {@link #releaseTrainLock}), and the anchor below must
            // name a real inode to be comparable (a missing file would otherwise force a wasted
            // retry on the common first acquisition after a release).
            try (var seed = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                // Creates the file if missing; nothing else to do.
            }
            // Anchor: the identity of the file the path names when the lock channel is opened.
            // FileChannel exposes no file key, so the anchor is captured from the path BEFORE
            // the open - a replacement after the capture then always surfaces as an anchor
            // mismatch in the probe below, which is the safe direction (release and retry)
            // instead of a false "same inode".
            Object openedFileKey = fileKeyOf(lockFile);
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                if (channel.tryLock() == null) {
                    // Held by another process: contention. (Same-JVM contention throws
                    // OverlappingFileLockException instead and is handled below.)
                    channel.close();
                    return null;
                }
                try (FileChannel probe = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                    FileLock probeLock = probe.tryLock();
                    if (probeLock == null) {
                        // The path names a file another process holds: our first lock is the
                        // orphaned inode, so this is contention after all.
                        channel.close();
                        return null;
                    }
                    probeLock.release();
                    // The path names a different, currently unlocked file (the previous holder
                    // deleted it and something recreated it): our first lock is the orphaned
                    // inode - release it and retry against the current file.
                    channel.close();
                } catch (NoSuchFileException e) {
                    // The path was deleted between our open and our probe: orphaned inode,
                    // retry against a freshly created lock file.
                    channel.close();
                } catch (OverlappingFileLockException e) {
                    // This JVM holds a lock on the file the path currently names. Only when
                    // that file is the SAME inode we locked is it our own lock (consistent).
                    // OverlappingFileLockException is JVM-wide rather than inode-scoped, so in
                    // the same JVM it also fires when a peer thread holds a live lock on a
                    // recreated file while we hold the orphaned inode - mistaking that for
                    // consistent would let both threads train and silently overwrite each
                    // other's dictionary. Judge by file identity, but only when the identity
                    // is actually provable: fileKey() is null on filesystems without
                    // file-key support (and a missing file cannot be attributed either), so
                    // with either key unavailable a mismatch cannot be proven and the
                    // tryLock result is accepted as consistent (the pre-file-key behavior;
                    // treating unknown as consistent is also what keeps acquisition from
                    // deterministically retrying to exhaustion on such filesystems). Only a
                    // proven mismatch - both keys available and different - is an orphaned
                    // inode, released and retried against the current file.
                    Object currentFileKey = fileKeyOf(lockFile);
                    if (openedFileKey == null || currentFileKey == null
                            || openedFileKey.equals(currentFileKey)) {
                        return channel;
                    }
                    channel.close();
                }
            } catch (OverlappingFileLockException e) {
                // This JVM already holds the lock on this keyspace: contention.
                channel.close();
                return null;
            } catch (IOException e) {
                channel.close();
                lastFailure = e;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        // Retries exhausted on transient delete races: every attempt's lock landed on an inode
        // the path no longer named, and no IOException ever fired. This is NOT the contention
        // null - no other process holds the current lock file - so report it as the failure it
        // is: as contention, trainIfMissing would silently report 'already handled' for a
        // dictionary that was never trained (the caller degrades an IOException instead).
        throw new IOException("could not acquire the dictionary training lock after "
                + LOCK_ACQUIRE_ATTEMPTS + " attempts");
    }

    /**
     * The file key of {@code p} - the identity of the file the path currently names - or
     * {@code null} when the file does not exist or its key cannot be read. Used by
     * {@link #acquireTrainLock} to tell an orphaned inode (the file we locked is no longer the
     * one the path names) from a consistent acquisition.
     */
    private static Object fileKeyOf(Path p) {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Releases the lock acquired by {@link #acquireTrainLock} and removes the lock file. The
     * file is deleted <em>before</em> the lock is released so that a fresh acquirer can never
     * open the path mid-protected-section: once the path is gone, a new {@code open(CREATE)}
     * locks a brand-new file that a still-running peer cannot be holding, and a peer that
     * opened the old file just before the delete either finds the lock still held (contention)
     * or is caught by {@code acquireTrainLock}'s probe. Deleting before closing is also what
     * bounds the orphan-inode window to acquirers already holding a descriptor.
     *
     * <p>Both failures are swallowed: a leftover lock file is harmless (only the OS lock
     * matters; the next trainer opens and locks it again), and a close failure must not mask
     * the outcome of the protected operation.
     */
    private static void releaseTrainLock(FileChannel lockChannel, Path dictFile) {
        try {
            Files.deleteIfExists(lockFileOf(dictFile));
        } catch (IOException e) {
            // A leftover lock file is harmless - see the javadoc.
        }
        try {
            lockChannel.close();
        } catch (IOException e) {
            // A close failure means the OS lock may outlive this call until GC reaps the
            // channel; the next acquirer then reports contention, which degrades to 'no
            // dictionary' rather than data loss - see the javadoc.
        }
    }

    /** The sibling lock file coordinating training of {@code dictFile}: {@code <name>.lock}. */
    private static Path lockFileOf(Path dictFile) {
        return dictFile.resolveSibling(dictFile.getFileName() + ".lock");
    }

    /**
     * Whether a dictionary file is present.
     */
    public static boolean exists(Path dictFile) {
        return Files.isRegularFile(dictFile);
    }

    /**
     * Whether the first four bytes carry the zstd dictionary magic. The constant
     * {@code 0xEC30A437} is little-endian on disk, so a real dictionary starts {@code 37 A4 30 EC}.
     */
    private static boolean hasDictionaryMagic(byte[] dict) {
        return dict.length >= 4
                && ByteBuffer.wrap(dict, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == ZSTD_DICT_MAGIC;
    }

    /**
     * Best-effort directory fsync so a completed rename survives a crash. Mirrors the
     * directory-fsync pattern of {@code FolesiumDatabase.writeAtomically} (opening the
     * directory with {@code READ} and forcing flushes the rename on filesystems that
     * support directory fsync); filesystems that reject it (some Windows filesystems)
     * are skipped silently.
     */
    private static void fsyncDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is unavailable on some Windows filesystems.
        }
    }

    /**
     * Moves {@code source} to {@code target}, preferring an atomic move but falling back to a
     * plain move on filesystems that report missing atomic-move support as
     * {@link AtomicMoveNotSupportedException}.
     *
     * <p>This does <em>not</em> by itself prevent replacing an existing {@code target}:
     * {@link Files#move} with {@code ATOMIC_MOVE} silently replaces an existing file on POSIX
     * and Windows (whether an existing target is replaced or the move fails is
     * implementation-defined, and both platforms replace), so {@link FileAlreadyExistsException}
     * cannot be relied on to surface the no-replace race. The no-replace guarantee comes from
     * the caller holding the cross-process training lock (see {@link #acquireTrainLock}) across
     * the whole check-train-move sequence. The {@code exists} re-checks below are best-effort
     * defenses against external (non-lock-taking) actors racing the move: a target that
     * demonstrably exists is surfaced as {@link FileAlreadyExistsException} instead of being
     * silently replaced, and any other {@link FileSystemException} is rethrown unchanged rather
     * than masked by a doomed retry.</p>
     */
    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            if (Files.exists(target)) {
                FileAlreadyExistsException race = new FileAlreadyExistsException(target.toString());
                race.addSuppressed(e);
                throw race;
            }
            Files.move(source, target);
        } catch (FileSystemException e) {
            // Some platforms report missing atomic-move support as a generic filesystem
            // error; it is indistinguishable from a real one, so only the case that is
            // provably the concurrent-appearance race - the target already exists - is
            // converted to FileAlreadyExistsException. Any other failure is rethrown
            // unchanged, keeping the original diagnostics instead of masking them behind
            // a retry that would fail the same way.
            if (Files.exists(target)) {
                FileAlreadyExistsException race = new FileAlreadyExistsException(target.toString());
                race.addSuppressed(e);
                throw race;
            }
            throw e;
        }
    }
}
