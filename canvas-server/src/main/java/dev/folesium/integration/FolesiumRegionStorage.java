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

package dev.folesium.integration;

import dev.folesium.anvil.AnvilRegionFile;
import dev.folesium.core.FolesiumDatabase;
import dev.folesium.core.FolesiumRegistry;
import dev.folesium.core.Keyspace;
import dev.folesium.core.util.LongKeys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Folesium replacement for one {@code RegionFileStorage} instance.
 *
 * <p>The server creates one {@code RegionFileStorage} per (dimension, data type):
 * {@code <dim>/region}, {@code <dim>/poi} and {@code <dim>/entities}. Folesium maps
 * all three onto a single store directory {@code <dim>/folesium} with one keyspace
 * each ({@code chunks}, {@code poi}, {@code entities}), so a dimension has exactly
 * one group-commit thread and one shard set.</p>
 *
 * <h2>Thread model</h2>
 * <p>Every method here is safe to call from any thread. Folesium shards each keyspace
 * and takes a per-shard read/write lock, so Folia region threads that save chunks in
 * different regions almost never contend (vanilla Anvil serialises on the
 * {@code RegionFile} object, cesium-fabric serialises on a single LMDB write lock).
 * Nothing in this class touches the main thread or any region-specific state, and no
 * lock is held across an NBT (de)serialisation.</p>
 *
 * <h2>Lazy migration</h2>
 * <p>When Folesium is enabled on a world that has not yet been converted, chunks
 * missing from the store are served from the original Anvil region files on read.
 * This keeps an un-converted world fully playable the moment Folesium is switched on,
 * and--combined with the merge-mode converter--makes the "enable Folesium, then convert
 * later" workflow data-safe: chunks a running server has already migrated (or edited)
 * are never clobbered by the older Anvil bytes.</p>
 */
