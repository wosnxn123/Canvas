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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

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
    /**
     * One slot per shard index of the on-disk layout, in routing order. Read-write
     * keyspaces eagerly open every shard of {@link FolesiumConfig#shardCount()};
     * read-only keyspaces size the array from the shard files actually present on disk
     * (see {@link #discoveredShardIndices(Path, String)}) and leave the slot of a
     * missing shard {@code null}. Fixed at construction and never mutated.
     */
    private final ShardFile[] shards;
    /**
     * Dense, non-null view of {@link #shards} in routing order (identical to
     * {@code shards} for read-write keyspaces, where every slot is open). The
     * iteration and maintenance paths walk this array, so an absent read-only shard is
     * simply skipped instead of crashing on a null slot.
     */
    private final ShardFile[] liveShards;
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
     * the keyspace is not region-keyed or no {@code <store>/idx/<name>/dict.bin} exists.
     * Loaded once at open whenever the dictionary file is present - also in read-only mode and
     * even when dictionary compression is currently disabled, because existing codec-3 records
     * cannot be decoded without it (whether new writes use the dictionary is decided per record
     * by {@link FolesiumConfig#dictionaryCompression()} in {@link ShardFile}). Shared by every
     * shard. A corrupt dictionary fails the open with a clear {@link FolesiumException}.
     */
    private final byte[] keyspaceDict;

    Keyspace(Path dir, String name, FolesiumConfig config, boolean readOnly) {
        this.name = name;
        int[] discovered = readOnly ? discoveredShardIndices(dir, name) : null;
        int shardCount;
        if (readOnly) {
            if (discovered.length == 0) {
                shardCount = 0;
            } else {
                // Routing must match the power-of-two mask the store was written with,
                // not the count of files found: a sparse or torn layout (missing shard
                // files) must still route every present file to itself. The shard file
                // header records the authoritative shard count; absent slots stay null.
                shardCount = readRecordedShardCount(
                        dir.resolve(String.format("%s-%04d.flog", name, discovered[0])));
            }
        } else {
            shardCount = config.shardCount();
        }
        this.shards = new ShardFile[shardCount];
        this.shardMask = shardCount - 1;
        this.pageIndex = createPageIndex(dir, name, config, readOnly);
        try {
            this.keyspaceDict = loadKeyspaceDict(dir, name);
            for (int i = 0; i < shards.length; i++) {
                if (readOnly && Arrays.binarySearch(discovered, i) < 0) {
                    // Read-only: no shard file exists for this index (a keyspace that was
                    // never written, or an old layout with fewer shards than the current
                    // configuration expects). Read-only shards must never create the file,
                    // so leave the slot null: reads treat the shard as absent data, and the
                    // iteration/maintenance paths skip it (see {@link #liveShards}).
                    continue;
                }
                String shardName = String.format("%s-%04d", name, i);
                shards[i] = new ShardFile(dir.resolve(shardName + ".flog"), i, config, pageIndex,
                        shardName, config.indexMode() == FolesiumConfig.IndexMode.PAGE, keyspaceDict, readOnly);
            }
        } catch (RuntimeException e) {
            // One bad shard must not leak the handles of the shards already opened: nobody
            // holds a reference to this half-built keyspace, so nothing else can close them.
            for (ShardFile s : shards) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (RuntimeException suppressed) {
                        e.addSuppressed(suppressed);
                    }
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
        this.liveShards = readOnly
                ? Arrays.stream(shards).filter(Objects::nonNull).toArray(ShardFile[]::new)
                : shards;
    }

    /**
     * Indices of the shard files ({@code <name>-NNNN.flog}) present in {@code dir},
     * sorted ascending. Read-only opens discover the shard topology from disk instead of
     * trusting {@link FolesiumConfig#shardCount()}: the current configuration (or the
     * store metadata) may name more shards than the files actually written - an old
     * layout, or a keyspace that was never written to - and a read-only shard must never
     * create or touch a missing file. Files that do not belong to this keyspace (other
     * keyspaces, {@code .fidx} hints, {@code .tmp} scratch files) are ignored.
     */
    private static int[] discoveredShardIndices(Path dir, String name) {
        String prefix = name + "-";
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(fn -> fn.startsWith(prefix) && fn.endsWith(".flog"))
                    .map(fn -> fn.substring(prefix.length(), fn.length() - ".flog".length()))
                    .filter(Keyspace::isDecimalIndex)
                    .mapToInt(Integer::parseInt)
                    .sorted()
                    .toArray();
        } catch (IOException e) {
            throw new FolesiumException("Cannot list " + dir + " to discover the shards of keyspace '"
                    + name + "'", e);
        }
    }

    /**
     * Reads the authoritative shard count from a shard file header (shard count at
     * offset 12, u32 big-endian, matching {@code ShardFile}'s file header). Validates
     * the power-of-two invariant so the read-only routing mask is always legal.
     */
    private static int readRecordedShardCount(Path shardFile) {
        try (FileChannel ch = FileChannel.open(shardFile, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(16);
            int n = ch.read(header, 0);
            if (n < 16) {
                throw new FolesiumException("Shard file too short to read its header: " + shardFile);
            }
            header.flip();
            header.position(12);
            int count = header.getInt();
            if (count < 1 || count > 1024 || Integer.bitCount(count) != 1) {
                throw new FolesiumException("Invalid shard count " + count + " in header of " + shardFile);
            }
            return count;
        } catch (IOException e) {
            throw new FolesiumException("Cannot read shard header " + shardFile, e);
        }
    }

    private static boolean isDecimalIndex(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
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
     * Loads the per-keyspace dictionary ({@code <store>/idx/<name>/dict.bin}) for a
     * region-keyed keyspace. The dictionary is objective data required to decode existing
     * codec-3 (ZSTD_DICT) records, so it is loaded whenever the file exists - regardless of
     * whether dictionary compression is currently enabled (the write path decides per record
     * in {@link ShardFile}, gated on {@link FolesiumConfig#dictionaryCompression()}). Missing
     * dictionary means no codec-3 record can exist yet, so {@code null} (plain compression) is
     * correct. A corrupt or unreadable dictionary fails the open: codec-3 records would be
     * undecodable. Also loaded in read-only mode, where reads of codec-3 records still need it.
     */
    private static byte[] loadKeyspaceDict(Path dir, String name) {
        if (!isRegionKeyed(name)) {
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
                    + "Restore dict.bin from a backup, or delete it and re-run the conversion "
                    + "(dictionary compression must be re-enabled to write codec-3 records).", e);
        } catch (IOException e) {
            throw new FolesiumException("Cannot load the dictionary of keyspace '" + name + "' from " + dictFile, e);
        }
    }

    public String name() {
        return name;
    }

    /**
     * Number of shard slots in this keyspace's layout. Read-write keyspaces return
     * {@link FolesiumConfig#shardCount()}; read-only keyspaces return the on-disk layout
     * discovered at open - the highest present shard index plus one, {@code 0} when no
     * shard file exists. Slots whose file is missing on disk are {@code null} (see
     * {@link #shards}); routing via {@link #shardIndexFor(byte[])} never exceeds this
     * count.
     */
    public int shardCount() {
        return shards.length;
    }

    /**
     * The shards of this keyspace, in routing order, with absent read-only shards (no
     * file on disk) omitted. Package-private: the database reads it to collect
     * compaction candidates across keyspaces for workload-ordered compaction. The array
     * is fixed at construction and never mutated; callers must not modify it.
     */
    ShardFile[] shards() {
        return liveShards;
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
        for (ShardFile s : liveShards) {
            s.applyRuntimeConfig(next);
        }
    }

    private ShardFile shardFor(byte[] key) {
        if (shards.length == 0) {
            // No shard files exist at all (an empty read-only store): every key is absent.
            return null;
        }
        return shards[(int) (Bytes.mix64(key) & shardMask)];
    }

    /**
     * The shard index of {@code key} under this keyspace's routing mask, or {@code -1}
     * when the keyspace has no shards at all (an empty read-only store).
     */
    public int shardIndexFor(byte[] key) {
        if (shards.length == 0) {
            return -1;
        }
        return (int) (Bytes.mix64(key) & shardMask);
    }

    // ------------------------------------------------------------- byte[] API

    public byte[] get(byte[] key) {
        ShardFile shard = shardFor(key);
        return shard == null ? null : shard.get(new Bytes(key));
    }

    public boolean contains(byte[] key) {
        ShardFile shard = shardFor(key);
        return shard != null && shard.contains(new Bytes(key));
    }

    public void put(byte[] key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("null value; use delete()");
        }
        requireShardForWrite(key).put(new Bytes(key), value);
    }

    /** Stores the value only if the key is absent; returns {@code true} if written. */
    public boolean putIfAbsent(byte[] key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("null value; use delete()");
        }
        return requireShardForWrite(key).putIfAbsent(new Bytes(key), value);
    }

    public void delete(byte[] key) {
        requireShardForWrite(key).delete(new Bytes(key));
    }

    /**
     * The shard owning {@code key}, failing loudly when no shard exists for it. A null
     * slot means the key's shard file is absent from disk - only possible in a
     * read-only keyspace (read-write keyspaces eagerly open every shard), where writes
     * must never be silently dropped. The read paths ({@link #get}, {@link #contains})
     * treat the same situation as absent data instead.
     */
    private ShardFile requireShardForWrite(byte[] key) {
        ShardFile shard = shardFor(key);
        if (shard == null) {
            throw new IllegalStateException("Cannot write in read-only keyspace '" + name
                    + "': the key's shard file is missing from disk");
        }
        return shard;
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
        for (ShardFile s : liveShards) {
            s.forEach(consumer);
        }
    }

    /**
     * Iterates every live key without reading any value - much cheaper than {@link #forEach}
     * when only the key set is needed, since no record is read back or decompressed.
     */
    public void forEachKey(java.util.function.Consumer<byte[]> consumer) {
        for (ShardFile s : liveShards) {
            s.forEachKey(consumer);
        }
    }

    /**
     * Iterates one shard only; lets callers parallelise a full scan safely. A shard
     * absent from disk in a read-only keyspace is skipped.
     */
    public void forEachShard(int shardIndex, BiConsumer<byte[], byte[]> consumer) {
        if (shardIndex < 0 || shardIndex >= shards.length) {
            throw new IndexOutOfBoundsException("shardIndex " + shardIndex + " outside [0,"
                    + shards.length + ") of keyspace '" + name + "'");
        }
        ShardFile shard = shards[shardIndex];
        if (shard != null) {
            shard.forEach(consumer);
        }
    }

    public void flush() {
        for (ShardFile s : liveShards) {
            s.flushIfDirty();
        }
        // Log-first: dirty pages must never be persisted ahead of the log data they
        // reference. The shard forces above ran first, so flushing here is safe.
        if (pageIndex != null) {
            pageIndex.flush();
        }
    }

    public void compactIfNeeded() {
        for (ShardFile s : liveShards) {
            if (s.needsCompaction()) {
                s.compact();
            }
        }
    }

    public void compactAll() {
        for (ShardFile s : liveShards) {
            s.compact();
        }
    }

    public long count() {
        long n = 0;
        for (ShardFile s : liveShards) {
            n += s.count();
        }
        return n;
    }

    public long sizeBytes() {
        long n = 0;
        for (ShardFile s : liveShards) {
            n += s.sizeBytes();
        }
        return n;
    }

    public long deadBytes() {
        long n = 0;
        for (ShardFile s : liveShards) {
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
     * The per-keyspace codec-3 (ZSTD_DICT) dictionary of this keyspace, or {@code null} when
     * none is loaded. Package-private: the resharder forwards it to the staged shard files so
     * the rewritten records keep using the same trained dictionary.
     */
    byte[] keyspaceDict() {
        return keyspaceDict;
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
        for (ShardFile s : liveShards) {
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
