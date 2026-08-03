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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Folesium replacement for the vanilla per-player files.
 *
 * <p>Vanilla stores one small file per player and per data type (26.x layout;
 * older versions used {@code <world>/playerdata} etc. directly under the world root):</p>
 * <pre>
 * &lt;world&gt;/players/data/&lt;uuid&gt;.dat           gzip NBT   (inventory, position, health, ...)
 * &lt;world&gt;/players/advancements/&lt;uuid&gt;.json  UTF-8 JSON
 * &lt;world&gt;/players/stats/&lt;uuid&gt;.json         UTF-8 JSON
 * </pre>
 * <p>Every autosave rewrites, renames and fsyncs each of those files, which on a
 * busy server turns into hundreds of tiny synchronous file operations. Folesium
 * routes all three into one store next to them ({@code <world>/players/folesium}
 * on 26.x, {@code <world>/folesium} on the older layout -- the same place
 * cesium-fabric puts its {@code players.db}) with keyspaces {@code playerdata},
 * {@code advancements} and {@code stats}, so an autosave becomes a handful of
 * appends plus one group commit.</p>
 *
 * <h2>Storage format</h2>
 * <p>Values are the <em>exact bytes of the file vanilla would have written</em>:
 * gzip-compressed NBT for player data, UTF-8 JSON text for the other two. This is
 * what lets {@code folesium-converter} move records in and out losslessly without
 * ever parsing NBT or JSON, and it means a rollback reproduces byte-identical files.
 * Folesium's own per-record compression is applied on top and is transparent here.</p>
 *
 * <h2>Store role</h2>
 * <p>The store is opened with {@link FolesiumDatabase.StoreRole#PLAYERS}, which is
 * recorded in its metadata. A dimension store and this player store may therefore both
 * be named {@code folesium/} without any tool confusing one for the other, and opening
 * the wrong kind fails loudly instead of corrupting data.</p>
 *
 * <h2>No lazy migration</h2>
 * <p>There is <em>no</em> vanilla-file fallback: a player absent from the store is a new
 * player, and an existing world must be converted with {@code --folesiumConvertToFolesium}
 * before Folesium is enabled. The vanilla per-player files are never read while Folesium
 * is on (they are kept on disk only as a backup by the converter).</p>
 *
 * <h2>Thread model</h2>
 * <p>Safe to call from any thread. Keyspaces are sharded with per-shard locks, and player
 * UUIDs hash across shards, so concurrent autosaves of different players do not contend.</p>
 */
public final class FolesiumPlayerStorage implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger("Folesium");

    /**
     * The storage for the running server. A server has exactly one player data
     * directory, so a single handle is enough -- and it lets {@link FolesiumPlayerFiles}
     * redirect the advancement/statistics JSON without threading a reference through
     * {@code PlayerAdvancements} and {@code ServerStatsCounter}.
     */
    private static volatile FolesiumPlayerStorage active;

    /** Vanilla directory grouping the per-player files on the 26.x layout. */
    private static final String DIR_PLAYERS_26 = "players";

    private final Path worldRoot;
    private final Path storeDir;
    private final FolesiumDatabase database;
    private final Keyspace playerData;
    private final Keyspace advancements;
    private final Keyspace stats;
    private volatile boolean closed;
    /**
     * The final-flush hook registered in {@link #createIfEnabled}. Kept so {@link #close()}
     * can unregister it: a server that loads and unloads worlds repeatedly would otherwise
     * accumulate one hook per storage, each pinning a closed instance for the JVM's lifetime.
     */
    private volatile Thread shutdownHook;

    private FolesiumPlayerStorage(Path worldRoot, Path storeDir, FolesiumDatabase database) {
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
        this.storeDir = storeDir;
        this.database = database;
        this.playerData = database.keyspace(FolesiumDatabase.KS_PLAYERDATA);
        this.advancements = database.keyspace(FolesiumDatabase.KS_ADVANCEMENTS);
        this.stats = database.keyspace(FolesiumDatabase.KS_STATS);
    }

    /**
     * @param worldRoot the save directory, i.e. the parent of {@code playerdata/}
     * @return a player storage bound to that world, or {@code null} when Folesium is disabled
     */
    public static synchronized FolesiumPlayerStorage createIfEnabled(Path worldRoot) {
        if (!FolesiumRegistry.isEnabled()) {
            return null;
        }
        Path storeDir = storeDirectoryFor(worldRoot);
        FolesiumDatabase db = FolesiumRegistry.acquire(storeDir, FolesiumDatabase.StoreRole.PLAYERS);
        if (db == null) {
            // acquire() degrades to disabled (returns null) when the config file exists but
            // cannot be read; fall through to vanilla behaviour like isEnabled() == false.
            return null;
        }
        FolesiumPlayerStorage storage = new FolesiumPlayerStorage(worldRoot, storeDir, db);
        active = storage;
        // The server has no single "player storage close" call to hook, so guarantee a
        // final fsync at JVM exit. Everything before that is covered by the configured
        // durability mode (group commit by default, per-write fsync with durability=ALWAYS).
        Thread hook = Thread.ofPlatform().name("folesium-player-flush").unstarted(storage::flushQuietly);
        storage.shutdownHook = hook;
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down: the hook cannot be registered. Release
            // the acquired store instead of leaking it (active would point at an
            // instance whose final fsync never fires).
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: JVM already shutting down; releasing the player store instead of"
                            + " registering the flush hook", e);
            FolesiumRegistry.release(storeDir, database);
            storage.closed = true;
            active = null;
            return null;
        }
        LOGGER.log(System.Logger.Level.INFO, "Folesium: player data {0} -> {1}", worldRoot, storeDir);
        return storage;
    }

    /** The player storage of the running server, or {@code null} when Folesium is off. */
    public static FolesiumPlayerStorage active() {
        FolesiumPlayerStorage storage = active;
        return storage == null || storage.closed ? null : storage;
    }

    /**
     * The store directory for a world root: {@code <world>/players/folesium} on the 26.x
     * layout, {@code <world>/folesium} on the older one. An existing store with
     * {@code store.role=PLAYERS} at either location wins, so a world converted under one
     * layout keeps using its store even if the directory shape changes around it (a
     * pre-26 world upgraded to 26.x must not boot against a fresh empty store). Mirrors
     * {@code PlayerDataConverter.storeDirectoryFor()}.
     */
    public static Path storeDirectoryFor(Path worldRoot) {
        Path root = worldRoot.toAbsolutePath().normalize();
        // The PlayerDataStorage hook passes <world>/players (the parent of the 26.x
        // playerdata directory), not the world root itself. Ascend one level so the
        // legacy <world>/folesium store of a pre-26-converted world is found too; a
        // caller that passes the true world root is unaffected.
        if (DIR_PLAYERS_26.equalsIgnoreCase(root.getFileName() == null ? null : root.getFileName().toString())) {
            Path parent = root.getParent();
            if (parent != null) {
                root = parent;
            }
        }
        Path modern = root.resolve(DIR_PLAYERS_26).resolve(FolesiumDatabase.STORE_DIR_NAME);
        Path legacy = root.resolve(FolesiumDatabase.STORE_DIR_NAME);
        if (FolesiumDatabase.readRole(modern) == FolesiumDatabase.StoreRole.PLAYERS) {
            return modern;
        }
        if (FolesiumDatabase.readRole(legacy) == FolesiumDatabase.StoreRole.PLAYERS) {
            return legacy;
        }
        // Default follows the world's layout: next to players/ when present (26.x),
        // at the world root otherwise (pre-26 layout). The container name is matched
        // case-insensitively, mirroring PlayerDataConverter.playersContainer and
        // PlayerPathRecognizer: on a case-sensitive file system a container created as
        // PLAYERS/ must still count as the 26.x layout - a case-sensitive check here
        // would send the server to the legacy <world>/folesium store while the
        // converter keeps writing <world>/PLAYERS/folesium, splitting player data
        // across two stores (converted players silently starting fresh).
        Path players = playersContainer(root);
        return players == null ? legacy : players.resolve(FolesiumDatabase.STORE_DIR_NAME);
    }

    /**
     * The {@code players/} container under {@code worldRoot}, or {@code null} when there is
     * none. Mirrors {@code PlayerDataConverter.playersContainer}: the name is matched
     * case-insensitively so a container created as {@code PLAYERS/} on a case-sensitive
     * file system is still recognised as the 26.x layout.
     */
    private static Path playersContainer(Path worldRoot) {
        Path players = worldRoot.resolve(DIR_PLAYERS_26);
        if (Files.isDirectory(players)) {
            return players;
        }
        try (var s = Files.list(worldRoot)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> DIR_PLAYERS_26.equalsIgnoreCase(p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: cannot list {0} to locate the players/ container; treating the world"
                            + " as the pre-26 layout: {1}", worldRoot, e);
            return null;
        }
    }

    /**
     * The absolute, normalised save directory of this storage, exposed for the
     * {@code FolesiumPlayerFiles} path classifier. {@code package-private} because
     * only the {@code dev.folesium.integration} bridge uses it.
     */
    Path worldRootForClassify() {
        return worldRoot;
    }

    /** True if {@code dir} is the save directory this storage was created for. */
    public boolean ownsWorld(Path dir) {
        return dir != null && worldRoot.equals(dir.toAbsolutePath().normalize());
    }

    public FolesiumDatabase database() {
        return database;
    }

    /* ------------------------------------------------------------------ */
    /* player NBT                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Loads a player's NBT from the store. There is <em>no lazy migration</em>: a
     * player absent from the store is a new player, and an existing world must be
     * converted with {@code --folesiumConvertToFolesium} before Folesium is enabled.
     *
     * @return the tag, or {@code null} for a player that has never been saved
     */
    public CompoundTag loadPlayer(UUID id) throws IOException {
        byte[] raw = playerData.get(id);
        if (raw == null) {
            return null;
        }
        try (var in = new ByteArrayInputStream(raw)) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
    }

    /** Saves a player's NBT as the gzip-compressed bytes vanilla would have written. */
    public void savePlayer(UUID id, CompoundTag tag) throws IOException {
        try {
            if (tag == null) {
                playerData.delete(id);
                return;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 * 1024);
            NbtIo.writeCompressed(tag, bytes);
            playerData.put(id, bytes.toByteArray());
        } catch (RuntimeException e) {
            // The engine signals store failures as FolesiumException (a RuntimeException),
            // which vanilla's `catch (IOException)` around this call site does not catch --
            // an unhandled leak would abort the autosave. Match vanilla's warn-and-continue
            // semantics: log and skip this player's save.
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: failed to save player data for " + id, e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* advancements + statistics (JSON text)                               */
    /* ------------------------------------------------------------------ */

    /** @return the stored advancement JSON, or {@code null} */
    public String loadAdvancements(UUID id) throws IOException {
        return loadJson(advancements, id);
    }

    /** True when the store holds an advancement record for {@code id} (no payload read). */
    public boolean hasAdvancements(UUID id) {
        return advancements.contains(id);
    }

    public void saveAdvancements(UUID id, String json) {
        saveJson(advancements, id, json);
    }

    /** @return the stored statistics JSON, or {@code null} */
    public String loadStats(UUID id) throws IOException {
        return loadJson(stats, id);
    }

    /** True when the store holds a statistics record for {@code id} (no payload read). */
    public boolean hasStats(UUID id) {
        return stats.contains(id);
    }

    public void saveStats(UUID id, String json) {
        saveJson(stats, id, json);
    }

    private String loadJson(Keyspace keyspace, UUID id) throws IOException {
        byte[] raw = keyspace.get(id);
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    private void saveJson(Keyspace keyspace, UUID id, String json) {
        if (json == null) {
            keyspace.delete(id);
        } else {
            keyspace.put(id, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /* ------------------------------------------------------------------ */
    /* lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    /** fsyncs every dirty shard of the three player keyspaces. */
    public void flush() {
        playerData.flush();
        advancements.flush();
        stats.flush();
    }

    /** Shutdown-hook variant: a failure here must never abort JVM exit. */
    private void flushQuietly() {
        if (closed) {
            return;
        }
        try {
            flush();
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.ERROR, "Folesium: final player data flush failed", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (active == this) {
            active = null;
        }
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // close() is running from the shutdown sequence itself; the hook either
                // already ran or is about to, and flushQuietly() is a no-op once closed.
            }
        }
        try {
            flush();
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
