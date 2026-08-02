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

import dev.folesium.core.index.DictionaryStore;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reflection bridge to {@code com.github.luben.zstd.Zstd}, the JNI-backed zstd
 * implementation that Minecraft / Folia already ship on the server classpath.
 *
 * <p>Folesium is otherwise a pure-Java store (no native dependency for the
 * NONE / DEFLATE paths). The optional {@link dev.folesium.core.FolesiumConfig.Compression#ZSTD}
 * path reuses the host server's zstd-jni, so the vendored Folesium sources stay
 * dependency-free at compile time and need nothing extra at runtime on a Folia
 * server - exactly the same library cesium-fabric uses for its store.</p>
 *
 * <p>Everything is resolved lazily and cached: if zstd-jni is absent,
 * {@link #available()} is {@code false} and callers surface a clear error
 * instead of a cryptic {@link NoClassDefFoundError}. The dictionary API
 * (used by {@link dev.folesium.core.FolesiumConfig.Compression#ZSTD_DICT}) is
 * probed separately via {@link #dictAvailable()}, so an older zstd-jni that
 * only has the plain compress/decompress pair degrades gracefully.</p>
 */
public final class ZstdNative {

    private ZstdNative() {}

    /**
     * {@code MethodHandle}s rather than {@code Method.invoke}: compression sits on the
     * per-record write path, and {@code invokeExact} against a static final handle costs
     * about as much as a direct call, with no argument boxing or varargs array per record.
     */
    private static final MethodHandle COMPRESS;
    private static final MethodHandle DECOMPRESS;
    private static final MethodHandle COMPRESS_USING_DICT;
    private static final MethodHandle DECOMPRESS_USING_DICT;
    private static final MethodHandle TRAIN_FROM_BUFFER;
    private static final MethodHandle COMPRESS_BOUND;
    private static final boolean AVAILABLE;
    private static final boolean DICT_AVAILABLE;

    static {
        MethodHandle compress = null;
        MethodHandle decompress = null;
        try {
            Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType type = MethodType.methodType(byte[].class, byte[].class, int.class);
            compress = lookup.findStatic(zstd, "compress", type);
            decompress = lookup.findStatic(zstd, "decompress", type);
        } catch (Throwable ignored) {
            // zstd-jni not on the classpath; ZSTD compression simply unavailable.
            compress = null;
            decompress = null;
        }
        COMPRESS = compress;
        DECOMPRESS = decompress;

        // The dictionary API (compressUsingDict / decompressUsingDict / trainFromBuffer) is
        // newer than the plain compress/decompress pair; bind it in a separate try so an older
        // zstd-jni jar degrades to DICT_AVAILABLE=false without losing the plain ZSTD path.
        // Real signatures (zstd-jni 1.5.7-11):
        //   compressUsingDict(byte[] dst, int dstOffset, byte[] src, int srcOffset, int srcSize, byte[] dict, int level) -> long
        //   decompressUsingDict(byte[] dst, int dstOffset, byte[] src, int srcOffset, int srcSize, byte[] dict) -> long
        //   trainFromBuffer(byte[][] samples, byte[] dictBuffer, boolean legacy) -> long
        //   compressBound(long srcSize) -> long
        MethodHandle compressUsingDict = null;
        MethodHandle decompressUsingDict = null;
        MethodHandle trainFromBuffer = null;
        MethodHandle compressBound = null;
        if (compress != null) {
            try {
                Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                compressUsingDict = lookup.findStatic(zstd, "compressUsingDict",
                        MethodType.methodType(long.class, byte[].class, int.class, byte[].class,
                                int.class, int.class, byte[].class, int.class));
                decompressUsingDict = lookup.findStatic(zstd, "decompressUsingDict",
                        MethodType.methodType(long.class, byte[].class, int.class, byte[].class,
                                int.class, int.class, byte[].class));
                trainFromBuffer = lookup.findStatic(zstd, "trainFromBuffer",
                        MethodType.methodType(long.class, byte[][].class, byte[].class, boolean.class));
                compressBound = lookup.findStatic(zstd, "compressBound",
                        MethodType.methodType(long.class, long.class));
            } catch (Throwable ignored) {
                // Old zstd-jni without the dictionary API; only the dict path is unavailable.
                compressUsingDict = null;
                decompressUsingDict = null;
                trainFromBuffer = null;
                compressBound = null;
            }
        }
        // Real native probe: reflection only proves the classes and methods exist, not that
        // the JNI library will link at the first native call (a platform whose bundled native
        // image does not match the class version, a partially loaded library, an
        // UnsatisfiedLinkError from a stale native cache, ...). A cheap real call -
        // compressBound(0) - triggers that linking NOW, so an environment where the natives
        // cannot actually run degrades to "unavailable" here instead of blowing up on the
        // first compressed record. Versions without compressBound (pre-dict-API zstd-jni)
        // are probed with a real compression of an empty array instead.
        boolean available = compress != null && decompress != null;
        if (available) {
            try {
                if (compressBound != null) {
                    // NOTE: the cast matters. As a bare statement, invokeExact infers a
                    // (long)void call type and throws WrongMethodTypeException against the
                    // handle's (long)long type - which would fail the probe and degrade
                    // ZSTD to unavailable on every host that ships zstd-jni. Casting to
                    // long infers the exact (long)long call type instead.
                    long probeBound = (long) compressBound.invokeExact(0L);
                } else {
                    byte[] probeBytes = (byte[]) compress.invokeExact(new byte[0], 3);
                }
            } catch (Throwable probeFailure) {
                // The class loads but the natives will not run here: ZSTD is unavailable.
                // Drop the plain compress/decompress handles too: a live MethodHandle
                // would otherwise let compress()/decompress() reach the broken native link
                // and blow up with an UnsatisfiedLinkError on the first record instead of
                // the clear IllegalStateException from the available() entry check.
                available = false;
                compress = null;
                decompress = null;
                compressUsingDict = null;
                decompressUsingDict = null;
                trainFromBuffer = null;
                compressBound = null;
            }
        }
        COMPRESS_USING_DICT = compressUsingDict;
        DECOMPRESS_USING_DICT = decompressUsingDict;
        TRAIN_FROM_BUFFER = trainFromBuffer;
        COMPRESS_BOUND = compressBound;
        AVAILABLE = available;
        DICT_AVAILABLE = available && compressUsingDict != null && decompressUsingDict != null
                && trainFromBuffer != null && compressBound != null;
    }

    /**
     * Test-visible override: setting {@code -Dfolesium.zstd.forceUnavailable=true} (or
     * {@code System.setProperty} before the first probe) makes {@link #available()} and
     * {@link #dictAvailable()} report {@code false} even when zstd-jni is loadable,
     * deterministically exercising the "library absent / dictionary API missing" negative
     * paths regardless of the test classpath. Unset in production, so the default
     * behaviour is unchanged.
     *
     * <p>The switch is read from the system property <em>once</em>, on first use, and
     * cached in a {@code volatile Boolean}: {@link #available()}/{@link #dictAvailable()}
     * sit on the per-record write path (every dictionary-compressed put probes the codec),
     * so a {@code System.getProperty} lookup per call would be avoidable overhead. Tests
     * that flip the switch after the class was already probed refresh the cached value
     * with {@link #setForcedUnavailable(boolean)} instead of relying on the property.</p>
     */
    private static final String FORCE_UNAVAILABLE_PROPERTY = "folesium.zstd.forceUnavailable";

    /** Lazily probed from {@link #FORCE_UNAVAILABLE_PROPERTY}; {@code null} until first use. */
    private static volatile Boolean forcedUnavailable;

    /**
     * Test-only override: directly sets the cached switch value, overriding the
     * {@link #FORCE_UNAVAILABLE_PROPERTY} value read at the first probe. Tests that toggle
     * the switch after the class was already probed (e.g. between JUnit test methods) call
     * this to refresh the cache; {@code false} restores the default behaviour. Public (not
     * package-private) because the existing switch-gated tests live in
     * {@code dev.folesium.core}, outside this class's package - a package-private hook
     * would be unreachable from them and a stale cache would silently break the tests.
     */
    public static void setForcedUnavailable(boolean forced) {
        forcedUnavailable = forced;
    }

    private static boolean forcedUnavailable() {
        Boolean cached = forcedUnavailable;
        if (cached == null) {
            cached = Boolean.parseBoolean(System.getProperty(FORCE_UNAVAILABLE_PROPERTY));
            forcedUnavailable = cached;
        }
        return cached;
    }

    /** Whether {@code zstd-jni} is loadable on the current classpath. */
    public static boolean available() {
        return !forcedUnavailable() && AVAILABLE;
    }

    /**
     * Clear explanation for the entry checks in {@link #compress}/{@link #decompress}:
     * the handle is null or the native probe failed (or the test-only force switch is
     * set), so the requested ZSTD operation cannot run here.
     */
    private static String unavailableMessage() {
        return "Folesium ZSTD requested but zstd-jni is unavailable: the zstd-jni classes or their "
                + "native library could not be loaded on this platform (absent from the classpath, "
                + "or the native probe failed). Run on a Folia/Canvas server (which ships zstd-jni) "
                + "or add com.github.luben:zstd-jni.";
    }

    public static byte[] compress(byte[] src, int level) {
        if (!available()) {
            throw new IllegalStateException(unavailableMessage());
        }
        MethodHandle mh = COMPRESS;
        try {
            return (byte[]) mh.invokeExact(src, level);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd compression failed", t);
        }
    }

    public static byte[] decompress(byte[] src, int rawLen) {
        if (!available()) {
            throw new IllegalStateException(unavailableMessage());
        }
        MethodHandle mh = DECOMPRESS;
        try {
            return (byte[]) mh.invokeExact(src, rawLen);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd decompression failed", t);
        }
    }

    /**
     * Whether the zstd dictionary API (compressUsingDict / decompressUsingDict / trainFromBuffer)
     * is loadable on the current classpath. Independent of {@link #available()}: an older zstd-jni
     * may expose the plain compress/decompress pair without the dictionary API.
     */
    public static boolean dictAvailable() {
        return !forcedUnavailable() && DICT_AVAILABLE;
    }

    private static String dictUnavailableMessage() {
        return "Folesium ZSTD_DICT compression requested but the zstd-jni dictionary API is not available. "
                + "Run on a Folia/Canvas server with zstd-jni >= 1.5.x (which ships the dictionary API) or "
                + "add com.github.luben:zstd-jni.";
    }

    /**
     * Compresses {@code raw} with the pre-trained {@code dict} at {@code level}. The destination
     * buffer is sized with {@code Zstd.compressBound(srcSize)} (worst-case bound, as required by
     * zstd-jni's offset-based {@code compressUsingDict}), then trimmed to the produced size.
     */
    public static byte[] compressUsingDict(byte[] raw, byte[] dict, int level) {
        // Entry checks mirroring compress()/decompress(): the handles can be bound while the
        // native link is broken (the probe dropped only the plain pair, see the static
        // initializer) or the test-only force switch is set - both must surface as the same
        // clear failure the plain path reports, so the switch's negative semantics are
        // closed over the dictionary entries too.
        if (!available()) {
            throw new IllegalStateException(unavailableMessage());
        }
        if (!dictAvailable()) {
            throw new IllegalStateException(dictUnavailableMessage());
        }
        MethodHandle mh = COMPRESS_USING_DICT;
        try {
            long bound = (long) COMPRESS_BOUND.invokeExact((long) raw.length);
            if (bound > Integer.MAX_VALUE - 8) {
                throw new IllegalStateException("zstd dictionary compression input too large: " + raw.length + " bytes");
            }
            byte[] dst = new byte[(int) bound];
            long n = (long) mh.invokeExact(dst, 0, raw, 0, raw.length, dict, level);
            if (n < 0) {
                throw new IllegalStateException("zstd dictionary compression failed with error code " + n);
            }
            if (n == dst.length) {
                return dst;
            }
            byte[] out = new byte[(int) n];
            System.arraycopy(dst, 0, out, 0, (int) n);
            return out;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd dictionary compression failed", t);
        }
    }

    /**
     * Decompresses {@code stored} with the pre-trained {@code dict}, expecting exactly
     * {@code rawLen} bytes. A mismatch means the record was written with a different
     * dictionary (or is corrupt) and fails loudly rather than returning truncated data.
     */
    public static byte[] decompressUsingDict(byte[] stored, byte[] dict, int rawLen) {
        // Entry checks mirroring compress()/decompress() - see compressUsingDict for why the
        // availability probes must gate the dictionary entries too.
        if (!available()) {
            throw new IllegalStateException(unavailableMessage());
        }
        if (!dictAvailable()) {
            throw new IllegalStateException(dictUnavailableMessage());
        }
        MethodHandle mh = DECOMPRESS_USING_DICT;
        try {
            byte[] dst = new byte[rawLen];
            long n = (long) mh.invokeExact(dst, 0, stored, 0, stored.length, dict);
            if (n < 0) {
                throw new IllegalStateException("zstd dictionary decompression failed with error code " + n);
            }
            if (n != rawLen) {
                throw new IllegalStateException("zstd dictionary decompressed size mismatch: " + n + " != " + rawLen);
            }
            return dst;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd dictionary decompression failed", t);
        }
    }

    /**
     * Trains a dictionary from {@code samples} into a fresh {@code dictSize}-byte buffer using the
     * COVER algorithm ({@code legacy=false}). Returns the dictionary bytes exactly as long as the
     * native side wrote them. Requires at least 11 samples, matching {@code Zstd.trainFromBuffer}.
     */
    public static byte[] trainDict(byte[][] samples, int dictSize) {
        // Entry checks mirroring compress()/decompress() - see compressUsingDict for why the
        // availability probes must gate the dictionary entries too.
        if (!available()) {
            throw new IllegalStateException(unavailableMessage());
        }
        if (!dictAvailable()) {
            throw new IllegalStateException(dictUnavailableMessage());
        }
        MethodHandle mh = TRAIN_FROM_BUFFER;
        // Mirror the load-side size gates (DictionaryStore.load enforces the same 64 MiB
        // ceiling): reject absurd sizes up front instead of allocating first and OOM-ing.
        if (dictSize < 1) {
            throw new IllegalArgumentException("dictSize must be >= 1: " + dictSize);
        }
        if (dictSize > DictionaryStore.MAX_DICT_BYTES) {
            throw new IllegalArgumentException("dictSize exceeds maximum of "
                    + DictionaryStore.MAX_DICT_BYTES + " bytes: " + dictSize);
        }
        try {
            byte[] dict = new byte[dictSize];
            long n = (long) mh.invokeExact(samples, dict, false);
            if (n < 0) {
                throw new IllegalStateException("zstd dictionary training failed with error code " + n);
            }
            if (n == dict.length) {
                return dict;
            }
            byte[] out = new byte[(int) n];
            System.arraycopy(dict, 0, out, 0, (int) n);
            return out;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd dictionary training failed", t);
        }
    }
}
