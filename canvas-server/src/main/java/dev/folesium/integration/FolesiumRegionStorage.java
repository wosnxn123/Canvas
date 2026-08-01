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

import dev.folesium.core.FolesiumDatabase;
import dev.folesium.core.FolesiumRegistry;
import dev.folesium.core.Keyspace;
import dev.folesium.core.util.LongKeys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

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
 * <p>There is <em>no lazy migration</em>: a chunk missing from the store is simply
 * absent (the world is generated fresh), and an existing Anvil world must be
 * converted with {@code --folesiumConvertToFolesium} <em>before</em> Folesium is
 * enabled. The vanilla {@code .mca} files are never read once Folesium is on.</p>
 */
public final class FolesiumRegionStorage implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger("Folesium");

    /** Directory of the shared per-dimension store (used to release the registry reference). */
    private final Path storeDir;
    private final String keyspaceName;
    private final FolesiumDatabase database;
    private final Keyspace keyspace;
    /**
     * CAS, not a plain flag: the server closes storages from world unload and from the
     * shutdown hook, and a double release would decrement the registry's reference count
     * twice, closing a store another dimension is still using.
     */
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();

    private FolesiumRegionStorage(Path storeDir, String keyspaceName, FolesiumDatabase database) {
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
        return new FolesiumRegionStorage(storeDir, keyspaceName, db);
    }

    /**
     * {@code <dim>/region} -> {@code <dim>/folesium}.
     *
     * <p>The world root's player store is called {@code folesium/} too; the two are told
     * apart by the {@code store.role} recorded in each store's metadata, never by path.</p>
     */
    public static Path storeDirectoryFor(Path folder) {
        Path parent = folder.toAbsolutePath().normalize().getParent();
        String name = FolesiumDatabase.STORE_DIR_NAME;
        return parent == null ? folder.resolve(name) : parent.resolve(name);
    }

    /** {@code region} -> {@code chunks}, {@code poi} -> {@code poi}, {@code entities} -> {@code entities}. */
    public static String keyspaceFor(Path folder) {
        Path name = folder.getFileName();
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
        if (data == null) {
            // No lazy migration: a chunk absent from the store is absent. An existing
            // Anvil world must be converted with --folesiumConvertToFolesium before
            // Folesium is enabled; the .mca files are never read while Folesium is on.
            return null;
        }
        return deserialise(data);
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            keyspace.flush();
        } finally {
            // release() must always run: if flush() throws and the registry reference were
            // skipped, the store, its shards and the group-commit thread would leak until
            // shutdown. The flush failure still propagates to the caller.
            // Pass the instance we actually hold: if the registry has since reopened the store
            // (e.g. after closeAll()), this release must not decrement the new one.
            FolesiumRegistry.release(storeDir, database);
        }
    }
}