public final class FolesiumRegionStorage implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger("Folesium");

    /** Directory of the shared per-dimension store (used to release the registry reference). */
    private final Path storeDir;
    /** The original Anvil folder this store was created from (used for lazy migration reads). */
    private final Path anvilFolder;
    private final String keyspaceName;
    private final FolesiumDatabase database;
    private final Keyspace keyspace;
    private volatile boolean closed;

    /**
     * Region keys whose {@code .mca} file is known to be absent. Used to skip the
     * stat/opens for regions already proven missing during lazy migration, avoiding
     * repeated filesystem probes for chunks that will never be found in Anvil.
     */
    private final Set<Long> anvilMissing = ConcurrentHashMap.newKeySet();

    private FolesiumRegionStorage(Path anvilFolder, Path storeDir, String keyspaceName, FolesiumDatabase database) {
        this.anvilFolder = anvilFolder;
        this.storeDir = storeDir;
        this.keyspaceName = keyspaceName;
        this.database = database;
        this.keyspace = database.keyspace(keyspaceName);
    }

    /**
     * @param folder the Anvil folder the vanilla storage would have used, e.g. {@code world/region}
     * @return a Folesium storage bound to that folder, or {@code null} when Folesium is disabled
     */
    public static FolesiumRegionStorage createIfEnabled(Path folder) {
        if (!FolesiumRegistry.isEnabled()) {
            return null;
        }
        Path storeDir = storeDirectoryFor(folder);
        String keyspaceName = keyspaceFor(folder);
        FolesiumDatabase db = FolesiumRegistry.acquire(storeDir);
        LOGGER.log(System.Logger.Level.INFO, "Folesium: {0} -> {1}#{2}", folder, storeDir, keyspaceName);
        return new FolesiumRegionStorage(folder, storeDir, keyspaceName, db);
    }

    /**
     * {@code <dim>/region} -> {@code <dim>/folesium}.
     *
     * <p>The world root's player store is called {@code folesium/} too; the two are told
     * apart by the {@code store.role} recorded in each store's metadata, never by path.</p>
     */
    public static Path storeDirectoryFor(Path anvilFolder) {
        Path parent = anvilFolder.toAbsolutePath().normalize().getParent();
        String name = FolesiumDatabase.STORE_DIR_NAME;
        return parent == null ? anvilFolder.resolve(name) : parent.resolve(name);
    }

    /** {@code region} -> {@code chunks}, {@code poi} -> {@code poi}, {@code entities} -> {@code entities}. */
    public static String keyspaceFor(Path anvilFolder) {
        Path name = anvilFolder.getFileName();
        String raw = name == null ? "misc" : name.toString().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "region" -> FolesiumDatabase.KS_CHUNKS;
            case "poi" -> FolesiumDatabase.KS_POI;
            case "entities" -> FolesiumDatabase.KS_ENTITIES;
            default -> raw;
        };
    }

    public String keyspaceName() {
        return keyspaceName;
    }

    public Keyspace keyspace() {
        return keyspace;
    }

    public FolesiumDatabase database() {
        return database;
    }

    /* ------------------------------------------------------------------ */
    /* NBT <-> bytes                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Serialises a chunk tag to uncompressed NBT bytes. Compression is Folesium's job
     * (per-record, configurable), which keeps this step allocation-cheap and lets the
     * caller run it off the region thread exactly like Moonrise does today.
     */
    public static byte[] serialise(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return bytes.toByteArray();
    }

    public static CompoundTag deserialise(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }

    /* ------------------------------------------------------------------ */
    /* storage operations                                                  */
    /* ------------------------------------------------------------------ */

    public boolean has(int chunkX, int chunkZ) {
        return keyspace.contains(LongKeys.chunkKey(chunkX, chunkZ));
    }

    public byte[] readRaw(int chunkX, int chunkZ) {
        return keyspace.get(LongKeys.chunkKey(chunkX, chunkZ));
    }

    public CompoundTag read(int chunkX, int chunkZ) throws IOException {
        byte[] data = readRaw(chunkX, chunkZ);
        if (data != null) {
            return deserialise(data);
        }
        // Lazy migration: a chunk absent from the Folesium store is served from the
        // original Anvil region file. This keeps an un-converted world fully playable
        // the instant Folesium is enabled, and together with the merge-mode converter
        // it makes the "enable Folesium, then convert later" workflow data-safe.
        return readFromAnvil(chunkX, chunkZ);
    }

    private CompoundTag readFromAnvil(int chunkX, int chunkZ) throws IOException {
        if (anvilFolder == null) {
            return null;
        }
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long regionKey = (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);
        if (anvilMissing.contains(regionKey)) {
            return null;
        }
        Path mca = anvilFolder.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
        if (!Files.isRegularFile(mca)) {
            anvilMissing.add(regionKey);
            return null;
        }
        // Open the region file on demand and close it immediately: keeping a reader
        // cached per region would leak file descriptors on a large, long-running,
        // un-converted world. The cost of re-opening is a couple of header reads, which
        // is negligible next to the chunk load itself.
        try (AnvilRegionFile rf = new AnvilRegionFile(mca)) {
            byte[] raw = rf.readChunk(chunkX & 31, chunkZ & 31);
            return raw == null ? null : deserialise(raw);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: Anvil fallback read failed for {0}: {1}", mca, e.getMessage());
            return null;
        }
    }

    public void writeRaw(int chunkX, int chunkZ, byte[] data) {
        keyspace.put(LongKeys.chunkKey(chunkX, chunkZ), data);
    }

    public void write(int chunkX, int chunkZ, CompoundTag tag) throws IOException {
        if (tag == null) {
            delete(chunkX, chunkZ);
        } else {
            writeRaw(chunkX, chunkZ, serialise(tag));
        }
    }

    public void delete(int chunkX, int chunkZ) {
        keyspace.delete(LongKeys.chunkKey(chunkX, chunkZ));
    }

    /** fsyncs every dirty shard of this keyspace. */
    public void flush() {
        keyspace.flush();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        anvilMissing.clear();
        keyspace.flush();
        FolesiumRegistry.release(storeDir);
    }
}
