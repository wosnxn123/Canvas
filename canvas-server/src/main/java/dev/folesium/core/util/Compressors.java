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
        byte[] out;
        try {
            out = ZstdNative.decompressUsingDict(stored, dict, rawLen);
        } catch (RuntimeException e) {
            // zstd-jni reports a corrupt/truncated frame as ZstdException; normalize it to
            // the same IllegalStateException the DEFLATE path throws for corrupt records so
            // every codec fails uniformly (mirror of zstdInflate above). Matched by class
            // name: zstd-jni is an optional runtime dependency, never a compile-time one
            // (ZstdNative is a reflection bridge). Anything else - e.g. the
            // IllegalStateException thrown when the dict API is unavailable - propagates
            // unchanged.
            if (isZstdException(e)) {
                throw new IllegalStateException("Corrupt compressed record", e);
            }
            throw e;
        }
        return out;
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
            if (!inf.finished()) {
                // The declared rawLen is filled (or is 0, so the loop above never ran) but
                // the stream has not yet reached its end-of-stream marker. Drain it: a
                // legal multi-block DEFLATE stream can end with empty blocks that produce
                // no output, and finished() only turns true once the final block is
                // consumed - a single inflate() against a scratch buffer would misreport
                // such a stream as truncated/oversized. The drain producing further output
                // means the record is genuinely larger than its header claims (previously
                // this silently returned truncated data, only the CRC would catch it); a
                // stream that runs out of input before its end-of-stream marker is
                // truncated.
                drainToEnd(inf, rawLen);
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

    /**
     * Consumes the remainder of an inflater stream whose declared {@code rawLen} output
     * bytes were already produced (or skipped, for {@code rawLen == 0}). A legal
     * multi-block DEFLATE stream may end with empty blocks that produce no output, and
     * {@link Inflater#finished()} only turns true once the final block is consumed, so
     * the stream is drained block by block against a scratch buffer. Throws when the
     * stream produces any further output (the record is larger than its header claims),
     * runs out of input before its end-of-stream marker (truncated), or makes no
     * progress at all (a corrupt record that would otherwise spin this loop forever).
     */
    private static void drainToEnd(Inflater inf, int rawLen) throws DataFormatException {
        byte[] scratch = new byte[1];
        while (!inf.finished() && !inf.needsInput()) {
            int n = inf.inflate(scratch, 0, 1);
            if (n != 0) {
                throw new IllegalStateException("Compressed record exceeds declared size: " + rawLen);
            }
            if (inf.finished()) {
                // The inflate() call just consumed the final block and turned the stream
                // finished without producing output: the legal end of a multi-block
                // DEFLATE stream whose last blocks are empty. Same re-check as the main
                // loop in inflate() - without it a legal empty trailing block would be
                // misreported as a no-progress corrupt record.
                break;
            }
            if (inf.needsDictionary()) {
                throw new IllegalStateException("Compressed record requires a preset dictionary");
            }
            // inflate() returned 0 yet the stream is neither finished nor waiting for input
            // or a dictionary: a corrupt record that would otherwise spin this loop forever.
            // Same no-progress guard as the main loop in inflate().
            throw new IllegalStateException("Compressed record made no progress");
        }
        if (!inf.finished()) {
            throw new IllegalStateException("Truncated compressed record");
        }
    }

    private static byte[] zstdCompress(byte[] raw, int level) {
        return ZstdNative.compress(raw, level);
    }

    private static byte[] zstdInflate(byte[] stored, int rawLen) {
        byte[] out;
        try {
            out = ZstdNative.decompress(stored, rawLen);
        } catch (RuntimeException e) {
            // zstd-jni reports a corrupt/truncated frame as ZstdException; normalize it to
            // the same IllegalStateException the DEFLATE path throws for corrupt records so
            // every codec fails uniformly. Matched by class name: zstd-jni is an optional
            // runtime dependency, never a compile-time one (ZstdNative is a reflection
            // bridge). Anything else - e.g. the IllegalStateException thrown when zstd-jni
            // is absent - propagates unchanged.
            if (isZstdException(e)) {
                throw new IllegalStateException("Corrupt compressed record", e);
            }
            throw e;
        }
        if (out.length != rawLen) {
            // zstd-jni's decompress(byte[], int) treats rawLen as the *maximum* output
            // size and trims the result when the frame is actually smaller, so an
            // over-declared header would otherwise silently return a short array.
            // Same hardening as inflate() above: fail loudly instead.
            throw new IllegalStateException("Decompressed size mismatch: " + out.length + " != " + rawLen);
        }
        return out;
    }

    /**
     * Whether {@code t} is (a subclass of) {@code com.github.luben.zstd.ZstdException} -
     * zstd-jni's runtime exception for corrupt/truncated frames. Matched by class name
     * because zstd-jni is an optional runtime dependency: the main source set must not
     * reference its types at compile time (see {@link ZstdNative}). Shared with
     * {@link ZstdNative} so the write path normalizes subclasses too (a shaded or upgraded
     * zstd-jni may throw a subclass).
     */
    static boolean isZstdException(Throwable t) {
        Class<?> c = t.getClass();
        while (c != null && c != Object.class) {
            if ("com.github.luben.zstd.ZstdException".equals(c.getName())) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }
}
