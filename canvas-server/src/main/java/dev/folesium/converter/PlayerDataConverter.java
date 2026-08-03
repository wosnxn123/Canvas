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

import dev.folesium.anvil.AnvilRegionFile;
import dev.folesium.core.FolesiumConfig;
import dev.folesium.core.FolesiumDatabase;
import dev.folesium.core.Keyspace;
import dev.folesium.core.util.UuidKeys;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /**
     * Upper bound for a single player file imported from vanilla. Vanilla player data is
     * tiny (tens of KiB at most), so 32 MiB -- the same bound the dimension side applies
     * to a chunk payload ({@link AnvilRegionFile#MAX_CHUNK_PAYLOAD_BYTES}) -- is far above
     * any legitimate file while stopping a corrupt or hostile file from OOMing the
     * converter when it is read into memory whole.
     */
    private static final long MAX_PLAYER_FILE_BYTES = AnvilRegionFile.MAX_CHUNK_PAYLOAD_BYTES;

    /**
     * Vanilla dir -> (Folesium keyspace, file extension, location). {@code base}
     * decides which root the directory resolves against: a LEGACY mapping's files sit
     * directly under the world root, a MODERN (26.x) mapping's under {@code players/}.
     * Resolving both against the same root silently skips an entire tree -- on a 26.x
     * world {@code <world>/players/playerdata} never exists while the legacy files are
     * at {@code <world>/playerdata}.
     */
    private record Mapping(String dir, String keyspace, String extension, Base base) {

        /** The vanilla directory holding these files, given the world's roots. */
        Path resolve(Path worldRoot, Path playerRoot) {
            return (base == Base.LEGACY ? worldRoot : playerRoot).resolve(dir);
        }
    }

    /** Where a mapping's vanilla files live: world-root level (pre-26.x) or under {@code players/} (26.x). */
    private enum Base { MODERN, LEGACY }

    private static final List<Mapping> LEGACY_MAPPINGS = List.of(
            new Mapping(DIR_PLAYERDATA, FolesiumDatabase.KS_PLAYERDATA, ".dat", Base.LEGACY),
            new Mapping(DIR_ADVANCEMENTS, FolesiumDatabase.KS_ADVANCEMENTS, ".json", Base.LEGACY),
            new Mapping(DIR_STATS, FolesiumDatabase.KS_STATS, ".json", Base.LEGACY)
    );

    private static final List<Mapping> MODERN_MAPPINGS = List.of(
            new Mapping(DIR_DATA_26, FolesiumDatabase.KS_PLAYERDATA, ".dat", Base.MODERN),
            new Mapping(DIR_ADVANCEMENTS, FolesiumDatabase.KS_ADVANCEMENTS, ".json", Base.MODERN),
            new Mapping(DIR_STATS, FolesiumDatabase.KS_STATS, ".json", Base.MODERN)
    );

    /**
     * The directory that holds the per-player vanilla directories <em>and</em> the player
     * store: {@code <world>/players} on a 26.x world, the world root itself on the older
     * layout. This mirrors the server hook, which anchors the store next to the directory
     * {@code LevelResource.PLAYER_DATA_DIR} resolves to. The {@code players/} container is
     * matched case-insensitively (like {@link PlayerPathRecognizer}), so a container
     * created as {@code PLAYERS/} on a case-sensitive file system is still the 26.x
     * container.
     *
     * <p>A 26.x world whose {@code players/} directory holds no per-player files at all
     * (all three 26.x data directories empty) while the pre-26 root-level directories
     * still contain data is treated as the legacy layout it actually is, so its data is
     * not silently left behind by {@link #mappingsFor}. The one exception is a
     * {@code players/} directory that already holds a {@code PLAYERS} store: the store
     * location is the layout authority (the server anchors its store at exactly this
     * spot), so such a world is a genuine 26.x+Folesium world and is never downgraded,
     * even when its 26.x per-player directories happen to be empty.</p>
     *
     * <p>The downgrade itself is reported <em>once per conversion run</em> by
     * {@link WorldConversionService#convertWorld} (which checks {@link #isLegacyDowngrade});
     * this method only makes the decision, so direct callers stay silent.</p>
     */
    public static Path playerRootFor(Path worldRoot) {
        Path players = playersContainer(worldRoot);
        if (legacyDowngrade(players, worldRoot)) {
            return worldRoot;
        }
        return players != null ? players : worldRoot;
    }

    /**
     * True when {@link #playerRootFor} downgrades {@code worldRoot} to the legacy
     * root-level layout (an empty 26.x {@code players/} shell while the pre-26
     * root-level directories hold data). The world conversion driver calls this once
     * per run to report the decision, instead of letting every {@link #playerRootFor}
     * call inside the conversion print it (that fired 3-4 times per run: the
     * vanilla-player probe, the import/export itself, and the root-dimension collision
     * check).
     */
    public static boolean isLegacyDowngrade(Path worldRoot) {
        return legacyDowngrade(playersContainer(worldRoot), worldRoot);
    }

    /** True when {@code players} is an empty 26.x shell while the legacy root tree holds data. */
    private static boolean legacyDowngrade(Path players, Path worldRoot) {
        return players != null && !hasModernStore(players)
                && modernTreeIsEmpty(players) && legacyTreeHasData(worldRoot);
    }

    /**
     * The {@code players/} container under {@code worldRoot}, or {@code null} when there is
     * none. The name is matched case-insensitively, mirroring
     * {@link PlayerPathRecognizer#DIR_PLAYERS}: on a case-sensitive file system a container
     * created as {@code PLAYERS/} must still be recognised, so the layout decision and the
     * store location stay symmetric with the recognizer.
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
            // The layout decision in playerRootFor is made on incomplete data when the world
            // root cannot be listed; say so instead of degrading silently.
            System.err.println("Folesium: cannot list " + worldRoot + " (" + e + ");"
                    + " treating it as having no players/ container");
            return null;
        }
    }

    /**
     * True when the {@code players/} container already holds a {@code PLAYERS} store. The
     * store location is the layout authority signal -- the server anchors its store at
     * {@code <world>/players/folesium} on the 26.x layout -- so a world with such a store
     * is a genuine 26.x+Folesium world whose empty-shell downgrade must be skipped.
     */
    private static boolean hasModernStore(Path players) {
        return FolesiumDatabase.readRole(players.resolve(FolesiumDatabase.STORE_DIR_NAME))
                == FolesiumDatabase.StoreRole.PLAYERS;
    }

    /**
     * The mapping set follows the world's layout, not the store's location: a world
     * with a {@code players/} directory uses the 26.x directories even when an
     * existing PLAYERS store sits at the legacy world-root location (see
     * {@link #storeDirectoryFor}). The vanilla root is therefore always derived
     * from the world layout via {@link #playerRootFor}; the store location is used
     * for the store itself only. The one exception is the empty-26.x-shell case
     * handled by {@link #playerRootFor}, which downgrades such a world to the
     * legacy directories it actually holds its data in.
     *
     * <p>When a 26.x world additionally still holds a legacy root-level tree with
     * player files, both trees are imported -- the modern one first, so
     * {@code putIfAbsent} lets the 26.x files win on conflicts -- and a warning is
     * logged by {@link #anvilToFolesium}; neither tree is silently left behind.</p>
     */
    private static List<Mapping> mappingsFor(Path worldRoot, Path playerRoot) {
        if (playerRoot.equals(worldRoot)) {
            return LEGACY_MAPPINGS;
        }
        if (legacyTreeHasData(worldRoot)) {
            // Both the 26.x players/ tree and the legacy root-level tree hold player
            // files: scan both, modern first (putIfAbsent keeps the 26.x bytes).
            List<Mapping> merged = new ArrayList<>(MODERN_MAPPINGS.size() + LEGACY_MAPPINGS.size());
            merged.addAll(MODERN_MAPPINGS);
            merged.addAll(LEGACY_MAPPINGS);
            return merged;
        }
        return MODERN_MAPPINGS;
    }

    /** True when none of the 26.x per-player directories under {@code players/} holds a player file. */
    private static boolean modernTreeIsEmpty(Path playersRoot) {
        return MODERN_MAPPINGS.stream().noneMatch(m -> hasPlayerFiles(playersRoot.resolve(m.dir()), m.extension()));
    }

    /** True when at least one legacy root-level per-player directory holds a player file. */
    private static boolean legacyTreeHasData(Path worldRoot) {
        return LEGACY_MAPPINGS.stream().anyMatch(m -> hasPlayerFiles(worldRoot.resolve(m.dir()), m.extension()));
    }

    /**
     * True when the directory exists and contains at least one player file whose extension
     * matches the mapping ({@code <uuid>.dat} for a playerdata mapping, {@code <uuid>.json}
     * for an advancements/stats mapping). A directory holding only files of the other
     * mapping's extension is not player data for this directory.
     */
    private static boolean hasPlayerFiles(Path dir, String extension) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var s = Files.list(dir)) {
            return s.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(extension)
                    && UUID_FILE.matcher(p.getFileName().toString()).matches());
        } catch (IOException e) {
            // The layout decision in playerRootFor is made on incomplete data when a
            // directory cannot be listed; say so instead of degrading silently.
            System.err.println("Folesium: cannot list " + dir + " (" + e + ");"
                    + " treating it as having no player files");
            return false;
        }
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
     * A directory alone is not data: an empty {@code playerdata/} (or the 26.x
     * equivalent) must not trigger a rebuild of an existing empty store.
     */
    public static boolean hasVanillaPlayerData(Path worldRoot) {
        Path playerRoot = playerRootFor(worldRoot);
        for (Mapping m : mappingsFor(worldRoot, playerRoot)) {
            if (hasPlayerFiles(m.resolve(worldRoot, playerRoot), m.extension())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The player store directory for a world root: {@code <world>/players/folesium} on
     * the 26.x layout, {@code <world>/folesium} on the older one. An existing store with
     * {@code store.role=PLAYERS} at either location wins, so a world converted under one
     * layout keeps using its store even if the directory shape changes around it. The
     * {@code players/} container is matched case-insensitively, mirroring
     * {@link PlayerPathRecognizer} and {@link #playerRootFor}.
     */
    public static Path storeDirectoryFor(Path worldRoot) {
        Path players = playersContainer(worldRoot);
        Path modern = players == null ? null : players.resolve(FolesiumDatabase.STORE_DIR_NAME);
        Path legacy = worldRoot.resolve(FolesiumDatabase.STORE_DIR_NAME);
        if (modern != null && FolesiumDatabase.readRole(modern) == FolesiumDatabase.StoreRole.PLAYERS) {
            return modern;
        }
        if (FolesiumDatabase.readRole(legacy) == FolesiumDatabase.StoreRole.PLAYERS) {
            return legacy;
        }
        // No store anywhere yet: follow the layout decision, which downgrades an empty
        // players/ shell to the legacy world-root location when the legacy tree holds data.
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
     * the store. If the rebuild fails, the backup is moved back to its original location,
     * so a failed backup-mode conversion leaves the previous store in place instead of a
     * half-written store.</p>
     */
    public static Stats anvilToFolesium(Path worldRoot, Path storeDir, FolesiumConfig config) throws IOException {
        return anvilToFolesium(worldRoot, storeDir, config, null);
    }

    /**
     * Same as {@link #anvilToFolesium(Path, Path, FolesiumConfig)}, but reports the backup
     * sibling created for a pre-existing store (only when {@code backupOnConvert} is set)
     * through {@code backupSink}, once the conversion has succeeded. The sink is never
     * invoked when the conversion fails, because the backup is then moved back to its
     * original location and the failed run leaves the canonical store untouched.
     *
     * @param backupSink receives the {@code .folesium-backup-*} sibling of {@code storeDir}
     *                   after a successful backup-mode rebuild; {@code null} to ignore
     */
    public static Stats anvilToFolesium(Path worldRoot, Path storeDir, FolesiumConfig config,
                                        Consumer<Path> backupSink) throws IOException {
        long start = System.nanoTime();
        long entries = 0;
        long bytes = 0;

        // With backupOnConvert the existing store is moved aside first; the whole move +
        // rebuild runs inside the try below, so a failed conversion restores the backup
        // instead of leaving the canonical path missing or holding a half-written store.
        // Only once the move succeeded (backedUp) may the rollback touch the canonical
        // path: when the initial move itself failed, the original store is still in
        // place and must not be deleted.
        Path backup = null;
        boolean backedUp = false;
        boolean backupOnConvert = config.backupOnConvert();
        if (backupOnConvert && Files.isDirectory(storeDir)) {
            backup = backupPath(storeDir);
        }
        try {
            if (backup != null) {
                movePath(storeDir, backup, false);
                backedUp = true;
            }

            Path playerRoot = playerRootFor(worldRoot);
            List<Mapping> mappings = mappingsFor(worldRoot, playerRoot);
            if (mappings.size() > MODERN_MAPPINGS.size()) {
                // Both the 26.x players/ tree and the legacy root-level tree hold player
                // files; import both (26.x first, so putIfAbsent keeps the 26.x bytes on
                // conflicts) instead of silently leaving one tree behind.
                System.err.println("Folesium: found both modern and legacy player trees; merging");
            }
            try (FolesiumDatabase db = FolesiumDatabase.open(storeDir,
                    config.withDurability(FolesiumConfig.DurabilityMode.EXPLICIT),
                    FolesiumDatabase.StoreRole.PLAYERS)) {
                for (Mapping m : mappings) {
                    // Legacy mappings resolve against the world root, not playerRoot:
                    // on a 26.x world the legacy tree is <world>/playerdata etc., never
                    // <world>/players/playerdata.
                    Path src = m.resolve(worldRoot, playerRoot);
                    if (!Files.isDirectory(src)) {
                        continue;
                    }
                    Keyspace ks = db.keyspace(m.keyspace());
                    for (Path file : listPlayerFiles(src, m.extension())) {
                        UUID id = uuidOf(file);
                        if (id == null) {
                            continue;
                        }
                        // Pre-check the size before reading the file into memory: a corrupt
                        // or hostile player file must not OOM the converter. Mirrors the
                        // dimension side's chunk payload bound
                        // (AnvilRegionFile.MAX_CHUNK_PAYLOAD_BYTES); anything larger is
                        // skipped with a warning instead of being imported.
                        byte[] payload;
                        try {
                            long size = Files.size(file);
                            if (size > MAX_PLAYER_FILE_BYTES) {
                                System.err.println("Folesium: skipping player file " + file.getFileName()
                                        + ": " + size + " bytes exceeds the " + MAX_PLAYER_FILE_BYTES
                                        + " byte limit");
                                continue;
                            }
                            // The bound is enforced again on the bytes actually read (a
                            // limit + 1 probe) instead of being trusted from the size()
                            // check above: a concurrent grow between the two would
                            // otherwise smuggle an over-limit file into memory whole
                            // (TOCTOU).
                            payload = readPlayerFileBounded(file, size);
                        } catch (NoSuchFileException e) {
                            // A file that vanished between listing and read (concurrent
                            // cleanup, server pruning) is skipped per file - except in
                            // backup mode, where the vanished source means the old store
                            // (moved aside) may hold the only remaining copy and the
                            // rebuild would silently lack this player; abort so the
                            // rollback restores the old store (same rule as the
                            // unreadable-file branch and the dimension side).
                            if (backupOnConvert) {
                                throw new IOException("Failed importing " + file
                                        + ": it disappeared while importing", e);
                            }
                            System.err.println("Folesium: skipping player file " + file.getFileName()
                                    + ": it disappeared while importing");
                            continue;
                        } catch (IOException e) {
                            // An unreadable player file (permissions, torn write): in
                            // backup mode the rebuild would silently lack this player
                            // while the old store - possibly holding the player's only
                            // good copy - sits under .folesium-backup-* until the next
                            // backup-mode conversion prunes it. Abort so the rollback
                            // restores the old store (same rule as the dimension side).
                            // Otherwise skip per file like a vanished one: the source
                            // tree keeps the data, so a re-run after a repair re-imports
                            // it. Aborting the whole world adoption over one unreadable
                            // player file would be the same asymmetry the dimension side
                            // removed.
                            if (backupOnConvert) {
                                throw new IOException("Failed importing " + file, e);
                            }
                            System.err.println("Folesium: skipping player file " + file.getFileName()
                                    + ": cannot read it (" + e + ")");
                            continue;
                        }
                        if (payload == null) {
                            // Reached only when the file grew past the bound after the
                            // size() pre-check above.
                            System.err.println("Folesium: skipping player file " + file.getFileName()
                                    + ": it grew past the " + MAX_PLAYER_FILE_BYTES
                                    + " byte limit while reading");
                            continue;
                        }
                        if (ks.putIfAbsent(id, payload)) {
                            entries++;
                            bytes += payload.length;
                        }
                    }
                }
                db.flush();
            }
        } catch (IOException | RuntimeException ex) {
            if (backedUp) {
                rollbackFailedBackup(storeDir, backup, ex);
            }
            throw ex;
        }
        if (backup != null && backupSink != null) {
            backupSink.accept(backup);
        }
        return new Stats(entries, bytes, (System.nanoTime() - start) / 1_000_000);
    }

    // ------------------------------------------------------- folesium -> vanilla

    /**
     * Materializes every player keyspace back into the vanilla per-player files.
     *
     * <p>Default: each record is written straight into the target directory,
     * atomically replacing any existing file of the same name. A player file the store
     * holds NO record for at all is KEPT with a warning (never deleted): the import may
     * have skipped it as unreadable, and destroying the only remaining copy would
     * break the documented re-run-after-repair contract (a player actually deleted on
     * the server may be resurrected by a later export - the accepted cost, same trade
     * as the dimension-side prune). Foreign files that are not player data are left
     * untouched.</p>
     *
     * <p>With {@code backupOnConvert} a clean staging directory is built first and
     * swapped in, and the previous directory is moved to a unique
     * {@code .folesium-backup-*} sibling, so stale UUID files and empty-keyspace
     * remnants cannot survive the rollback.</p>
     *
     * <p>The export follows the world's current layout, mirroring the import side: a
     * world with a {@code players/} directory gets the 26.x tree
     * ({@code players/data}, {@code players/advancements}, {@code players/stats}); a
     * world without one gets the root-level legacy directories ({@code playerdata/},
     * {@code advancements/}, {@code stats/}), which pre-26 servers read. The
     * {@code players/} container is never fabricated: creating it on a legacy world
     * would hide the rolled-back players from a pre-26 server.</p>
     */
    public static Stats folesiumToAnvil(Path storeDir, Path worldRoot, FolesiumConfig config) throws IOException {
        return folesiumToAnvil(storeDir, worldRoot, config, null);
    }

    /**
     * Same as {@link #folesiumToAnvil(Path, Path, FolesiumConfig)}, but reports each
     * {@code .folesium-backup-*} sibling actually created by a backup-mode swap through
     * {@code backupSink}. The sink fires <em>eagerly</em>, right after each mapping's swap
     * succeeds -- not only once the whole export has succeeded: the player directories are
     * swapped one at a time, so when a later mapping fails the world is left in a mixed
     * exported/untouched state and the backups already swapped (and already reported) are
     * exactly the trees the operator must restore. The sink is invoked only when a
     * pre-existing vanilla directory was moved aside (an empty store replaces nothing),
     * so callers can tell the operator exactly which vanilla trees were kept.
     *
     * @param backupSink receives the {@code .folesium-backup-*} sibling of each replaced
     *                   vanilla directory right after its swap succeeds;
     *                   {@code null} to ignore
     */
    public static Stats folesiumToAnvil(Path storeDir, Path worldRoot, FolesiumConfig config,
                                        Consumer<Path> backupSink) throws IOException {
        long start = System.nanoTime();
        long entries = 0;
        long bytes = 0;
        if (!Files.isDirectory(storeDir)) {
            return new Stats(0, 0, (System.nanoTime() - start) / 1_000_000);
        }

        // Export only: read the existing layout without rewriting it first. The target
        // follows the world's layout, mirroring playerRootFor (and therefore the import
        // side): a world with a players/ directory gets the 26.x tree
        // (players/data, players/advancements, players/stats); a world without one gets
        // the root-level legacy directories (playerdata/, advancements/, stats/) that
        // pre-26 servers read. A players/ directory that is an empty shell while the
        // root-level legacy tree holds data is treated as the legacy layout, so the
        // export lands where the world actually reads it. The players/ container is
        // deliberately NOT created here -- fabricating it on a legacy world would hide
        // the rolled-back players from a pre-26 server.
        Path exportRoot = playerRootFor(worldRoot);
        boolean modern = !exportRoot.equals(worldRoot);
        List<Mapping> mappings = modern ? MODERN_MAPPINGS : LEGACY_MAPPINGS;
        // Sweep writeAtomically crash leftovers ({@code <uuid>.<ext>.tmp-<uuid>} sibling
        // files) from the target directories before any new write, mirroring the
        // staging sweep: an interrupted export leaves them behind, they match neither
        // the UUID filename pattern nor any other consumer, and they would accumulate
        // in the player directories forever otherwise.
        for (Mapping m : mappings) {
            cleanTmpLeftovers(exportRoot.resolve(m.dir()));
        }
        try (FolesiumDatabase db = FolesiumDatabase.open(storeDir,
                FolesiumConfig.defaults().withDurability(FolesiumConfig.DurabilityMode.EXPLICIT),
                FolesiumDatabase.StoreRole.PLAYERS, false)) {
            for (Mapping m : mappings) {
                Keyspace ks = db.keyspace(m.keyspace());
                Path out = exportRoot.resolve(m.dir());
                if (ks.count() == 0 && !Files.exists(out)) {
                    // Do not create empty vanilla roots on a brand-new world.
                    continue;
                }
                long[] inc = config.backupOnConvert()
                        ? convertMappingViaStaging(out, ks, m, backupSink)
                        : convertMappingInPlace(out, ks, m);
                entries += inc[0];
                bytes += inc[1];
            }
        }
        return new Stats(entries, bytes, (System.nanoTime() - start) / 1_000_000);
    }

    /** Backup-mode path: clean staging tree + atomic swap, previous dir kept as backup. */
    private static long[] convertMappingViaStaging(Path out, Keyspace ks, Mapping m, Consumer<Path> backupSink)
            throws IOException {
        // This path only runs under backupOnConvert, so crash leftovers of an earlier
        // backup-mode run (a crash between staging and swap) must be collected before a
        // new staging tree is created - otherwise they accumulate next to the restored
        // trees instead of being swept by a later in-place run. Mirrors
        // WorldConverter#convertKeyspaceViaStaging.
        cleanStagingSiblings(out);
        Path staging = siblingPath(out, ".folesium-staging-");
        Files.createDirectories(staging);
        try {
            long[] inc = writeMapping(staging, ks, m);
            Path backup = replaceDirectory(out, staging);
            if (backup != null && backupSink != null) {
                backupSink.accept(backup);
            }
            return inc;
        } catch (IOException | RuntimeException ex) {
            deleteTreeQuietly(staging);
            throw ex;
        }
    }

    /**
     * Default path: write each record straight into the target directory, replacing existing
     * files, then sweep the files the store no longer holds. A file the store holds NO record
     * for is kept with a warning (never deleted) - see {@link #prunePlayerFilesMissingFromStore}.
     */
    private static long[] convertMappingInPlace(Path out, Keyspace ks, Mapping m) throws IOException {
        Files.createDirectories(out);
        long[] inc = writeMapping(out, ks, m);
        prunePlayerFilesMissingFromStore(out, ks, m.extension());
        cleanStagingSiblings(out);
        return inc;
    }

    /**
     * Best-effort removal of {@code .tmp-*} files in {@code out} left behind by an
     * interrupted {@link #writeAtomically} (a crash between writing the temporary
     * file and publishing it). Only regular files whose name contains the
     * {@code .tmp-} marker are touched, so real player files are never deleted;
     * failures are ignored (the leftovers are inert). Mirrors
     * {@link #cleanStagingSiblings}.
     */
    private static void cleanTmpLeftovers(Path out) {
        try (Stream<Path> files = Files.list(out)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().contains(".tmp-"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort only
                        }
                    });
        } catch (IOException ignored) {
            // best-effort only; a missing directory means nothing to sweep
        }
    }

    /**
     * Best-effort removal of {@code .folesium-staging-*} siblings of {@code out} left
     * behind by an interrupted backup-mode conversion (a crash between creating the
     * staging tree and swapping it in). Only directories whose name starts with
     * {@code <out-name>.folesium-staging-} are touched, so unrelated data is never
     * deleted; {@code .folesium-backup-*} trees are deliberately left alone (their
     * lifecycle belongs to the backup-mode {@link #pruneOldBackups}, and a default-mode
     * run must not break the backup chain), and failures are ignored (the leftovers are
     * inert).
     */
    private static void cleanStagingSiblings(Path out) {
        Path parent = out.getParent();
        if (parent == null) {
            return;
        }
        String prefix = out.getFileName().toString() + ".folesium-staging-";
        try (Stream<Path> files = Files.list(parent)) {
            files.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(PlayerDataConverter::deleteTreeQuietly);
        } catch (IOException ignored) {
            // best-effort only
        }
    }

    /**
     * In-place exports keep every record the store still has and warn about (never delete)
     * per-player files the store dropped: sweeping a file the store holds NO record for at
     * all could destroy the only surviving copy of a player the import skipped as
     * unreadable, so such files are kept with a warning and the operator deletes them by
     * hand (a player actually deleted on the server may be resurrected by a later export -
     * the accepted cost). Every player file present in the target tree is compared against
     * the full set of player keys the store still holds; files are only touched when they
     * match the mapping's extension and the UUID filename pattern, so foreign files stay
     * untouched.
     */
    private static void prunePlayerFilesMissingFromStore(Path out, Keyspace ks, String extension) throws IOException {
        // Keys first: the sweep needs the full stored set (a clean staging tree would handle
        // deleted records; the in-place path has to compare against the store explicitly).
        List<byte[]> keys = new ArrayList<>();
        ks.forEachKey(keys::add);
        Set<UUID> stored = new HashSet<>();
        for (byte[] key : keys) {
            if (key.length == UuidKeys.LENGTH) {
                stored.add(UuidKeys.decode(key));
            }
        }
        List<Path> files;
        try (var s = Files.list(out)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .filter(p -> UUID_FILE.matcher(p.getFileName().toString()).matches())
                    .toList();
        }
        for (Path file : files) {
            UUID id = uuidOf(file);
            if (id != null && !stored.contains(id)) {
                // The store holds NO record for this player at all. Sweeping the file
                // would destroy the data of a player the import skipped as unreadable
                // (the import promises 'the source keeps the data, re-run after a
                // repair'), or of a foreign file that never entered the store - the
                // same hazard the dimension-side prune removed. Keep it and warn; the
                // operator can delete it manually. (A player actually deleted on the
                // server may be resurrected by a later export - the accepted cost of
                // never destroying the only remaining copy.)
                System.err.println("Folesium: keeping " + file.getFileName()
                        + ": the store holds no record for it (skipped as unreadable or never"
                        + " imported); delete the file manually to drop it");
            }
        }
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

    /**
     * Replaces a destination directory while retaining the previous tree as a backup.
     *
     * @return the {@code .folesium-backup-*} sibling the previous destination was moved
     *         to, or {@code null} when the destination did not exist (nothing replaced)
     */
    private static Path replaceDirectory(Path destination, Path staging) throws IOException {
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
        return backedUp ? backup : null;
    }

    /**
     * Restores the previous store after a failed backup-mode conversion, mirroring the
     * rollback of {@link #replaceDirectory}: the half-written new store at the canonical
     * path is removed and the backup is moved back into place. The original failure is
     * rethrown by the caller; a restore failure is attached to it as suppressed, with the
     * backup path in the message so the operator can find the retained data. When the
     * initial backup move itself failed, the caller never reaches this method: the backup
     * does not exist and the canonical path still holds the intact original.
     */
    private static void rollbackFailedBackup(Path destination, Path backup, Exception failure) {
        if (Files.exists(destination)) {
            // The new store may be only partially written; it must not survive next to the
            // restored original.
            deleteTreeQuietly(destination);
        }
        if (Files.exists(backup)) {
            try {
                movePath(backup, destination, false);
            } catch (IOException restoreFailure) {
                failure.addSuppressed(new IOException(
                        "Failed restoring the previous player store from " + backup
                                + " after a failed conversion; the backup is still there",
                        restoreFailure));
            }
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

    /**
     * Reads a player file under the {@link #MAX_PLAYER_FILE_BYTES} bound with a bounded
     * {@link FileChannel} loop: read up to the bound in bounded chunks, then probe one
     * further byte so an over-limit file is rejected by its cumulative count (limit + 1)
     * instead of by a size() check that could race a concurrent grow (TOCTOU). The
     * caller's size pre-check is only a fast path; the bound is enforced on the bytes
     * actually read, so a file that grows between the two reads is still caught.
     *
     * @param sizeHint the file size observed by the caller's pre-check, used only as the
     *                 initial buffer capacity
     * @return the file bytes, or {@code null} when the file exceeds the bound
     */
    private static byte[] readPlayerFileBounded(Path file, long sizeHint) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream(
                (int) Math.min(Math.max(sizeHint, 0L), MAX_PLAYER_FILE_BYTES));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer tmp = ByteBuffer.allocate(8192);
            long total = 0;
            while (total < MAX_PLAYER_FILE_BYTES) {
                tmp.clear();
                tmp.limit((int) Math.min(tmp.capacity(), MAX_PLAYER_FILE_BYTES - total));
                int n = channel.read(tmp);
                if (n < 0) {
                    break;
                }
                total += n;
                collected.write(tmp.array(), 0, n);
            }
            if (total == MAX_PLAYER_FILE_BYTES) {
                tmp.clear();
                tmp.limit(1);
                if (channel.read(tmp) > 0) {
                    total++;
                }
            }
            if (total > MAX_PLAYER_FILE_BYTES) {
                return null;
            }
        }
        return collected.toByteArray();
    }

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
