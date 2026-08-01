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
 * instead of a cryptic {@link NoClassDefFoundError}.</p>
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
    private static final boolean AVAILABLE;

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
        AVAILABLE = compress != null && decompress != null;
    }

    /** Whether {@code zstd-jni} is loadable on the current classpath. */
    public static boolean available() {
        return AVAILABLE;
    }

    public static byte[] compress(byte[] src, int level) {
        MethodHandle mh = COMPRESS;
        if (mh == null) {
            throw new UnsupportedOperationException(
                    "Folesium ZSTD compression requested but zstd-jni is not on the classpath. "
                            + "Run on a Folia/Canvas server (which ships zstd-jni) or add com.github.luben:zstd-jni.");
        }
        try {
            return (byte[]) mh.invokeExact(src, level);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd compression failed", t);
        }
    }

    public static byte[] decompress(byte[] src, int rawLen) {
        MethodHandle mh = DECOMPRESS;
        if (mh == null) {
            throw new UnsupportedOperationException(
                    "Folesium ZSTD decompression requested but zstd-jni is not on the classpath. "
                            + "Run on a Folia/Canvas server (which ships zstd-jni) or add com.github.luben:zstd-jni.");
        }
        try {
            return (byte[]) mh.invokeExact(src, rawLen);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd decompression failed", t);
        }
    }
}
