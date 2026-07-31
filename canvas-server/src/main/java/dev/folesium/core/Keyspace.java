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

import dev.folesium.core.shard.ShardFile;
import dev.folesium.core.util.Bytes;
import dev.folesium.core.util.LongKeys;
import dev.folesium.core.util.UuidKeys;

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

    Keyspace(Path dir, String name, FolesiumConfig config) {
        this.name = name;
        this.shards = new ShardFile[config.shardCount()];
        this.shardMask = config.shardCount() - 1;
        for (int i = 0; i < shards.length; i++) {
            shards[i] = new ShardFile(dir.resolve(String.format("%s-%04d.flog", name, i)), i, config);
        }
    }

    public String name() {
        return name;
    }

    public int shardCount() {
        return shards.length;
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

    /** Iterates one shard only; lets callers parallelise a full scan safely. */
    public void forEachShard(int shardIndex, BiConsumer<byte[], byte[]> consumer) {
        shards[shardIndex].forEach(consumer);
    }

    public void flush() {
        for (ShardFile s : shards) {
            s.flushIfDirty();
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
        if (first != null) {
            throw first;
        }
    }
}
