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
 * codec-3 records exist means those records cannot be decoded - {@link #load} reports that as
 * a clear {@link FolesiumException} instead of returning garbage.</p>
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

    /** {@code Zstd.trainFromBuffer} rejects fewer than 11 samples. */
    private static final int MIN_SAMPLES = 11;

    private DictionaryStore() {
    }

    /**
     * Loads and validates the dictionary file.
     *
     * @return the dictionary bytes, or {@code null} if {@code dictFile} does not exist (or is not
     *         a regular file) - the "no dictionary" case, distinct from "dictionary is corrupt"
     * @throws FolesiumException if the file exists but is not a valid zstd dictionary (missing,
     *                           short, or wrong magic header): codec-3 records of this keyspace
     *                           cannot be decoded without a valid dictionary
     * @throws IOException       if the file cannot be read
     */
    public static byte[] load(Path dictFile) throws IOException {
        if (!Files.isRegularFile(dictFile)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(dictFile);
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
     * @throws FolesiumException if {@code dictFile} already exists, or if fewer than
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
        Path parent = dictFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = dictFile.resolveSibling(dictFile.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            ByteBuffer buf = ByteBuffer.wrap(trained);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                channel.force(true);
            }
            moveReplacing(tmp, dictFile);
        } finally {
            Files.deleteIfExists(tmp);
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
     * Moves {@code source} over {@code target}, preferring an atomic replace but falling back to a
     * plain replace move on filesystems that do not support atomic moves (reported either as
     * {@link AtomicMoveNotSupportedException} or as a generic filesystem error). Mirrors
     * {@link WatermarkFile#write}.
     */
    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException e) {
            // Some platforms report missing atomic-replace support as a generic filesystem error.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
