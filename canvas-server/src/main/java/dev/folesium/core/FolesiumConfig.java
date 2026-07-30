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

package dev.folesium.core;

import java.util.Objects;

/**
 * Immutable configuration for a {@link FolesiumDatabase}.
 *
 * <p>Design notes (relative to cesium-fabric, which uses LMDB behind a single
 * global write lock): Folesium is a pure-Java, sharded, append-only log store.
 * There is no native library and no global write lock for the {@link Compression#NONE}
 * and {@link Compression#DEFLATE} paths, which makes it safe for Folia's
 * region-threaded servers where many region threads save chunks concurrently.
 * The optional {@link Compression#ZSTD} path reuses the {@code zstd-jni} library that
 * Minecraft/Folia already ship (the same library cesium-fabric uses for its store),
 * so the vendored sources stay dependency-free at compile time.</p>
 *
 * @param shardCount        number of independent log shards per keyspace. Must be a power of two.
 *                          Concurrency scales with shards; 32 is a good default for 64-core hosts.
 * @param durability        automatic durability policy, see {@link DurabilityMode}.
 * @param batchFlushMillis  interval of the background group-commit thread when
 *                          {@code durability == BATCH}.
 * @param compression       per-record value compression.
 * @param compressionLevel  Deflate level (1-9) when compression is DEFLATE.
 * @param compactRatio      shard is compacted when {@code deadBytes > compactRatio * fileSize}.
 * @param compactMinBytes   never compact shards smaller than this.
 * @param verifyChecksums   verify the record CRC32C on every read (always verified on open/scan).
 */
public record FolesiumConfig(
        int shardCount,
        DurabilityMode durability,
        int batchFlushMillis,
        Compression compression,
        int compressionLevel,
        double compactRatio,
        long compactMinBytes,
        boolean verifyChecksums
) {
    public enum DurabilityMode {
        /** fsync on every write. Safest, slowest. */
        ALWAYS,
        /** background group-commit thread fsyncs dirty shards every {@code batchFlushMillis}. */
        BATCH,
        /** fsync only on {@link FolesiumDatabase#flush()} and close. */
        EXPLICIT
    }

    public enum Compression {
        NONE((byte) 0),
        DEFLATE((byte) 1),
        /** zstd via zstd-jni, provided by the host Minecraft/Folia server. */
        ZSTD((byte) 2);

        public final byte id;

        Compression(byte id) {
            this.id = id;
        }

        public static Compression byId(byte id) {
            return switch (id) {
                case 0 -> NONE;
                case 1 -> DEFLATE;
                case 2 -> ZSTD;
                default -> throw new IllegalArgumentException("Unknown compression id " + id);
            };
        }
    }

    public FolesiumConfig {
        if (Integer.bitCount(shardCount) != 1 || shardCount < 1 || shardCount > 1024) {
            throw new IllegalArgumentException("shardCount must be a power of two in [1,1024]: " + shardCount);
        }
        Objects.requireNonNull(durability, "durability");
        Objects.requireNonNull(compression, "compression");
        if (batchFlushMillis < 1) {
            throw new IllegalArgumentException("batchFlushMillis must be >= 1");
        }
        if (compressionLevel < 1 || compressionLevel > 9) {
            throw new IllegalArgumentException("compressionLevel must be in [1,9]");
        }
        if (compactRatio <= 0 || compactRatio > 1) {
            throw new IllegalArgumentException("compactRatio must be in (0,1]");
        }
    }

    public static FolesiumConfig defaults() {
        return new FolesiumConfig(
                32,
                DurabilityMode.BATCH,
                500,
                Compression.DEFLATE,
                4,
                0.5,
                8L * 1024 * 1024,
                false
        );
    }

    public FolesiumConfig withShardCount(int n) {
        return new FolesiumConfig(n, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums);
    }

    public FolesiumConfig withDurability(DurabilityMode d) {
        return new FolesiumConfig(shardCount, d, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums);
    }

    public FolesiumConfig withCompression(Compression c) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, c, compressionLevel, compactRatio, compactMinBytes, verifyChecksums);
    }

    public FolesiumConfig withVerifyChecksums(boolean v) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, v);
    }
}
