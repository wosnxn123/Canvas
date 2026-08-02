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

package dev.folesium.core.util;

import dev.folesium.core.FolesiumConfig.Compression;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Pure-Java per-record compression. Thread-safe (no shared state). */
public final class Compressors {
    private Compressors() {
    }

    public static byte[] compress(Compression c, int level, byte[] raw) {
        return switch (c) {
            case NONE -> raw;
            case DEFLATE -> deflate(raw, level);
            case ZSTD -> zstdCompress(raw, level);
            case ZSTD_DICT -> throw new IllegalArgumentException(
                    "ZSTD_DICT requires a per-keyspace dictionary; use compressWithDict(byte[], byte[], int)");
        };
    }

    public static byte[] decompress(Compression c, byte[] stored, int rawLen) {
        return switch (c) {
            case NONE -> {
                // NONE is a length-prefixed copy, so stored must be exactly rawLen. A
                // short array would otherwise silently return truncated data (only the
                // CRC would catch it); fail loudly like the DEFLATE/ZSTD size checks.
                if (stored.length != rawLen) {
                    throw new IllegalStateException("Decompressed size mismatch: " + stored.length + " != " + rawLen);
                }
                yield stored;
            }
            case DEFLATE -> inflate(stored, rawLen);
            case ZSTD -> zstdInflate(stored, rawLen);
            case ZSTD_DICT -> throw new IllegalArgumentException(
                    "ZSTD_DICT requires a per-keyspace dictionary; use decompressWithDict(byte[], byte[], int)");
        };
    }

    /**
     * Dictionary-aware compression. The caller has already decided the codec is
     * {@link Compression#ZSTD_DICT}, so this is a thin wrapper over
     * {@link ZstdNative#compressUsingDict}. A null {@code dict} is rejected here as a
     * defensive guard (the write path must not reach codec 3 without a dictionary).
     */
    public static byte[] compressWithDict(byte[] raw, byte[] dict, int level) {
        if (dict == null) {
            throw new IllegalArgumentException("ZSTD_DICT compression requires a non-null dictionary");
        }
        return ZstdNative.compressUsingDict(raw, dict, level);
    }

    /**
     * Dictionary-aware decompression, mirror of {@link #compressWithDict}: codec 3 records
     * decompress with the keyspace dictionary via {@link ZstdNative#decompressUsingDict}.
     */
    public static byte[] decompressWithDict(byte[] stored, byte[] dict, int rawLen) {
        if (dict == null) {
            throw new IllegalArgumentException("ZSTD_DICT decompression requires a non-null dictionary");
        }
        return ZstdNative.decompressUsingDict(stored, dict, rawLen);
    }

    private static byte[] deflate(byte[] raw, int level) {
        // Deflater only accepts 0..9; clamp defensively in case a caller passes a level
        // validated for a different algorithm (e.g. a ZSTD level routed to DEFLATE).
        int lvl = level < 0 ? 0 : Math.min(level, 9);
        Deflater d = new Deflater(lvl);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                out.write(buf, 0, d.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    private static byte[] inflate(byte[] stored, int rawLen) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(stored);
            byte[] out = new byte[rawLen];
            int off = 0;
            while (off < rawLen && !inf.finished()) {
                int n = inf.inflate(out, off, rawLen - off);
                if (n == 0) {
                    if (inf.finished()) {
                        break;
                    }
                    if (inf.needsDictionary()) {
                        throw new IllegalStateException("Compressed record requires a preset dictionary");
                    }
                    if (inf.needsInput()) {
                        throw new IllegalStateException("Truncated compressed record");
                    }
                    // Made no progress yet is neither finished nor needs more input: a corrupt
                    // stream that would otherwise deadlock the read thread.
                    throw new IllegalStateException("Compressed record made no progress");
                }
                off += n;
            }
            if (rawLen > 0 && !inf.finished()) {
                // The stream still has input left after the declared rawLen was filled: the
                // record is larger than its header claims. Previously this silently returned
                // truncated data (only the CRC would catch it); fail loudly instead.
                throw new IllegalStateException("Compressed record exceeds declared size: " + rawLen);
            }
            if (rawLen == 0 && !inf.finished()) {
                // A declared size of 0 must not skip validation: an empty DEFLATE stream
                // (deflate of nothing) is legal and the loop above never ran, but garbage
                // input with a 0 size would otherwise pass silently. Inflate the stream once
                // against a scratch buffer - a legal empty stream reaches the end-of-stream
                // marker (finished) without producing output; garbage either raises
                // DataFormatException below or never finishes (truncated / oversized).
                byte[] scratch = new byte[1];
                int n = inf.inflate(scratch, 0, 1);
                if (n != 0) {
                    throw new IllegalStateException("Compressed record exceeds declared size: " + rawLen);
                }
                if (!inf.finished()) {
                    throw new IllegalStateException("Truncated compressed record");
                }
            }
            if (off != rawLen) {
                throw new IllegalStateException("Decompressed size mismatch: " + off + " != " + rawLen);
            }
            return out;
        } catch (DataFormatException e) {
            throw new IllegalStateException("Corrupt compressed record", e);
        } finally {
            inf.end();
        }
    }

    private static byte[] zstdCompress(byte[] raw, int level) {
        return ZstdNative.compress(raw, level);
    }

    private static byte[] zstdInflate(byte[] stored, int rawLen) {
        byte[] out = ZstdNative.decompress(stored, rawLen);
        if (out.length != rawLen) {
            // zstd-jni's decompress(byte[], int) treats rawLen as the *maximum* output
            // size and trims the result when the frame is actually smaller, so an
            // over-declared header would otherwise silently return a short array.
            // Same hardening as inflate() above: fail loudly instead.
            throw new IllegalStateException("Decompressed size mismatch: " + out.length + " != " + rawLen);
        }
        return out;
    }
}
