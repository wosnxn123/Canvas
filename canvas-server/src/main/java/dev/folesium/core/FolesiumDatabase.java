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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.folesium.core.util.ZstdNative;

/**
 * Top-level Folesium store for a single Minecraft dimension, or for a world's
 * player data.
 *
 * <p>Layout on disk:</p>
 * <pre>
 * &lt;dir&gt;/folesium.properties       store metadata (format version, role, shard count, compression)
 * &lt;dir&gt;/chunks-0000.flog          append-only shard logs
 * &lt;dir&gt;/chunks-0000.flog.fidx     clean-shutdown index hint (optional, regenerable)
 * ...
 * </pre>
 *
 * <p>Both kinds of store live inside the save directory next to the vanilla data
 * they mirror, exactly like cesium-fabric's {@code chunks.db} / {@code players.db}:</p>
 * <pre>
 * &lt;world&gt;/folesium/                             role=players   (playerdata, advancements, stats)
 * &lt;world&gt;/dimensions/&lt;ns&gt;/&lt;path&gt;/folesium/       role=dimension (chunks, entities, poi)
 * </pre>
 *
 * <p>The {@link StoreRole role} is recorded in the metadata file rather than being
 * inferred from the directory name. Tools therefore never have to guess what a
 * {@code folesium/} directory contains — see {@link #readRole(Path)}.</p>
 *
 * <p>Thread model: fully thread-safe. Keyspaces are created lazily via a
 * {@link ConcurrentHashMap}; per-key mutual exclusion is provided by the owning
 * shard. Folia region threads may call {@link Keyspace#put} concurrently for
 * chunks belonging to different regions with no cross-region blocking.</p>
 */
