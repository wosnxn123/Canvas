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
import java.io.InputStream;
import java.lang.reflect.Constructor;

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
        } catch (Throwable ignored) {
            // lz4-java not on the classpath.
        }
        HANDLE = h;
        AVAILABLE = h != null;
    }

    public static boolean available() {
        return AVAILABLE;
    }

    public static byte[] decompress(byte[] data) {
        if (HANDLE == null) {
            throw new UnsupportedOperationException(
                    "Cannot read LZ4-compressed Anvil chunk: lz4-java is not on the classpath. "
                            + "Run the conversion on a Folia/Canvas server, or add net.jpountz.lz4:lz4.");
        }
        try (InputStream in = (InputStream) HANDLE.ctor.newInstance(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LZ4 decompression failed", e);
        }
    }
}
