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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-level conversion driver: walks a Minecraft world root and converts the
 * world's player data plus every dimension (overworld / the_nether / the_end /
 * modded, ...) in a single call.
 *
 * <p>Used by both the {@code folesium-converter} CLI and the Folia server's
 * {@code --folesiumConvertToFolesium} / {@code --folesiumConvertToAnvil} startup
 * flags. The resulting on-disk layout mirrors cesium-fabric's, with the Folesium
 * stores living inside the save next to the vanilla data they mirror:</p>
 * <pre>
 * &lt;world&gt;/folesium/                        role=players    playerdata + advancements + stats
 * &lt;world&gt;/playerdata|advancements|stats/    vanilla files, kept as backup
 * &lt;world&gt;/dimensions/&lt;ns&gt;/&lt;path&gt;/folesium/  role=dimension  chunks + entities + poi
 * &lt;world&gt;/dimensions/&lt;ns&gt;/&lt;path&gt;/region|entities|poi/
 * </pre>
 *
 * <p>Because both stores are called {@code folesium/}, dimension discovery must not
 * infer a store's purpose from its path: it reads {@code store.role} from the store
 * metadata instead (see {@link FolesiumDatabase#readRole}). Without that, a world root
 * holding a player store would be mistaken for a dimension.</p>
 */
public final class WorldConversionService {

    /** Which direction the conversion goes. */
    public enum Direction { TO_FOLESIUM, TO_ANVIL }

    /** Aggregate report from {@link #convertWorld}. */
    public record Report(
            int dimensions,
            long totalChunks,
            long totalBytes,
            long playerRecords,
            long millis
    ) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "Converted %d dimensions, %d chunks (%.1f MiB raw NBT) and %d player records in %d ms",
                    dimensions, totalChunks, totalBytes / 1048576.0, playerRecords, millis);
        }
    }

    /** Single-dimension conversion: same logic the converter CLI uses per-dimension. */
    public record DimensionResult(Path dimensionDir, WorldConverter.Stats stats) {}

    private final int threads;

    public WorldConversionService(int threads) {
        this.threads = Math.max(1, threads);
    }

    public WorldConversionService() {
        this(Math.min(16, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Converts {@code worldRoot} -- its player data and all of its dimensions --
     * according to {@code dir}. For the Folesium direction, dimension stores are
     * opened/created at {@code <dimension>/folesium/} and the player store at
     * {@code <world>/folesium/}; for the Anvil direction the vanilla files are
     * re-created.
     *
     * <p>Like cesium-fabric's converter, <em>no files are ever deleted</em> in either
     * direction: the source data (vanilla files or Folesium stores) stays on disk as a
     * backup and a note is printed telling the operator what can now be removed
     * manually.</p>
     *
     * <p>Anything with no source data is silently skipped -- no Anvil {@code region/}
     * dir for TO_FOLESIUM, no store for TO_ANVIL -- which lets the same call work on a
     * brand-new world.</p>
     */
    public Report convertWorld(Path worldRoot, Direction dir, FolesiumConfig config) throws IOException {
        long t0 = System.nanoTime();
        // Stores moved aside by backupOnConvert during this run, so the retention note can
        // point the operator at their backup locations.
        List<Path> movedStores = new ArrayList<>();
        long players = convertPlayerData(worldRoot, dir, config, movedStores);

        WorldConverter converter = new WorldConverter(threads);
        long chunks = 0;
        long bytes = 0;
        int converted = 0;
        Set<Path> keptStores = new LinkedHashSet<>();
        if (dir == Direction.TO_ANVIL) {
            // Problem B: list the PLAYER store whenever it actually exists as a PLAYERS
            // store -- even when it holds zero records. Judging by the conversion record
            // count (players > 0) would hide an empty-but-real store from the log.
            Path playerStore = PlayerDataConverter.storeDirectoryFor(worldRoot);
            if (Files.isDirectory(playerStore)
                    && FolesiumDatabase.readRole(playerStore) == FolesiumDatabase.StoreRole.PLAYERS) {
                keptStores.add(playerStore);
            }
        }
        try {
            List<Path> dimensionDirs = discoverDimensions(worldRoot);
            for (Path dim : dimensionDirs) {
                Path folesiumStore = dim.resolve(FolesiumDatabase.STORE_DIR_NAME);
                boolean hasAnvil = hasAnvilData(dim);
                boolean hasFolesium = isDimensionStore(folesiumStore);
                WorldConverter.Stats stats;
                switch (dir) {
                    case TO_FOLESIUM -> {
                        if (!hasAnvil) continue;
                        // A pre-1.21 world root is both a dimension (it holds region/ etc.)
                        // and the host of the legacy player store: the dimension store the
                        // converter would open here is the very same directory as the player
                        // store, and opening it as a DIMENSION store would throw ("Refusing to
                        // mix player data and chunk data in one store"). Skip the root
                        // dimension and say why; its Anvil data stays on disk unconverted.
                        if (FolesiumDatabase.readRole(folesiumStore) == FolesiumDatabase.StoreRole.PLAYERS) {
                            System.out.println("Folesium: skipped dimension " + dim + ": " + folesiumStore
                                    + " already holds the player store (role=PLAYERS). On a pre-1.21 world");
                            System.out.println("Folesium: the root dimension cannot get its own store (path collision with");
                            System.out.println("Folesium: the player store), so its Anvil data (region/, entities/, poi/) is left unconverted.");
                            continue;
                        }
                        // Same collision while the reserved path is still free: on a pre-1.21
                        // world without player data, convertPlayerData above creates nothing, so
                        // the root's folesium/ dir is the reserved legacy player-store location
                        // (storeDirectoryFor == worldRoot/folesium) even though no store is
                        // there yet. Converting the root as a dimension would create a DIMENSION
                        // store on that reserved path and block the player store (role conflict)
                        // once player data appears. Skip the root dimension and say why; its
                        // Anvil data stays on disk unconverted. The skip applies only while the
                        // reserved path holds no DIMENSION store: a store already recorded as
                        // DIMENSION there means the root was converted as a dimension on purpose,
                        // and merging into it is allowed (the read-role check replaces an
                        // unconditional skip).
                        if (dim.equals(worldRoot)
                                && folesiumStore.equals(PlayerDataConverter.storeDirectoryFor(worldRoot))
                                && !isDimensionStore(folesiumStore)) {
                            System.out.println("Folesium: skipped dimension " + dim + ": " + folesiumStore
                                    + " is the reserved location of the legacy player store, so on a pre-1.21 world");
                            System.out.println("Folesium: the root dimension cannot get a store of its own (path collision with");
                            System.out.println("Folesium: the player store), even when no player data exists yet. Its Anvil data");
                            System.out.println("Folesium: (region/, entities/, poi/) is left unconverted; to convert the root");
                            System.out.println("Folesium: dimension, handle it separately (e.g. --folesiumWorldDir on a world");
                            System.out.println("Folesium: whose root is a pure dimension, not the legacy player-store host).");
                            continue;
                        }
                        stats = converter.anvilToFolesium(dim, folesiumStore, config, movedStores::add);
                    }
                    case TO_ANVIL -> {
                        if (!hasFolesium) continue;
                        stats = converter.folesiumToAnvil(folesiumStore, dim, config);
                        keptStores.add(folesiumStore);
                    }
                    default -> throw new IllegalStateException();
                }
                System.out.printf("  %-32s  %d chunks (%.1f MiB)%n",
                        worldRoot.relativize(dim).toString(),
                        stats.chunks(), stats.bytes() / 1048576.0);
                chunks += stats.chunks();
                bytes += stats.bytes();
                converted++;
            }
            printRetentionNote(worldRoot, dir, converted, players, keptStores, config.backupOnConvert());
        } finally {
            // A dimension failing mid-way must not leave the operator without the locations
            // of the stores backupOnConvert already moved aside and rebuilt during this run:
            // report them on both paths (the success path prints them right after the
            // retention note).
            printMovedStores(movedStores, config.backupOnConvert());
        }
        long millis = (System.nanoTime() - t0) / 1_000_000L;
        return new Report(converted, chunks, bytes, players, millis);
    }

    /**
     * cesium-fabric parity: the converter never deletes the <em>source</em> data, it
     * only tells the operator what is now redundant and safe to remove by hand.
     * With {@code backupOnConvert} it also keeps the previous target trees under
     * {@code .folesium-backup-*} names and says so (see {@link #printMovedStores}, which
     * runs in a {@code finally} and therefore also reports on a failed conversion).
     */
    private static void printRetentionNote(Path worldRoot, Direction dir,
                                           int dimensions, long players, Set<Path> keptStores,
                                           boolean backupOnConvert) {
        if (dir == Direction.TO_ANVIL) {
            printRetainedStores(keptStores, backupOnConvert);
        } else if (dimensions > 0 || players > 0) {
            System.out.println("Folesium: no files were deleted. The vanilla files (region/, entities/, poi/ and the");
            System.out.println("Folesium: per-player files) were kept as a backup; the server ignores them while Folesium");
            System.out.println("Folesium: is enabled. Delete them manually once the converted world is verified.");
        }
    }

    /**
     * Prints the {@code .folesium-backup-*} stores {@code backupOnConvert} moved aside
     * and rebuilt during this run. Invoked from a {@code finally} of
     * {@link #convertWorld}, so a dimension failing mid-way still reports the stores
     * that were already moved -- otherwise their backups would be silently pruned by
     * a later successful run and the operator could lose the pre-conversion copies
     * without ever knowing where they were kept.
     */
    private static void printMovedStores(List<Path> movedStores, boolean backupOnConvert) {
        if (!backupOnConvert || movedStores.isEmpty()) {
            return;
        }
        System.out.println("Folesium: the pre-existing stores were moved aside and kept as backups:");
        for (Path p : movedStores) {
            System.out.println("    " + p.toAbsolutePath().normalize());
        }
        System.out.println("Folesium: backups from earlier conversions are pruned, so they do not accumulate.");
    }

    /**
     * Prints the cesium-fabric-parity "nothing was deleted" note, one line per kept
     * Folesium source store. Every path is made absolute and normalized so the
     * operator can copy it verbatim even when the world root was given relatively.
     * Duplicate paths (e.g. a legacy world root that is both a dimension and the
     * player store) collapse into a single line via a {@link LinkedHashSet} so the
     * output order is stable and never misleading.
     */
    public static void printRetainedStores(Collection<? extends Path> stores, boolean backupOnConvert) {
        LinkedHashSet<Path> kept = new LinkedHashSet<>();
        for (Path p : stores) {
            if (p != null) {
                kept.add(p.toAbsolutePath().normalize());
            }
        }
        if (kept.isEmpty()) {
            return;
        }
        System.out.println("Folesium: no Folesium store files were deleted. The now-redundant Folesium stores were");
        System.out.println("Folesium: kept as a backup:");
        for (Path p : kept) {
            System.out.println("    " + p);
        }
        if (backupOnConvert) {
            System.out.println("Folesium: where the restored targets already existed, the replaced vanilla trees were");
            System.out.println("Folesium: kept as .folesium-backup-* siblings (e.g. <dir>.folesium-backup-<id>/); backup");
            System.out.println("Folesium: trees from earlier conversions are pruned, so backups do not accumulate.");
            System.out.println("Folesium: delete those backup trees manually once the restored world is verified - and");
            System.out.println("Folesium: always BEFORE converting back to Folesium if you played on Anvil meanwhile.");
        } else {
            System.out.println("Folesium: targets were overwritten in place (backupOnConvert=false), so no .folesium-backup-*");
            System.out.println("Folesium: trees were created by this run (older ones from earlier backup-mode runs, if any, stay");
            System.out.println("Folesium: untouched); the stores above are the only redundant data left on disk.");
        }
    }

    /**
     * Converts the world's {@code playerdata/}, {@code advancements/} and {@code stats/}
     * to or from the world-root player store. Returns the number of records moved.
     */
    private long convertPlayerData(Path worldRoot, Direction dir, FolesiumConfig config,
                                   List<Path> movedStores) throws IOException {
        Path store = PlayerDataConverter.storeDirectoryFor(worldRoot);
        PlayerDataConverter.Stats stats;
        switch (dir) {
            case TO_FOLESIUM -> {
                if (!PlayerDataConverter.hasVanillaPlayerData(worldRoot)) {
                    return 0;
                }
                // An existing store at the player-store location must be the player
                // store. A DIMENSION store there (misplaced, or a layout change) must
                // not be moved aside and rebuilt as a PLAYERS store -- backupOnConvert
                // would silently hide the dimension's data behind a backup name. Refuse
                // loudly and leave the vanilla files unconverted.
                if (FolesiumDatabase.readRole(store) == FolesiumDatabase.StoreRole.DIMENSION) {
                    System.out.println("Folesium: skipped player data conversion of " + worldRoot + ": " + store
                            + " already holds a DIMENSION store, not the player store.");
                    System.out.println("Folesium: move or convert that store first, then re-run; the vanilla player");
                    System.out.println("Folesium: files (playerdata/advancements/stats) are left unconverted.");
                    return 0;
                }
                stats = PlayerDataConverter.anvilToFolesium(worldRoot, store, config, movedStores::add);
            }
            case TO_ANVIL -> {
                if (FolesiumDatabase.readRole(store) != FolesiumDatabase.StoreRole.PLAYERS) {
                    return 0;
                }
                stats = PlayerDataConverter.folesiumToAnvil(store, worldRoot, config);
            }
            default -> throw new IllegalStateException();
        }
        if (stats.entries() > 0) {
            System.out.printf("  %-32s  %d player records (%.1f KiB)%n",
                    "(player data)", stats.entries(), stats.bytes() / 1024.0);
        }
        return stats.entries();
    }

    /**
     * Returns the directory of every dimension in {@code worldRoot}. Folia 26.x uses
     * {@code dimensions/<namespace>/<path>}; the world root itself is included iff it is
     * a pre-1.21.x single-dimension layout (it has an Anvil {@code region/},
     * {@code entities/} or {@code poi/} directory, or an already-converted dimension
     * store).
     *
     * <p>A directory counts as a dimension when it contains an Anvil data directory
     * ({@code region/}, {@code entities/} or {@code poi/}), or a {@code folesium/} store
     * whose recorded role is
     * {@link FolesiumDatabase.StoreRole#DIMENSION}. Checking the role -- not merely the
     * directory name -- is what keeps a world root holding a <em>player</em> store from
     * being converted as if it were a dimension.</p>
     *
     * <p>The scan is recursive and stops descending once a dimension directory is
     * found, so arbitrarily nested modded dimension layouts
     * ({@code dimensions/<ns>/<path>/<path>/...}) are discovered correctly. A world
     * root that is itself a dimension is still walked: only its well-known data
     * directories ({@code region/}, {@code entities/}, {@code poi/},
     * {@code folesium/}, {@code players/}) are pruned by name, so pre-1.21 sibling
     * dimensions ({@code DIM1/}, {@code DIM-1/}) and a {@code dimensions/} sub-tree
     * are discovered too. Basenames are otherwise not classified: names such as
     * {@code data} are legal dimension components.</p>
     */
    static List<Path> discoverDimensions(Path worldRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        if (isDimensionDirectory(worldRoot)) {
            out.add(worldRoot);
            seen.add(worldRoot);
        }
        Files.walkFileTree(worldRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(worldRoot)) {
                    // A root dimension was already recorded above, but its children must
                    // still be visited: a pre-1.21 world root (region/ etc.) also has
                    // sibling dimensions (DIM1/, DIM-1/) and possibly a dimensions/
                    // sub-tree. Only the root's own data directories are pruned, by
                    // name, in the generic branch below.
                    return FileVisitResult.CONTINUE;
                }
                if (seen.contains(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (isDimensionDirectory(dir)) {
                    out.add(dir);
                    seen.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // Prune the world root's own data directories (region/ etc. and the
                // folesium/ player store) by name so the walk does not descend into
                // them; anything deeper keeps the generic discovery, because a
                // dimension may legitimately be nested under such a name.
                if (dir.getParent().equals(worldRoot) && isWellKnownDataDir(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /**
     * The well-known data directories of a dimension ({@code region/},
     * {@code entities/}, {@code poi/}), the Folesium store directories
     * ({@code folesium/}) and the 26.x per-player container ({@code players/}) are
     * never candidate dimensions. They are pruned by name only when they are direct
     * children of the world root (a root dimension's own data, the legacy root player
     * store, or the 26.x player tree -- whose {@code players/folesium} store is inside
     * the container, so pruning {@code players/} keeps the walk out of it too); a
     * directory that <em>is</em> a dimension is matched by
     * {@link #isDimensionDirectory} before this check, so a dimension legitimately
     * named {@code region} or similar is never hidden by it.
     */
    private static boolean isWellKnownDataDir(Path dir) {
        String name = dir.getFileName().toString();
        return name.equals("region") || name.equals("entities") || name.equals("poi")
                || name.equalsIgnoreCase(PlayerDataConverter.DIR_PLAYERS_26)
                || name.equals(FolesiumDatabase.STORE_DIR_NAME);
    }

    private static boolean hasAnvilData(Path dir) {
        return Files.isDirectory(dir.resolve("region"))
                || Files.isDirectory(dir.resolve("entities"))
                || Files.isDirectory(dir.resolve("poi"));
    }

    private static boolean isDimensionDirectory(Path dir) {
        return hasAnvilData(dir)
                || isDimensionStore(dir.resolve(FolesiumDatabase.STORE_DIR_NAME));
    }

    /** True only for a {@code folesium/} directory whose metadata says it holds chunk data. */
    private static boolean isDimensionStore(Path storeDir) {
        return FolesiumDatabase.readRole(storeDir) == FolesiumDatabase.StoreRole.DIMENSION;
    }
}
