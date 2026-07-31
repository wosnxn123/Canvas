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

import java.util.Arrays;

/** Immutable byte-array key wrapper with cached hash. */
public final class Bytes {
    private final byte[] data;
    private final int hash;

    public Bytes(byte[] data) {
        this.data = data.clone();
        this.hash = Arrays.hashCode(this.data);
    }

    /** Returns a defensive copy of the retained key bytes. */
    public byte[] array() {
        return data.clone();
    }

    public int length() {
        return data.length;
    }

    /** 64-bit FNV-1a with a final avalanche mix; used for shard routing. */
    public static long mix64(byte[] a) {
        long h = 0xcbf29ce484222325L;
        for (byte b : a) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bytes b && hash == b.hash && Arrays.equals(data, b.data);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "Bytes[" + data.length + "b]";
    }
}
