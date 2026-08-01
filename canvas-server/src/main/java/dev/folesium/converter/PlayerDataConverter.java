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
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Minecraft dependency -- the same property that makes the chunk converter safe.</p>
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

    /**
     * The mapping set follows the world's layout, not the store's location: a world
     * with a {@code players/} directory uses the 26.x directories even when an
     * existing PLAYERS store sits at the legacy world-root location (see
     * {@link #storeDirectoryFor}). The vanilla root is therefore always derived
     * from the world layout via {@link #playerRootFor}; the store location is used
     * for the store itself only.
     */
    private static List<Mapping> mappingsFor(Path worldRoot, Path playerRoot) {
        return playerRoot.equals(worldRoot) ? LEGACY_MAPPINGS : MODERN_MAPPINGS;
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
     *
     * <p>With {@code backupOnConvert} an existing store is moved to a
     * {@code .folesium-backup-*} sibling first, turning the merge into a full rebuild of
     * the store.</p>
     */
    public static Stats anvilToFolesium(Path worldRoot, Path storeDir, FolesiumConfig config) throws IOException {
        long start = System.nanoTime();
        long entries = 0;
        long bytes = 0;

        if (config.backupOnConvert() && Files.isDirectory(storeDir)) {
            movePath(storeDir, backupPath(storeDir), false);
        }

        Path playerRoot = playerRootFor(worldRoot);
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
     * Materializes every player keyspace back into the vanilla per-player files.
     *
     * <p>Default (cesium-fabric parity): each record is written straight into the
     * target directory, atomically replacing any existing file of the same name;
     * files that are not in the store are left untouched.</p>
     *
     * <p>With {@code backupOnConvert} a clean staging directory is built first and
     * swapped in, and the previous directory is moved to a unique
     * {@code .folesium-backup-*} sibling, so stale UUID files and empty-keyspace
     * remnants cannot survive the rollback.</p>
     */
    public static Stats folesiumToAnvil(Path storeDir, Path worldRoot, FolesiumConfig config) throws IOException {
        long start = System.nanoTime();
        long entries = 0;
        long bytes = 0;
        if (!Files.isDirectory(storeDir)) {
            return new Stats(0, 0, (System.nanoTime() - start) / 1_000_000);
        }

        Path playerRoot = playerRootFor(worldRoot);
        // Export only: read the existing layout without rewriting it first.
        try (FolesiumDatabase db = FolesiumDatabase.open(storeDir,
                FolesiumConfig.defaults().withDurability(FolesiumConfig.DurabilityMode.EXPLICIT),
                FolesiumDatabase.StoreRole.PLAYERS, false)) {
            for (Mapping m : mappingsFor(worldRoot, playerRoot)) {
                Keyspace ks = db.keyspace(m.keyspace());
                Path out = playerRoot.resolve(m.dir());
                if (ks.count() == 0 && !Files.exists(out)) {
                    // Do not create empty vanilla roots on a brand-new world.
                    continue;
                }
                long[] inc = config.backupOnConvert()
                        ? convertMappingViaStaging(out, ks, m)
                        : convertMappingInPlace(out, ks, m);
                entries += inc[0];
                bytes += inc[1];
            }
        }
        return new Stats(entries, bytes, (System.nanoTime() - start) / 1_000_000);
    }

    /** Backup-mode path: clean staging tree + atomic swap, previous dir kept as backup. */
    private static long[] convertMappingViaStaging(Path out, Keyspace ks, Mapping m) throws IOException {
        Path staging = siblingPath(out, ".folesium-staging-");
        Files.createDirectories(staging);
        try {
            long[] inc = writeMapping(staging, ks, m);
            replaceDirectory(out, staging);
            return inc;
        } catch (IOException | RuntimeException ex) {
            deleteTreeQuietly(staging);
            throw ex;
        }
    }

    /** Default path: write each record straight into the target directory, replacing existing files. */
    private static long[] convertMappingInPlace(Path out, Keyspace ks, Mapping m) throws IOException {
        Files.createDirectories(out);
        return writeMapping(out, ks, m);
    }

    private static long[] writeMapping(Path writeRoot, Keyspace ks, Mapping m) throws IOException {
        long entries = 0;
        long bytes = 0;
        // Keys first: values are still read one at a time, while a clean
        // staging tree handles records deleted from the store.
        List<byte[]> keys = new ArrayList<>();
        ks.forEachKey(keys::add);
        for (byte[] key : keys) {
            if (key.length != UuidKeys.LENGTH) {
                continue; // not a player key; leave it out rather than guess
            }
            byte[] value = ks.get(key);
            if (value == null) {
                continue; // deleted between the key scan and the read
            }
            UUID id = UuidKeys.decode(key);
            writeAtomically(writeRoot.resolve(id + m.extension()), value);
            entries++;
            bytes += value.length;
        }
        return new long[]{entries, bytes};
    }

    /** The (pruned) {@code .folesium-backup-*} sibling name for a target path. */
    private static Path backupPath(Path destination) throws IOException {
        pruneOldBackups(destination);
        return siblingPath(destination, ".folesium-backup-");
    }

    private static Path siblingPath(Path destination, String marker) {
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            parent = absolute.getRoot();
        }
        return parent.resolve(destination.getFileName() + marker + UUID.randomUUID());
    }

    /** Writes and forces a sibling temporary file, then publishes it atomically. */
    private static void writeAtomically(Path destination, byte[] value) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Explicit portability fallback: staging-directory publication still
                // protects the existing vanilla tree from a partial write.
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replaceDirectory(Path destination, Path staging) throws IOException {
        Path parent = destination.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            parent = destination.toAbsolutePath().normalize().getRoot();
        }
        Files.createDirectories(parent);
        Path backup = siblingPath(destination, ".folesium-backup-");
        pruneOldBackups(destination);
        boolean backedUp = false;
        try {
            if (Files.exists(destination)) {
                movePath(destination, backup, false);
                backedUp = true;
            }
            movePath(staging, destination, true);
        } catch (IOException failure) {
            if (backedUp && Files.exists(destination)) {
                // The staging-to-destination move may have partially replaced the
                // destination, so clean it up before restoring the backup. When the
                // destination-to-backup move failed (backedUp == false), the
                // destination is still the intact original and must NOT be deleted.
                deleteTreeQuietly(destination);
            }
            if (backedUp && Files.exists(backup)) {
                try {
                    movePath(backup, destination, false);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * Repeated conversions would otherwise accumulate one {@code .folesium-backup-*}
     * tree per restored directory forever. Delete the backup trees left by previous
     * runs, keeping only the newest (the one a failed current run may still restore
     * from); the fresh backup dir is created by {@link #replaceDirectory} right
     * after this call. Mirrors the pruning in {@link WorldConverter#replaceDirectory}.
     */
    private static void pruneOldBackups(Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            parent = destination.toAbsolutePath().normalize().getRoot();
        }
        String prefix = destination.getFileName() + ".folesium-backup-";
        List<Path> backups = new ArrayList<>();
        try (var s = Files.list(parent)) {
            s.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(backups::add);
        }
        backups.sort(Comparator
                .comparingLong(PlayerDataConverter::lastModifiedMillis)
                .reversed()
                .thenComparing(Path::toString, Comparator.reverseOrder()));
        for (int i = 1; i < backups.size(); i++) {
            deleteTreeQuietly(backups.get(i));
        }
    }

    private static long lastModifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void movePath(Path source, Path target, boolean atomicPreferred) throws IOException {
        try {
            if (atomicPreferred) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(source, target);
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The previous destination remains available in its backup.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup of an uncommitted staging tree.
        }
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
