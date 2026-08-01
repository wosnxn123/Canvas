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

import java.util.UUID;

/**
 * Canonical encoding of player UUID keys.
 *
 * <p>A player key is the 16 raw bytes of the UUID, most-significant bits first,
 * both halves big-endian. This is the only key encoding used for the
 * {@code playerdata}, {@code advancements} and {@code stats} keyspaces, and it
 * sorts identically to the textual UUID form, which keeps store dumps readable.</p>
 */
public final class UuidKeys {
    /** Length in bytes of an encoded UUID key. */
    public static final int LENGTH = 16;

    private UuidKeys() {
    }

    public static byte[] encode(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        return new byte[]{
                (byte) (msb >>> 56), (byte) (msb >>> 48), (byte) (msb >>> 40), (byte) (msb >>> 32),
                (byte) (msb >>> 24), (byte) (msb >>> 16), (byte) (msb >>> 8), (byte) msb,
                (byte) (lsb >>> 56), (byte) (lsb >>> 48), (byte) (lsb >>> 40), (byte) (lsb >>> 32),
                (byte) (lsb >>> 24), (byte) (lsb >>> 16), (byte) (lsb >>> 8), (byte) lsb
        };
    }

    public static UUID decode(byte[] b) {
        if (b.length != LENGTH) {
            throw new IllegalArgumentException("Expected " + LENGTH + "-byte UUID key, got " + b.length);
        }
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (b[i] & 0xffL);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (b[i] & 0xffL);
        }
        return new UUID(msb, lsb);
    }
}
