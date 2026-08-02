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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
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
        byte[] bytes = Files.readAllBytes(dictFile);
        // Post-read size re-check: the file may have grown between the pre-check above and
        // the read (TOCTOU), so the bytes actually held must satisfy the same bound - the
        // third guard of the RegionPage.read triple (pre-check, re-check, post-check). A
        // huge file that grew past the pre-check would otherwise be read into memory whole.
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
     * @throws FolesiumException if {@code dictFile} already exists (or appears
     *                           concurrently while training), or if fewer than
     *                           {@link #MIN_SAMPLES} samples are provided
     * @throws IOException       if the file cannot be written
     */
    public static byte[] train(Path dictFile, List<byte[]> samples) throws IOException {
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
     * Trains and persists a dictionary only when none exists yet - the conversion pipeline's
     * post-conversion bootstrap path ({@code WorldConverter}). Unlike {@link #train}, an
     * existing dictionary is not an error: it is left untouched, because a different
     * dictionary would make existing codec-3 records undecodable (the immutable-once-minted
     * contract).
     *
     * @return the trained bytes when a new dictionary was written, or {@code null} when
     *         {@code dictFile} already exists
     * @throws FolesiumException if fewer than {@link #MIN_SAMPLES} samples are provided
     * @throws IOException       if the file cannot be written
     */
    public static byte[] trainIfMissing(Path dictFile, List<byte[]> samples) throws IOException {
        if (Files.exists(dictFile)) {
            return null;
        }
        return train(dictFile, samples);
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
     * Moves {@code source} to {@code target} <em>without</em> replacing an existing file,
     * preferring an atomic move but falling back to a plain move on filesystems that
     * report missing atomic-move support as {@link AtomicMoveNotSupportedException}. The
     * fallback re-checks {@link Files#exists} first to narrow the check-then-act window; a
     * target that appears anyway surfaces as {@link FileAlreadyExistsException} from the
     * move itself. Any other {@link FileSystemException} is rethrown unchanged instead of
     * being masked by a doomed retry - except that a target which genuinely exists is
     * treated as the concurrent-appearance race and converted to
     * {@link FileAlreadyExistsException} with the original error attached as suppressed
     * to keep its diagnostics.
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
