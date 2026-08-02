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

import dev.folesium.core.index.DictionaryStore;
import dev.folesium.core.index.PageIndex;
import dev.folesium.core.shard.ShardFile;
import dev.folesium.core.util.Bytes;
import dev.folesium.core.util.LongKeys;
import dev.folesium.core.util.UuidKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * A named, sharded key-value namespace (e.g. {@code chunks}, {@code entities},
 * {@code poi}, {@code playerdata}).
 *
 * <p>Keys are routed to shards by {@link Bytes#mix64(byte[])}, so a given key
 * always maps to exactly one shard. Operations on distinct shards proceed fully
 * in parallel; there is no keyspace-wide lock on the hot path.</p>
 */
public final class Keyspace implements AutoCloseable {
    private final String name;
    private final ShardFile[] shards;
    private final int shardMask;
    /**
     * Region-page index for this keyspace, or {@code null} for non-region-keyed keyspaces
     * (playerdata/advancements/stats/misc), when {@code indexCacheBytes == 0} disables it,
     * or when the open failed before it could be created. Owned by this keyspace: closed
     * in {@link #close()} and shared by every shard.
     */
    private final PageIndex pageIndex;
    /**
     * Immutable per-keyspace dictionary for codec-3 (ZSTD_DICT) records, or {@code null} when
     * dictionary compression is disabled, the keyspace is not region-keyed, or no
     * {@code <store>/idx/<name>/dict.bin} exists. Loaded once at open (also in read-only mode -
     * reading codec-3 records needs it) and shared by every shard. A corrupt dictionary fails
     * the open with a clear {@link FolesiumException}.
     */
    private final byte[] keyspaceDict;

    Keyspace(Path dir, String name, FolesiumConfig config, boolean readOnly) {
        this.name = name;
        this.shards = new ShardFile[config.shardCount()];
        this.shardMask = config.shardCount() - 1;
        this.pageIndex = createPageIndex(dir, name, config, readOnly);
        this.keyspaceDict = loadKeyspaceDict(dir, name, config);
        int opened = 0;
        try {
            for (; opened < shards.length; opened++) {
                String shardName = String.format("%s-%04d", name, opened);
                shards[opened] = new ShardFile(dir.resolve(shardName + ".flog"), opened, config, pageIndex,
                        shardName, config.indexMode() == FolesiumConfig.IndexMode.PAGE, keyspaceDict);
            }
        } catch (RuntimeException e) {
            // One bad shard must not leak the handles of the shards already opened: nobody
            // holds a reference to this half-built keyspace, so nothing else can close them.
            for (int i = 0; i < opened; i++) {
                try {
                    shards[i].close();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            if (pageIndex != null) {
                try {
                    pageIndex.close();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }

    /** True for keyspaces whose keys are region coordinates (chunks/entities/poi). */
    private static boolean isRegionKeyed(String name) {
        return FolesiumDatabase.KS_CHUNKS.equals(name)
                || FolesiumDatabase.KS_ENTITIES.equals(name)
                || FolesiumDatabase.KS_POI.equals(name);
    }

    /**
     * Creates the region-page index for a region-keyed keyspace ({@code chunks},
     * {@code entities}, {@code poi}) when the page index is enabled; {@code null}
     * otherwise. {@code readOnly} opens the index in memory only - no directory is
     * created and nothing is written to disk.
     */
    private static PageIndex createPageIndex(Path dir, String name, FolesiumConfig config, boolean readOnly) {
        if (config.indexCacheBytes() <= 0) {
            return null;
        }
        if (!isRegionKeyed(name)) {
            return null;
        }
        try {
            return new PageIndex(dir.resolve("idx").resolve(name), config.indexCacheBytes(), readOnly);
        } catch (IOException e) {
            throw new FolesiumException("Cannot open the page index of keyspace '" + name + "' in " + dir, e);
        }
    }

    /**
     * Loads the per-keyspace dictionary ({@code <store>/idx/<name>/dict.bin}) when dictionary
     * compression is enabled for a region-keyed keyspace. Missing dictionary means no codec-3
     * record can exist yet, so {@code null} (plain compression) is correct. A corrupt or
     * unreadable dictionary fails the open: codec-3 records would be undecodable. Also loaded
     * in read-only mode, where reads of codec-3 records still need it.
     */
    private static byte[] loadKeyspaceDict(Path dir, String name, FolesiumConfig config) {
        if (!config.dictionaryCompression() || !isRegionKeyed(name)) {
            return null;
        }
        Path dictFile = dir.resolve("idx").resolve(name).resolve("dict.bin");
        if (!Files.exists(dictFile)) {
            return null;
        }
        try {
            return DictionaryStore.load(dictFile);
        } catch (FolesiumException e) {
            throw new FolesiumException("Dictionary of keyspace '" + name + "' in " + dictFile
                    + " is corrupt; codec-3 (ZSTD_DICT) records cannot be read without it. "
                    + "Delete dict.bin and rebuild the store, or disable dictionaryCompression.", e);
        } catch (IOException e) {
            throw new FolesiumException("Cannot load the dictionary of keyspace '" + name + "' from " + dictFile, e);
        }
    }

    public String name() {
        return name;
    }

    public int shardCount() {
        return shards.length;
    }

    /**
     * The shards of this keyspace, in routing order. Package-private: the database
     * reads it to collect compaction candidates across keyspaces for
     * workload-ordered compaction. The array is fixed at construction and never
     * mutated; callers must not modify it.
     */
    ShardFile[] shards() {
        return shards;
    }

    /**
     * Pushes a new runtime configuration to every shard. Takes effect on the next
     * operation; nothing on disk is touched.
     *
     * @throws IllegalArgumentException if the shard count differs from this keyspace's
     *                                  physical topology
     */
    public void applyRuntimeConfig(FolesiumConfig next) {
        if (next.shardCount() != shards.length) {
            throw new IllegalArgumentException("Cannot change shardCount on the open keyspace '"
                    + name + "' (" + shards.length + " -> " + next.shardCount() + ")");
        }
        for (ShardFile s : shards) {
            s.applyRuntimeConfig(next);
        }
    }

    private ShardFile shardFor(byte[] key) {
        return shards[(int) (Bytes.mix64(key) & shardMask)];
    }

    public int shardIndexFor(byte[] key) {
        return (int) (Bytes.mix64(key) & shardMask);
    }

    // ------------------------------------------------------------- byte[] API

    public byte[] get(byte[] key) {
        return shardFor(key).get(new Bytes(key));
    }

    public boolean contains(byte[] key) {
        return shardFor(key).contains(new Bytes(key));
    }

    public void put(byte[] key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("null value; use delete()");
        }
        shardFor(key).put(new Bytes(key), value);
    }

    /** Stores the value only if the key is absent; returns {@code true} if written. */
    public boolean putIfAbsent(byte[] key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("null value; use delete()");
        }
        return shardFor(key).putIfAbsent(new Bytes(key), value);
    }

    public void delete(byte[] key) {
        shardFor(key).delete(new Bytes(key));
    }

    // --------------------------------------------------------- chunk-key API

    public byte[] get(long chunkKey) {
        return get(LongKeys.encode(chunkKey));
    }

    public void put(long chunkKey, byte[] value) {
        put(LongKeys.encode(chunkKey), value);
    }

    /** Stores the value only if the chunk key is absent; returns {@code true} if written. */
    public boolean putIfAbsent(long chunkKey, byte[] value) {
        return putIfAbsent(LongKeys.encode(chunkKey), value);
    }

    public void delete(long chunkKey) {
        delete(LongKeys.encode(chunkKey));
    }

    public boolean contains(long chunkKey) {
        return contains(LongKeys.encode(chunkKey));
    }

    // ---------------------------------------------------------- player-key API

    public byte[] get(UUID player) {
        return get(UuidKeys.encode(player));
    }

    public void put(UUID player, byte[] value) {
        put(UuidKeys.encode(player), value);
    }

    /** Stores the value only if the player key is absent; returns {@code true} if written. */
    public boolean putIfAbsent(UUID player, byte[] value) {
        return putIfAbsent(UuidKeys.encode(player), value);
    }

    public void delete(UUID player) {
        delete(UuidKeys.encode(player));
    }

    public boolean contains(UUID player) {
        return contains(UuidKeys.encode(player));
    }

    // ------------------------------------------------------------ maintenance

    public void forEach(BiConsumer<byte[], byte[]> consumer) {
        for (ShardFile s : shards) {
            s.forEach(consumer);
        }
    }

    /**
     * Iterates every live key without reading any value - much cheaper than {@link #forEach}
     * when only the key set is needed, since no record is read back or decompressed.
     */
    public void forEachKey(java.util.function.Consumer<byte[]> consumer) {
        for (ShardFile s : shards) {
            s.forEachKey(consumer);
        }
    }

    /** Iterates one shard only; lets callers parallelise a full scan safely. */
    public void forEachShard(int shardIndex, BiConsumer<byte[], byte[]> consumer) {
        if (shardIndex < 0 || shardIndex >= shards.length) {
            throw new IndexOutOfBoundsException("shardIndex " + shardIndex + " outside [0,"
                    + shards.length + ") of keyspace '" + name + "'");
        }
        shards[shardIndex].forEach(consumer);
    }

    public void flush() {
        for (ShardFile s : shards) {
            s.flushIfDirty();
        }
        // Log-first: dirty pages must never be persisted ahead of the log data they
        // reference. The shard forces above ran first, so flushing here is safe.
        if (pageIndex != null) {
            pageIndex.flush();
        }
    }

    public void compactIfNeeded() {
        for (ShardFile s : shards) {
            if (s.needsCompaction()) {
                s.compact();
            }
        }
    }

    public void compactAll() {
        for (ShardFile s : shards) {
            s.compact();
        }
    }

    public long count() {
        long n = 0;
        for (ShardFile s : shards) {
            n += s.count();
        }
        return n;
    }

    public long sizeBytes() {
        long n = 0;
        for (ShardFile s : shards) {
            n += s.sizeBytes();
        }
        return n;
    }

    public long deadBytes() {
        long n = 0;
        for (ShardFile s : shards) {
            n += s.deadBytes();
        }
        return n;
    }

    /**
     * The region-page index of this keyspace, or {@code null} when pages are disabled or
     * the keyspace is not region-keyed. Exposed for tests and tooling.
     */
    public PageIndex pageIndex() {
        return pageIndex;
    }

    /**
     * Persists every pending per-shard watermark ({@code <shardName>.wmk} files) of this
     * keyspace's page index. Called by the checkpoint path after the shard logs were
     * forced and the dirty pages flushed, so a watermark never claims log data that is
     * not yet durable. No-op without a page index or in read-only mode.
     */
    public void flushWatermarks() {
        if (pageIndex == null) {
            return;
        }
        try {
            pageIndex.flushWatermarks();
        } catch (IOException e) {
            throw new FolesiumException("Cannot flush shard watermarks of keyspace '" + name + "'", e);
        }
    }

    @Override
    public void close() {
        FolesiumException first = null;
        for (ShardFile s : shards) {
            try {
                s.close();
            } catch (FolesiumException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (pageIndex != null) {
            try {
                // After the shards: page flush must never outrun the log force in close().
                // Order within the index: dirty pages first, then the watermarks (a watermark
                // must never claim log offsets that the pages on disk do not yet cover), then
                // close() writes the hint manifest and releases the files.
                pageIndex.flush();
                pageIndex.flushWatermarks();
                pageIndex.close();
            } catch (IOException | RuntimeException e) {
                FolesiumException failure = e instanceof FolesiumException fe
                        ? fe : new FolesiumException("Cannot close the page index of keyspace '" + name + "'", e);
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
