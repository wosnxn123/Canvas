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
        long players = convertPlayerData(worldRoot, dir, config);

        List<Path> dimensionDirs = discoverDimensions(worldRoot);
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
        for (Path dim : dimensionDirs) {
            Path folesiumStore = dim.resolve(FolesiumDatabase.STORE_DIR_NAME);
            boolean hasAnvil = Files.isDirectory(dim.resolve("region"));
            boolean hasFolesium = isDimensionStore(folesiumStore);
            WorldConverter.Stats stats;
            switch (dir) {
                case TO_FOLESIUM -> {
                    if (!hasAnvil) continue;
                    stats = converter.anvilToFolesium(dim, folesiumStore, config);
                }
                case TO_ANVIL -> {
                    if (!hasFolesium) continue;
                    stats = converter.folesiumToAnvil(folesiumStore, dim);
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
        printRetentionNote(worldRoot, dir, converted, players, keptStores);
        long millis = (System.nanoTime() - t0) / 1_000_000L;
        return new Report(converted, chunks, bytes, players, millis);
    }

    /**
     * cesium-fabric parity: the converter never deletes files, it only tells the
     * operator what is now redundant and safe to remove by hand.
     */
    private static void printRetentionNote(Path worldRoot, Direction dir,
                                           int dimensions, long players, Set<Path> keptStores) {
        if (dir == Direction.TO_ANVIL) {
            printRetainedStores(keptStores);
        } else if (dimensions > 0 || players > 0) {
            System.out.println("Folesium: no files were deleted. The vanilla files (region/, entities/, poi/ and the");
            System.out.println("Folesium: per-player files) were kept as a backup; the server ignores them while Folesium");
            System.out.println("Folesium: is enabled. Delete them manually once the converted world is verified.");
        }
    }

    /**
     * Prints the cesium-fabric-parity "nothing was deleted" note, one line per kept
     * Folesium source store. Every path is made absolute and normalized so the
     * operator can copy it verbatim even when the world root was given relatively.
     * Duplicate paths (e.g. a legacy world root that is both a dimension and the
     * player store) collapse into a single line via a {@link LinkedHashSet} so the
     * output order is stable and never misleading.
     */
    public static void printRetainedStores(Collection<? extends Path> stores) {
        LinkedHashSet<Path> kept = new LinkedHashSet<>();
        for (Path p : stores) {
            if (p != null) {
                kept.add(p.toAbsolutePath().normalize());
            }
        }
        if (kept.isEmpty()) {
            return;
        }
        System.out.println("Folesium: no files were deleted. The now-redundant Folesium stores were kept as a backup:");
        for (Path p : kept) {
            System.out.println("    " + p);
        }
        System.out.println("Folesium: delete them manually once the restored world is verified - and always BEFORE");
        System.out.println("Folesium: converting back to Folesium if you have played on Anvil in the meantime.");
    }

    /**
     * Converts the world's {@code playerdata/}, {@code advancements/} and {@code stats/}
     * to or from the world-root player store. Returns the number of records moved.
     */
    private long convertPlayerData(Path worldRoot, Direction dir, FolesiumConfig config) throws IOException {
        Path store = PlayerDataConverter.storeDirectoryFor(worldRoot);
        PlayerDataConverter.Stats stats;
        switch (dir) {
            case TO_FOLESIUM -> {
                if (!PlayerDataConverter.hasVanillaPlayerData(worldRoot)) {
                    return 0;
                }
                stats = PlayerDataConverter.anvilToFolesium(worldRoot, store, config);
            }
            case TO_ANVIL -> {
                if (FolesiumDatabase.readRole(store) != FolesiumDatabase.StoreRole.PLAYERS) {
                    return 0;
                }
                stats = PlayerDataConverter.folesiumToAnvil(store, worldRoot);
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
     * a pre-1.21.x single-dimension layout (it has an Anvil {@code region/} directory or
     * an already-converted dimension store).
     *
     * <p>A directory counts as a dimension when it contains an Anvil {@code region/}
     * directory, or a {@code folesium/} store whose recorded role is
     * {@link FolesiumDatabase.StoreRole#DIMENSION}. Checking the role -- not merely the
     * directory name -- is what keeps a world root holding a <em>player</em> store from
     * being converted as if it were a dimension.</p>
     *
     * <p>The scan is recursive and stops descending once a dimension directory is
     * found, so arbitrarily nested modded dimension layouts
     * ({@code dimensions/<ns>/<path>/<path>/...}) are discovered correctly. Directories
     * that can only ever hold leaf data ({@code region/}, {@code folesium/}, ...) are
     * skipped outright: descending into them on a large save means stat-ing tens of
     * thousands of {@code .mca} and shard files for nothing. Entries that cannot be
     * read (permissions, a dangling symlink) are ignored instead of aborting the whole
     * conversion.</p>
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
                if (dir.equals(worldRoot) || seen.contains(dir)) {
                    return FileVisitResult.CONTINUE;
                }
                if (isDataLeafDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (isDimensionDirectory(dir)) {
                    out.add(dir);
                    seen.add(dir);
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
     * Directories that hold data files rather than further dimensions. Walking into them
     * can never discover a dimension, so the scan skips their whole subtree.
     */
    private static boolean isDataLeafDirectory(Path dir) {
        Path name = dir.getFileName();
        if (name == null) {
            return false;
        }
        return switch (name.toString().toLowerCase(Locale.ROOT)) {
            case FolesiumDatabase.STORE_DIR_NAME, "region", "entities", "poi", "data",
                 "playerdata", "advancements", "stats", "datapacks", "serverconfig" -> true;
            default -> false;
        };
    }

    private static boolean isDimensionDirectory(Path dir) {
        return Files.isDirectory(dir.resolve("region"))
                || isDimensionStore(dir.resolve(FolesiumDatabase.STORE_DIR_NAME));
    }

    /** True only for a {@code folesium/} directory whose metadata says it holds chunk data. */
    private static boolean isDimensionStore(Path storeDir) {
        return FolesiumDatabase.readRole(storeDir) == FolesiumDatabase.StoreRole.DIMENSION;
    }
}
