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

/**
 * Canonical encoding of chunk-position keys.
 *
 * <p>A chunk key is 8 bytes big-endian: {@code (x << 32) | (z & 0xFFFFFFFF)}.
 * This is the only key encoding the converter and the server integration use
 * for the {@code chunks}, {@code entities} and {@code poi} keyspaces.</p>
 */
public final class LongKeys {
    private LongKeys() {
    }

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    public static byte[] encode(long key) {
        return new byte[]{
                (byte) (key >>> 56), (byte) (key >>> 48), (byte) (key >>> 40), (byte) (key >>> 32),
                (byte) (key >>> 24), (byte) (key >>> 16), (byte) (key >>> 8), (byte) key
        };
    }

    public static long decode(byte[] b) {
        if (b.length != 8) {
            throw new IllegalArgumentException("Expected 8-byte key, got " + b.length);
        }
        return ((b[0] & 0xffL) << 56) | ((b[1] & 0xffL) << 48) | ((b[2] & 0xffL) << 40) | ((b[3] & 0xffL) << 32)
                | ((b[4] & 0xffL) << 24) | ((b[5] & 0xffL) << 16) | ((b[6] & 0xffL) << 8) | (b[7] & 0xffL);
    }
}