public final class FolesiumDatabase implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("Folesium");

    public static final String METADATA_FILE = "folesium.properties";
    public static final int STORE_VERSION = 1;

    /** Default store directory name, used both for dimension and player stores. */
    public static final String STORE_DIR_NAME = "folesium";

    public static final String KS_CHUNKS = "chunks";
    public static final String KS_ENTITIES = "entities";
    public static final String KS_POI = "poi";
    public static final String KS_PLAYERDATA = "playerdata";
    public static final String KS_ADVANCEMENTS = "advancements";
    public static final String KS_STATS = "stats";
    public static final String KS_MISC = "misc";

    /**
     * What a store directory holds. Recorded in {@code folesium.properties} as
     * {@code store.role} so that no tool has to infer a store's purpose from its
     * location — a world-root store and a dimension store may both be named
     * {@code folesium/} without any ambiguity.
     */
    public enum StoreRole {
        /** Per-dimension chunk data: {@code chunks}, {@code entities}, {@code poi}. */
        DIMENSION,
        /** Per-world player data: {@code playerdata}, {@code advancements}, {@code stats}. */
        PLAYERS;

        /** Stores written before roles existed are dimension stores. */
        public static final StoreRole LEGACY_DEFAULT = DIMENSION;
    }

    private final Path dir;
    private final FolesiumConfig config;
    private final StoreRole role;
    private final Map<String, Keyspace> keyspaces = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread flusher;

    public static FolesiumDatabase open(Path dir, FolesiumConfig config) {
        return new FolesiumDatabase(dir, config, StoreRole.DIMENSION);
    }

    public static FolesiumDatabase open(Path dir) {
        return new FolesiumDatabase(dir, FolesiumConfig.defaults(), StoreRole.DIMENSION);
    }

    public static FolesiumDatabase open(Path dir, FolesiumConfig config, StoreRole role) {
        return new FolesiumDatabase(dir, config, role);
    }

    /**
     * Peeks at the role of the store in {@code dir} without opening it (no shard
     * files are touched, no group-commit thread is started).
     *
     * @return the recorded role, or {@code null} if {@code dir} holds no Folesium store
     */
    public static StoreRole readRole(Path dir) {
        Path meta = dir.resolve(METADATA_FILE);
        if (!Files.isRegularFile(meta)) {
            // A store directory always has metadata; treat anything else as "not a store".
            return null;
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(meta)) {
            p.load(in);
        } catch (IOException e) {
            throw new FolesiumException("Cannot read " + meta, e);
        }
        return parseRole(p.getProperty("store.role"), meta);
    }

    private static StoreRole parseRole(String raw, Path meta) {
        if (raw == null || raw.isBlank()) {
            return StoreRole.LEGACY_DEFAULT;
        }
        try {
            return StoreRole.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FolesiumException("Unknown store.role '" + raw + "' in " + meta);
        }
    }

    private FolesiumDatabase(Path dir, FolesiumConfig requested, StoreRole requestedRole) {
        this.dir = dir;
        this.role = requestedRole;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new FolesiumException("Cannot create store directory " + dir, e);
        }
        this.config = reconcileMetadata(requested);

        if (config.compression() == FolesiumConfig.Compression.ZSTD && !ZstdNative.available()) {
            throw new FolesiumException("Folesium store at " + dir
                    + " is configured for ZSTD compression, but zstd-jni is not available on the classpath. "
                    + "Run on a Folia/Canvas server (which ships zstd-jni) or add com.github.luben:zstd-jni.");
        }

        if (config.durability() == FolesiumConfig.DurabilityMode.BATCH) {
            this.flusher = Thread.ofPlatform().daemon().name("folesium-groupcommit-" + dir.getFileName()).unstarted(this::flushLoop);
            this.flusher.start();
        } else {
            this.flusher = null;
        }
    }

    /**
     * The on-disk metadata is authoritative for shard count and compression:
     * changing them for an existing store would re-route keys / break records.
     */
    private FolesiumConfig reconcileMetadata(FolesiumConfig requested) {
        Path meta = dir.resolve(METADATA_FILE);
        Properties p = new Properties();
        if (Files.exists(meta)) {
            try (var in = Files.newInputStream(meta)) {
                p.load(in);
            } catch (IOException e) {
                throw new FolesiumException("Cannot read " + meta, e);
            }
            int version = Integer.parseInt(p.getProperty("store.version", "0"));
            if (version != STORE_VERSION) {
                throw new FolesiumException("Unsupported Folesium store version " + version + " at " + dir
                        + " (this build supports " + STORE_VERSION + ")");
            }
            StoreRole onDisk = parseRole(p.getProperty("store.role"), meta);
            if (onDisk != role) {
                throw new FolesiumException("Folesium store at " + dir + " holds " + onDisk
                        + " data but was opened as " + role
                        + ". Refusing to mix player data and chunk data in one store.");
            }
            int shards = Integer.parseInt(p.getProperty("store.shardCount"));
            FolesiumConfig.Compression comp =
                    FolesiumConfig.Compression.valueOf(p.getProperty("store.compression"));
            if (shards != requested.shardCount()) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Folesium: existing store at {0} uses {1} shards; overriding requested {2}",
                        dir, shards, requested.shardCount());
            }
            if (comp != requested.compression()) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Folesium: existing store at {0} was written with {1}; new writes will use {1}", dir, comp);
            }
            return requested.withShardCount(shards).withCompression(comp);
        }
        p.setProperty("store.version", Integer.toString(STORE_VERSION));
        p.setProperty("store.role", role.name());
        p.setProperty("store.shardCount", Integer.toString(requested.shardCount()));
        p.setProperty("store.compression", requested.compression().name());
        p.setProperty("store.created", Long.toString(System.currentTimeMillis()));
        try (var out = Files.newOutputStream(meta)) {
            p.store(out, "Folesium store metadata - do not edit while the server is running");
        } catch (IOException e) {
            throw new FolesiumException("Cannot write " + meta, e);
        }
        return requested;
    }

    public Path directory() {
        return dir;
    }

    public FolesiumConfig config() {
        return config;
    }

    /** What this store holds; see {@link StoreRole}. */
    public StoreRole role() {
        return role;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Returns (creating on first use) the named keyspace. */
    public Keyspace keyspace(String name) {
        if (closed.get()) {
            throw new FolesiumException("Database is closed: " + dir);
        }
        return keyspaces.computeIfAbsent(name, n -> new Keyspace(dir, n, config));
    }

    public Keyspace chunks() {
        return keyspace(KS_CHUNKS);
    }

    public Keyspace entities() {
        return keyspace(KS_ENTITIES);
    }

    public Keyspace poi() {
        return keyspace(KS_POI);
    }

    public Keyspace playerData() {
        return keyspace(KS_PLAYERDATA);
    }

    public Keyspace advancements() {
        return keyspace(KS_ADVANCEMENTS);
    }

    public Keyspace stats() {
        return keyspace(KS_STATS);
    }

    public Map<String, Keyspace> openKeyspaces() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(keyspaces));
    }

    /** fsyncs every dirty shard of every open keyspace. */
    public void flush() {
        for (Keyspace ks : keyspaces.values()) {
            ks.flush();
        }
    }

    public void compactIfNeeded() {
        for (Keyspace ks : keyspaces.values()) {
            ks.compactIfNeeded();
        }
    }

    private void flushLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(config.batchFlushMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                flush();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium group-commit failed for " + dir, e);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (flusher != null) {
            flusher.interrupt();
            try {
                flusher.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        FolesiumException first = null;
        for (Keyspace ks : keyspaces.values()) {
            try {
                ks.close();
            } catch (FolesiumException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        keyspaces.clear();
        if (first != null) {
            throw first;
        }
    }
}
