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

package dev.folesium.converter;

import dev.folesium.core.FolesiumConfig;
import dev.folesium.core.FolesiumDatabase;
import dev.folesium.core.Keyspace;
import dev.folesium.core.util.UuidKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a world's player data between the vanilla per-player files
 * ({@code playerdata/<uuid>.dat}, {@code advancements/<uuid>.json},
 * {@code stats/<uuid>.json}) and a Folesium store with
 * {@link FolesiumDatabase.StoreRole#PLAYERS role=players}.
 *
 * <p>Values are stored as the <em>exact bytes of the source file</em>: the gzip-compressed
 * NBT for {@code .dat}, the UTF-8 JSON text for {@code .json}. The converter therefore
 * never parses NBT or JSON, which keeps it version-independent, lossless and free of any
 * Minecraft dependency — the same property that makes the chunk converter safe.</p>
 *
 * <p>Keys are the raw 16 bytes of the player UUID, see {@link UuidKeys}.</p>
 *
 * <p>Vanilla's {@code .dat_old} rotation backups are deliberately <em>not</em> imported:
 * they are a crash-recovery artefact of the file-rename save protocol, which Folesium
 * replaces with a crash-safe append-only log. They stay on disk untouched.</p>
 */
public final class PlayerDataConverter {

    /** Vanilla directory holding {@code <uuid>.dat} player NBT (pre-26.x layout). */
    public static final String DIR_PLAYERDATA = "playerdata";
    /** Vanilla directory holding {@code <uuid>.json} advancement progress. */
    public static final String DIR_ADVANCEMENTS = "advancements";
    /** Vanilla directory holding {@code <uuid>.json} statistics. */
    public static final String DIR_STATS = "stats";
    /**
     * Minecraft 26.x groups the per-player files under one {@code players/} directory:
     * {@code players/data/<uuid>.dat}, {@code players/advancements/<uuid>.json},
     * {@code players/stats/<uuid>.json} ({@code LevelResource.PLAYER_DATA_DIR} et al.).
     */
    public static final String DIR_PLAYERS_26 = "players";
    /** 26.x replacement for {@link #DIR_PLAYERDATA}, nested under {@code players/}. */
    public static final String DIR_DATA_26 = "data";

    /** Vanilla dir -> (Folesium keyspace, file extension). */
    private record Mapping(String dir, String keyspace, String extension) {
    }

    private static final List<Mapping> LEGACY_MAPPINGS = List.of(
            new Mapping(DIR_PLAYERDATA, FolesiumDatabase.KS_PLAYERDATA, ".dat"),
            new Mapping(DIR_ADVANCEMENTS, FolesiumDatabase.KS_ADVANCEMENTS, ".json"),
            new Mapping(DIR_STATS, FolesiumDatabase.KS_STATS, ".json")
    );

    private static final List<Mapping> MODERN_MAPPINGS = List.of(
            new Mapping(DIR_DATA_26, FolesiumDatabase.KS_PLAYERDATA, ".dat"),
            new Mapping(DIR_ADVANCEMENTS, FolesiumDatabase.KS_ADVANCEMENTS, ".json"),
            new Mapping(DIR_STATS, FolesiumDatabase.KS_STATS, ".json")
    );

    /**
     * The directory that holds the per-player vanilla directories <em>and</em> the player
     * store: {@code <world>/players} on a 26.x world, the world root itself on the older
     * layout. This mirrors the server hook, which anchors the store next to the directory
     * {@code LevelResource.PLAYER_DATA_DIR} resolves to.
     */
    public static Path playerRootFor(Path worldRoot) {
        Path players = worldRoot.resolve(DIR_PLAYERS_26);
        return Files.isDirectory(players) ? players : worldRoot;
    }

    private static List<Mapping> mappingsFor(Path worldRoot, Path playerRoot) {
        return playerRoot.equals(worldRoot) ? LEGACY_MAPPINGS : MODERN_MAPPINGS;
    }

    /**
     * The vanilla files always live next to the store, so once a store location is
     * fixed the store's parent is the player root — this keeps a conversion coherent
     * even if a world somehow has both a {@code players/} directory and a legacy
     * world-root store.
     */
    private static Path playerRootOf(Path worldRoot, Path storeDir) {
        Path parent = storeDir.getParent();
        return parent != null ? parent : playerRootFor(worldRoot);
    }

    private static final Pattern UUID_FILE = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.(dat|json)");

    /** Per-run counters. {@code entries} counts individual player records moved. */
    public record Stats(long entries, long bytes, long millis) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d player records (%.1f KiB) in %d ms",
                    entries, bytes / 1024.0, millis);
        }
    }

    private PlayerDataConverter() {
    }

    /**
     * Returns {@code true} if {@code worldRoot} has any vanilla player data to import.
     * Used to skip the player step entirely on worlds that never had a player join.
     */
    public static boolean hasVanillaPlayerData(Path worldRoot) {
        Path playerRoot = playerRootFor(worldRoot);
        for (Mapping m : mappingsFor(worldRoot, playerRoot)) {
            if (Files.isDirectory(playerRoot.resolve(m.dir()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The player store directory for a world root: {@code <world>/players/folesium} on
     * the 26.x layout, {@code <world>/folesium} on the older one. An existing store with
     * {@code store.role=PLAYERS} at either location wins, so a world converted under one
     * layout keeps using its store even if the directory shape changes around it.
     */
    public static Path storeDirectoryFor(Path worldRoot) {
        Path modern = worldRoot.resolve(DIR_PLAYERS_26).resolve(FolesiumDatabase.STORE_DIR_NAME);
        Path legacy = worldRoot.resolve(FolesiumDatabase.STORE_DIR_NAME);
        if (FolesiumDatabase.readRole(modern) == FolesiumDatabase.StoreRole.PLAYERS) {
            return modern;
        }
        if (FolesiumDatabase.readRole(legacy) == FolesiumDatabase.StoreRole.PLAYERS) {
            return legacy;
        }
        return playerRootFor(worldRoot).resolve(FolesiumDatabase.STORE_DIR_NAME);
    }

    // ------------------------------------------------------- vanilla -> folesium

    /**
     * Imports every {@code <uuid>.dat} / {@code <uuid>.json} under {@code worldRoot}
     * into the player store.
     *
     * <p>Like the chunk converter this is <em>merge</em> mode: a record already in the
     * store (because a running server already migrated or updated it) is kept, and only
     * players missing from the store are taken from disk. Re-running the conversion after
     * playing therefore never rolls a player back to an older file. The vanilla files are
     * left in place as a backup.</p>
     */
    public static Stats anvilToFolesium(Path worldRoot, Path storeDir, FolesiumConfig config) throws IOException {
        long start = System.nanoTime();
        long entries = 0;
        long bytes = 0;

        Path playerRoot = playerRootOf(worldRoot, storeDir);
        try (FolesiumDatabase db = FolesiumDatabase.open(storeDir,
                config.withDurability(FolesiumConfig.DurabilityMode.EXPLICIT),
                FolesiumDatabase.StoreRole.PLAYERS)) {
            for (Mapping m : mappingsFor(worldRoot, playerRoot)) {
                Path src = playerRoot.resolve(m.dir());
                if (!Files.isDirectory(src)) {
                    continue;
                }
                Keyspace ks = db.keyspace(m.keyspace());
                for (Path file : listPlayerFiles(src, m.extension())) {
                    UUID id = uuidOf(file);
                    if (id == null) {
                        continue;
                    }
                    byte[] payload = Files.readAllBytes(file);
                    if (ks.putIfAbsent(id, payload)) {
                        entries++;
                        bytes += payload.length;
                    }
                }
            }
            db.flush();
        }
        return new Stats(entries, bytes, (System.nanoTime() - start) / 1_000_000);
    }

    // ------------------------------------------------------- folesium -> vanilla

    /**
     * Writes every stored player record back out as a vanilla file. Existing vanilla
     * files are overwritten, because the store is the authoritative copy at that point.
     *
     * <p>Like cesium-fabric's converter, <em>nothing is deleted</em>: the player store is
     * left in place as a backup. Delete it manually once the restored files have been
     * verified — and always before re-converting after having played on the restored
     * files, because {@link #anvilToFolesium} merges and would keep the (older) store
     * records over the newer vanilla files.</p>
     */
    public static Stats folesiumToAnvil(Path storeDir, Path worldRoot) throws IOException {
        long start = System.nanoTime();
        long entries = 0;
        long bytes = 0;
        if (!Files.isDirectory(storeDir)) {
            // Nothing was ever imported; opening would create an empty store out of
            // thin air (the converter must never create files it does not need).
            return new Stats(0, 0, (System.nanoTime() - start) / 1_000_000);
        }

        Path playerRoot = playerRootOf(worldRoot, storeDir);
        try (FolesiumDatabase db = FolesiumDatabase.open(storeDir,
                FolesiumConfig.defaults().withDurability(FolesiumConfig.DurabilityMode.EXPLICIT),
                FolesiumDatabase.StoreRole.PLAYERS)) {
            for (Mapping m : mappingsFor(worldRoot, playerRoot)) {
                Keyspace ks = db.keyspace(m.keyspace());
                if (ks.count() == 0) {
                    continue;
                }
                Path out = playerRoot.resolve(m.dir());
                Files.createDirectories(out);
                List<byte[][]> records = new ArrayList<>();
                ks.forEach((k, v) -> records.add(new byte[][]{k, v}));
                for (byte[][] record : records) {
                    if (record[0].length != UuidKeys.LENGTH) {
                        continue; // not a player key; leave it alone rather than guess
                    }
                    UUID id = UuidKeys.decode(record[0]);
                    Path file = out.resolve(id + m.extension());
                    Files.write(file, record[1]);
                    entries++;
                    bytes += record[1].length;
                }
            }
        }
        return new Stats(entries, bytes, (System.nanoTime() - start) / 1_000_000);
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> listPlayerFiles(Path dir, String extension) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .filter(p -> UUID_FILE.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }
    }

    private static UUID uuidOf(Path file) {
        Matcher m = UUID_FILE.matcher(file.getFileName().toString());
        if (!m.matches()) {
            return null;
        }
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
