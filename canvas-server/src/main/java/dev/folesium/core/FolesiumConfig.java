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
 * <h2>Runtime mutability</h2>
 * <p>Every field except {@link #shardCount()} can be changed on a live store: the
 * engine re-reads the configuration on every operation, so replacing the config
 * object takes effect immediately (see {@code FolesiumDatabase#applyRuntimeConfig}).
 * {@code shardCount} participates in key routing and is stamped into every shard
 * file header, so changing it requires rewriting the store - Folesium does that
 * automatically when the store is opened (see {@code StoreResharder}).</p>
 *
 * @param shardCount        number of independent log shards per keyspace. Must be a power of two.
 *                          Concurrency scales with shards; the server integration auto-tunes this
 *                          from the CPU core count. Changing it triggers an automatic reshard on
 *                          the next store open.
 * @param durability        automatic durability policy, see {@link DurabilityMode}.
 * @param batchFlushMillis  interval of the background group-commit thread when
 *                          {@code durability == BATCH}.
 * @param compression       per-record value compression used for <em>new</em> writes. Existing
 *                          records keep their own algorithm (it is stored per record), so this
 *                          may be changed at any time without migrating anything.
 * @param compressionLevel  compression level. Valid range depends on the algorithm:
 *                          Deflate 1-9, ZSTD 1-22 (see {@link #maxCompressionLevel}).
 * @param compactRatio      shard is compacted when {@code deadBytes > compactRatio * fileSize}.
 * @param compactMinBytes   never compact shards smaller than this.
 * @param verifyChecksums   verify the record CRC32C on every read (always verified on open/scan).
 * @param backupOnConvert   when converting a world, keep the previous tree at the target location
 *                          under a {@code .folesium-backup-*} sibling name instead of overwriting it
 *                          in place. Default {@code false} (cesium-fabric parity: targets are
 *                          written in place); only the converter reads this flag.
 * @param indexCacheBytes   bytes for the per-keyspace region-page index cache; {@code 0} disables
 *                          the page index entirely (pure v1 hash behaviour). Default 64 MiB.
 * @param indexMode         page-index mode, see {@link IndexMode}. Takes effect on store open, like
 *                          {@link #shardCount()}. Default {@link IndexMode#AUTO}.
 * @param dictionaryCompression
 *                          use a per-keyspace zstd dictionary (codec 3) for new region-record writes.
 *                          Default {@code false}; requires zstd-jni and a trained dictionary.
 * @param workloadCompaction
 *                          prefer compacting shards with the most write churn (dead ratio weighted by
 *                          write count) over a pure dead-ratio order. Default {@code false}.
 * @param compactIoLimit    cap compaction I/O at this many bytes/second; {@code 0} = unlimited.
 *                          Default {@code 0}.
 */
public record FolesiumConfig(
        int shardCount,
        DurabilityMode durability,
        int batchFlushMillis,
        Compression compression,
        int compressionLevel,
        double compactRatio,
        long compactMinBytes,
        boolean verifyChecksums,
        boolean backupOnConvert,
        long indexCacheBytes,
        IndexMode indexMode,
        boolean dictionaryCompression,
        boolean workloadCompaction,
        long compactIoLimit
) {
    public enum DurabilityMode {
        /** fsync on every write. Safest, slowest. */
        ALWAYS,
        /** background group-commit thread fsyncs dirty shards every {@code batchFlushMillis}. */
        BATCH,
        /** fsync only on {@link FolesiumDatabase#flush()} and close. */
        EXPLICIT
    }

    /**
     * Region-page index mode. Only takes effect when a store is opened, like {@link #shardCount()}.
     *
     * <p>{@code AUTO}: the page index is used with the HashMap index as a read fallback when pages
     * are invalidated (after compaction/reshard). {@code PAGE}: the page index is the only index for
     * region-keyed keys. Disabling the page index entirely is done with {@code indexCacheBytes = 0}.</p>
     */
    public enum IndexMode {
        /** page index first, HashMap fallback. */
        AUTO,
        /** page index is the only index for region-keyed keys. */
        PAGE
    }

    public enum Compression {
        NONE((byte) 0),
        DEFLATE((byte) 1),
        /** zstd via zstd-jni, provided by the host Minecraft/Folia server. */
        ZSTD((byte) 2),
        /**
         * zstd with a per-keyspace trained dictionary (see {@code index.DictionaryStore}).
         * Level range is the same as {@link #ZSTD}; the dictionary improves ratios on the
         * repetitive NBT payloads typical of Minecraft world data.
         */
        ZSTD_DICT((byte) 3);

        public final byte id;

        Compression(byte id) {
            this.id = id;
        }

        public static Compression byId(byte id) {
            return switch (id) {
                case 0 -> NONE;
                case 1 -> DEFLATE;
                case 2 -> ZSTD;
                case 3 -> ZSTD_DICT;
                default -> throw new IllegalArgumentException("Unknown compression id " + id);
            };
        }
    }

    /** Highest meaningful {@code compressionLevel} for the given algorithm. */
    public static int maxCompressionLevel(Compression c) {
        // zstd supports 1-22 (plain and dictionary variants); java.util.zip.Deflater only 1-9.
        // NONE ignores the level entirely but is validated against the widest range so that
        // switching NONE -> ZSTD -> NONE at runtime never trips the constructor.
        return switch (c) {
            case DEFLATE -> 9;
            case NONE, ZSTD, ZSTD_DICT -> 22;
        };
    }

    /** Clamps {@code level} into the range valid for {@code c}. */
    public static int clampCompressionLevel(Compression c, int level) {
        int max = maxCompressionLevel(c);
        return level < 1 ? 1 : Math.min(level, max);
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
        int maxLevel = maxCompressionLevel(compression);
        if (compressionLevel < 1 || compressionLevel > maxLevel) {
            throw new IllegalArgumentException(
                    "compressionLevel must be in [1," + maxLevel + "] for " + compression + ": " + compressionLevel);
        }
        // NaN fails both `<= 0` and `> 1`, so the positive form is required: a NaN here would
        // otherwise pass and make ShardFile.needsCompaction() permanently false (store never compacts).
        if (!(compactRatio > 0) || compactRatio > 1) {
            throw new IllegalArgumentException("compactRatio must be in (0,1]: " + compactRatio);
        }
        if (compactMinBytes < 0) {
            throw new IllegalArgumentException("compactMinBytes must be >= 0: " + compactMinBytes);
        }
        Objects.requireNonNull(indexMode, "indexMode");
        if (indexCacheBytes < 0) {
            throw new IllegalArgumentException("indexCacheBytes must be >= 0: " + indexCacheBytes);
        }
        if (compactIoLimit < 0) {
            throw new IllegalArgumentException("compactIoLimit must be >= 0: " + compactIoLimit);
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
                false,
                false,
                64L * 1024 * 1024,
                IndexMode.AUTO,
                false,
                false,
                0
        );
    }

    public FolesiumConfig withShardCount(int n) {
        return new FolesiumConfig(n, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withDurability(DurabilityMode d) {
        return new FolesiumConfig(shardCount, d, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    /**
     * Switches the algorithm used for <em>new</em> writes. The level is clamped into the
     * range valid for {@code c} (Deflate tops out at 9, zstd at 22), so switching back and
     * forth never throws.
     */
    public FolesiumConfig withCompression(Compression c) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, c,
                clampCompressionLevel(c, compressionLevel), compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withCompressionLevel(int level) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, level, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withBatchFlushMillis(int millis) {
        return new FolesiumConfig(shardCount, durability, millis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withCompactRatio(double ratio) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, ratio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withCompactMinBytes(long bytes) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, bytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withVerifyChecksums(boolean v) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, v, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withBackupOnConvert(boolean v) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, v, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withIndexCacheBytes(long bytes) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, bytes, indexMode, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withIndexMode(IndexMode m) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, m, dictionaryCompression, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withDictionaryCompression(boolean v) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, v, workloadCompaction, compactIoLimit);
    }

    public FolesiumConfig withWorkloadCompaction(boolean v) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, v, compactIoLimit);
    }

    public FolesiumConfig withCompactIoLimit(long bytes) {
        return new FolesiumConfig(shardCount, durability, batchFlushMillis, compression, compressionLevel, compactRatio, compactMinBytes, verifyChecksums, backupOnConvert, indexCacheBytes, indexMode, dictionaryCompression, workloadCompaction, bytes);
    }

    /**
     * Human-readable list of the fields that differ between {@code this} and {@code other},
     * as {@code "key: old -> new"} entries. Used by the runtime reload path to tell operators
     * exactly what changed.
     */
    public java.util.List<String> diff(FolesiumConfig other) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (shardCount != other.shardCount) {
            out.add("shards: " + shardCount + " -> " + other.shardCount);
        }
        if (durability != other.durability) {
            out.add("durability: " + durability + " -> " + other.durability);
        }
        if (batchFlushMillis != other.batchFlushMillis) {
            out.add("batchFlushMillis: " + batchFlushMillis + " -> " + other.batchFlushMillis);
        }
        if (compression != other.compression) {
            out.add("compression: " + compression + " -> " + other.compression);
        }
        if (compressionLevel != other.compressionLevel) {
            out.add("compressionLevel: " + compressionLevel + " -> " + other.compressionLevel);
        }
        if (Double.compare(compactRatio, other.compactRatio) != 0) {
            out.add("compactRatio: " + compactRatio + " -> " + other.compactRatio);
        }
        if (compactMinBytes != other.compactMinBytes) {
            out.add("compactMinBytes: " + compactMinBytes + " -> " + other.compactMinBytes);
        }
        if (verifyChecksums != other.verifyChecksums) {
            out.add("verifyChecksums: " + verifyChecksums + " -> " + other.verifyChecksums);
        }
        if (backupOnConvert != other.backupOnConvert) {
            out.add("backupOnConvert: " + backupOnConvert + " -> " + other.backupOnConvert);
        }
        if (indexCacheBytes != other.indexCacheBytes) {
            out.add("indexCacheBytes: " + indexCacheBytes + " -> " + other.indexCacheBytes);
        }
        if (indexMode != other.indexMode) {
            out.add("indexMode: " + indexMode + " -> " + other.indexMode);
        }
        if (dictionaryCompression != other.dictionaryCompression) {
            out.add("dictionaryCompression: " + dictionaryCompression + " -> " + other.dictionaryCompression);
        }
        if (workloadCompaction != other.workloadCompaction) {
            out.add("workloadCompaction: " + workloadCompaction + " -> " + other.workloadCompaction);
        }
        if (compactIoLimit != other.compactIoLimit) {
            out.add("compactIoLimit: " + compactIoLimit + " -> " + other.compactIoLimit);
        }
        return out;
    }
}
