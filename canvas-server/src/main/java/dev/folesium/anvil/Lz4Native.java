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

package dev.folesium.anvil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Reflection bridge to {@code net.jpountz.lz4.LZ4BlockInputStream}, used to read
 * Anvil region chunks compressed with Folia's {@code region-file-compression=lz4}
 * option (region compression type 4).
 *
 * <p>The lz4-java classes are already on the Folia/Canvas server classpath, so the
 * vendored Folesium sources remain dependency-free at compile time. If lz4 is not
 * available (e.g. running the standalone converter outside a server),
 * {@link #available()} is {@code false} and a clear error is raised instead of a
 * cryptic linkage failure.</p>
 */
public final class Lz4Native {

    private Lz4Native() {}

    private static final class Handle {
        final Constructor<?> ctor;

        Handle() throws Exception {
            Class<?> cls = Class.forName("net.jpountz.lz4.LZ4BlockInputStream");
            this.ctor = cls.getConstructor(InputStream.class);
        }
    }

    private static final Handle HANDLE;
    private static final boolean AVAILABLE;

    static {
        Handle h = null;
        try {
            h = new Handle();
        } catch (LinkageError e) {
            // lz4-java absent or its native library cannot link (a broken install and a
            // missing one must both degrade to 'unavailable' - the same class-name based
            // normalization Compressors applies to zstd-jni).
        } catch (Exception e) {
            // lz4-java not on the classpath, or its constructor is unavailable.
        }
        // OutOfMemoryError and other Errors deliberately propagate: swallowing them
        // would misdiagnose a dying JVM as 'lz4 not on the classpath'.
        HANDLE = h;
        AVAILABLE = h != null;
    }

    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Creates an LZ4 decompressing stream over {@code data}, reusing the constructor
     * cached in {@code HANDLE} so per-chunk callers (e.g. the region reader's bounded
     * decompression) do not pay the {@code Class.forName}/{@code getConstructor}
     * reflection lookup again for every chunk. The lz4 layer's checked failures
     * (constructor wrapping, stream creation) are surfaced as {@link IOException},
     * matching {@link #decompress}; a missing lz4-java library stays an
     * {@link UnsupportedOperationException}.
     *
     * @throws UnsupportedOperationException when lz4-java is not on the classpath
     * @throws IOException when the lz4 layer fails to create the stream (e.g. corrupt
     *         LZ4 block header)
     */
    public static InputStream newInputStream(byte[] data) throws IOException {
        if (HANDLE == null) {
            throw new UnsupportedOperationException(
                    "Cannot read LZ4-compressed Anvil chunk: lz4-java is not on the classpath. "
                            + "Run the conversion on a Folia/Canvas server, or add net.jpountz.lz4:lz4.");
        }
        try {
            return (InputStream) HANDLE.ctor.newInstance(new ByteArrayInputStream(data));
        } catch (InvocationTargetException e) {
            // The reflective constructor wrapped a failure from the lz4 layer itself
            // (e.g. a corrupt LZ4 block header): surface it as an IOException, not an
            // unchecked leak -- same contract as {@link #decompress}.
            throw new IOException("LZ4 decompression failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IOException("LZ4 decompression failed", e);
        }
    }

    /**
     * Decompresses {@code data} in full. Unchecked failures (a missing lz4-java
     * library, an {@code LZ4Exception} on corrupt data) keep their type; every
     * <em>checked</em> failure is wrapped in an {@link IOException}, mirroring
     * {@link AnvilRegionFile#decompressLz4Bounded}.
     *
     * <p>Callers that must bound the decompressed size (e.g. region chunk reads)
     * should use {@link #newInputStream} and read through a size-limited stream
     * instead: this method materializes the whole expansion before returning.</p>
     *
     * @throws UnsupportedOperationException when lz4-java is not on the classpath
     * @throws IOException when decompression fails
     */
    public static byte[] decompress(byte[] data) throws IOException {
        try (InputStream in = newInputStream(data)) {
            return in.readAllBytes();
        } catch (RuntimeException e) {
            // LZ4Exception on corrupt data: Lz4Native reports it unchecked by design.
            throw e;
        }
    }
}
