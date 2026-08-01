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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rewrites a store with a different shard count.
 *
 * <p>{@code shardCount} is the one setting that cannot simply be swapped at runtime: it
 * selects the shard a key lives in ({@code mix64(key) & (shardCount - 1)}) and is stamped
 * into every shard file header. Changing it therefore means physically rewriting every
 * record. Folesium does this automatically the next time the store is opened, so that
 * editing {@code shards} in {@code folesium.properties} actually takes effect instead of
 * being silently ignored.</p>
 *
 * <h2>Crash safety</h2>
 * <p>The rewrite is a three-phase commit. The original shard files are not touched until
 * the complete new set exists on disk and has been fsynced:</p>
 * <ol>
 *   <li><b>Stage</b> - build the full new shard set under {@code .folesium-reshard/}.
 *       The live store is untouched; a crash here loses nothing.</li>
 *   <li><b>Commit</b> - fsync the staging directory, write the {@code COMMIT} marker, then
 *       update {@code store.shardCount} in the metadata. From this point the new layout is
 *       authoritative and the swap will be finished (never rolled back).</li>
 *   <li><b>Swap</b> - move the old shard files aside into {@code .folesium-reshard-old/}
 *       (recording completion with a {@code MOVED} marker), move the staged files in, then
 *       delete both scratch directories.</li>
 * </ol>
 *
 * <p>{@link #recover(Path)} runs before every store open and makes any interrupted reshard
 * converge: staging without {@code COMMIT} is discarded, staging with {@code COMMIT} is
 * driven to completion. The {@code MOVED} marker is what makes phase 3 re-runnable - without
 * it, a retry could mistake already-swapped-in new files for leftovers of the old set.</p>
 *
 * <p>Two ordering invariants make recovery safe. The shard-count metadata is updated before
 * the swap evidence is destroyed, so a crash can never leave the metadata and the shard
 * files disagreeing. And the old shard files are only deleted once every new shard is
 * confirmed present in the store directory, so a crash in the middle of phase 3 can never
 * destroy the only surviving copy of the records.</p>
 *
 * <p>Not thread-safe and not multi-process safe: it is only ever called from the
 * {@link FolesiumDatabase} constructor, before any keyspace exists and before the store is
 * published to other threads.</p>
 */
final class StoreResharder {

    private static final System.Logger LOGGER = System.getLogger("Folesium");

    static final String STAGING_DIR = ".folesium-reshard";
    static final String BACKUP_DIR = ".folesium-reshard-old";
    private static final String COMMIT_MARKER = "COMMIT";
    private static final String MOVED_MARKER = "MOVED";

    /** {@code <keyspace>-<NNNN>.flog}, matching {@link Keyspace}'s naming. */
    private static final Pattern SHARD_FILE = Pattern.compile("^(.+)-(\\d{4})\\.flog$");

    private StoreResharder() {
    }

    // --------------------------------------------------------------- recovery

    /**
     * Completes or discards an interrupted reshard. Cheap no-op when no reshard is in
     * flight, so it is safe to call on every open; it also repairs metadata that a crash
     * left disagreeing with the shard files (see {@link #reconcileStaleMetadata}).
     */
    static void recover(Path dir) {
        Path staging = dir.resolve(STAGING_DIR);
        Path backup = dir.resolve(BACKUP_DIR);
        boolean hasStaging = Files.isDirectory(staging);
        boolean hasBackup = Files.isDirectory(backup);
        Path movedMarker = backup.resolve(MOVED_MARKER);
        if (!hasStaging && !hasBackup) {
            // No reshard is in flight. The only possible damage is metadata that still
            // names the old shard count while the files already use the new layout - a
            // crash that destroyed the swap evidence before the metadata was updated.
            reconcileStaleMetadata(dir);
            return;
        }
        if (hasStaging && Files.isRegularFile(staging.resolve(COMMIT_MARKER))) {
            Integer newCount = committedShardCountIfValid(staging);
            boolean moved = Files.isRegularFile(movedMarker);
            // MOVED distinguishes the two ways the staged set can be incomplete: before it
            // exists everything in dir is the old set and the staging itself must hold the
            // whole new set; after it exists some staged files may already sit in dir, so
            // the new layout is judged across dir and staging together. Either way the swap
            // is only finished once every new shard is present in dir.
            boolean swappable = newCount != null && (moved
                    ? completeNewLayout(dir, staging, newCount)
                    : validStagedLayout(staging, newCount));
            if (swappable) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: resuming an interrupted reshard of {0}", dir);
                // Make the metadata durable BEFORE finishSwap destroys the COMMIT evidence:
                // a crash after this point leaves files and metadata agreeing even if the
                // swap itself still has to be re-run.
                applyShardCountMetadata(dir, newCount);
                finishSwap(dir, staging, backup);
                return;
            }
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: ignoring malformed or mismatched reshard COMMIT marker in {0}", dir);
            if (moved) {
                // MOVED is durable evidence that the old set was already displaced, so it
                // must not be restored over the freshly swapped-in new files. But with the
                // new set incomplete, the old files are also the only surviving copy of the
                // records the missing new shards would have held: keep them. Deleting them
                // while any new shard is still missing would silently destroy data.
                deleteRecursively(staging);
                fsyncDirectory(dir);
                return;
            }
        }
        if (!hasStaging && Files.isRegularFile(movedMarker)) {
            LOGGER.log(System.Logger.Level.INFO,
                    "Folesium: removing the backup of a completed reshard of {0}", dir);
            deleteRecursively(backup);
            // The swap evidence is gone, so bring the metadata in line with the files
            // before the store is opened.
            reconcileStaleMetadata(dir);
            return;
        }
        // No valid COMMIT marker: the new set was never authoritative. Restore any old files
        // moved aside before deleting scratch state, rather than destroying live shards.
        LOGGER.log(System.Logger.Level.WARNING,
                "Folesium: discarding an incomplete reshard of {0} (store is unchanged)", dir);
        if (hasBackup && !Files.isRegularFile(movedMarker)) {
            restoreFromBackup(dir, backup);
        }
        deleteRecursively(staging);
        deleteRecursively(backup);
    }

    /**
     * Moves every shard file the backup still holds and that {@code dir} is missing back into
     * place. Only reachable when no {@code MOVED} marker exists, i.e. no staged file was ever
     * swapped in, so anything already in {@code dir} is part of the same old set and wins.
     */
    private static void restoreFromBackup(Path dir, Path backup) {
        try {
            for (Path old : listShardFiles(backup)) {
                Path target = dir.resolve(old.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.move(old, target, StandardCopyOption.ATOMIC_MOVE);
                }
            }
            fsyncDirectory(dir);
        } catch (IOException e) {
            throw new FolesiumException("Cannot restore the original shard files of " + dir, e);
        }
    }

    // ---------------------------------------------------------------- reshard

    /**
     * Rewrites every keyspace found in {@code dir} to use {@code newShardCount} shards and
     * updates {@code store.shardCount} in the metadata.
     *
     * @param oldConfig the configuration matching the <em>current</em> on-disk layout
     *                  (its {@code shardCount()} must be the on-disk value)
     */
    static void reshard(Path dir, Path metadataFile, FolesiumConfig oldConfig, int newShardCount) {
        int oldShardCount = oldConfig.shardCount();
        if (oldShardCount == newShardCount) {
            return;
        }
        List<String> keyspaces = discoverKeyspaces(dir);
        long startedAt = System.nanoTime();
        LOGGER.log(System.Logger.Level.INFO,
                "Folesium: resharding {0} from {1} to {2} shards ({3} keyspace(s)); "
                        + "this rewrites every record and may take a while",
                dir, oldShardCount, newShardCount, keyspaces.size());

        Path staging = dir.resolve(STAGING_DIR);
        Path backup = dir.resolve(BACKUP_DIR);
        deleteRecursively(staging);
        deleteRecursively(backup);

        long records = 0;
        try {
            Files.createDirectories(staging);

            // Phase 1 - stage. Nothing outside the staging directory is touched, so a crash
            // anywhere in here leaves the live store fully intact.
            //
            // The staged shards are written with EXPLICIT durability: a single fsync per
            // shard at the end is enough, and it must happen before the COMMIT marker.
            FolesiumConfig writeConfig = oldConfig
                    .withShardCount(newShardCount)
                    .withDurability(FolesiumConfig.DurabilityMode.EXPLICIT);
            for (String name : keyspaces) {
                records += copyKeyspace(dir, staging, name, oldConfig, writeConfig, newShardCount);
            }
            fsyncDirectory(staging);

            // Phase 2 - commit. After the marker is durable the new layout wins, and any
            // later crash resumes the swap rather than rolling back.
            FolesiumDatabase.writeAtomically(staging.resolve(COMMIT_MARKER), Integer.toString(newShardCount));
            fsyncDirectory(staging);
            updateShardCountMetadata(metadataFile, oldShardCount, newShardCount);

            // Phase 3 - swap.
            finishSwap(dir, staging, backup);
        } catch (IOException e) {
            throw new FolesiumException("Reshard of " + dir + " failed", e);
        }

        LOGGER.log(System.Logger.Level.INFO,
                "Folesium: reshard of {0} complete - {1} record(s) rewritten into {2} shards in {3} ms",
                dir, records, newShardCount, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * Streams every live record of one keyspace into a freshly created shard set under
     * {@code staging}, re-routing each key with the new shard mask.
     *
     * @return number of records copied
     */
    private static long copyKeyspace(Path dir, Path staging, String name,
                                     FolesiumConfig oldConfig, FolesiumConfig writeConfig,
                                     int newShardCount) {
        long[] copied = {0};
        int mask = newShardCount - 1;
        ShardFile[] out = new ShardFile[newShardCount];
        try {
            for (int i = 0; i < newShardCount; i++) {
                out[i] = new ShardFile(staging.resolve(String.format("%s-%04d.flog", name, i)), i, writeConfig);
            }
            try (Keyspace source = new Keyspace(dir, name, oldConfig)) {
                source.forEach((key, value) -> {
                    out[(int) (Bytes.mix64(key) & mask)].put(new Bytes(key), value);
                    copied[0]++;
                });
            }
            for (ShardFile s : out) {
                s.flushIfDirty();
            }
        } finally {
            // Always release the file handles, even if the copy blew up half way: the
            // caller deletes the staging directory and a stale handle would keep the
            // files alive on Windows.
            closeQuietly(out);
        }
        return copied[0];
    }

    /**
     * Phase 3, written so that it can be re-entered after a crash at any point.
     *
     * <p>The {@code MOVED} marker is the pivot: before it exists, everything matching a shard
     * file name in {@code dir} belongs to the <em>old</em> set and must be moved aside; after
     * it exists, such files are freshly swapped-in <em>new</em> ones and must be left alone.</p>
     */
    private static void finishSwap(Path dir, Path staging, Path backup) {
        try {
            Path movedMarker = backup.resolve(MOVED_MARKER);
            if (!Files.isRegularFile(movedMarker)) {
                Files.createDirectories(backup);
                for (Path old : listShardFiles(dir)) {
                    Files.move(old, backup.resolve(old.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                FolesiumDatabase.writeAtomically(movedMarker, "ok");
                fsyncDirectory(backup);
                fsyncDirectory(dir);
            }
            if (Files.isDirectory(staging)) {
                try (Stream<Path> files = Files.list(staging)) {
                    List<Path> staged = files.filter(Files::isRegularFile)
                            .filter(p -> !COMMIT_MARKER.equals(p.getFileName().toString()))
                            .toList();
                    for (Path p : staged) {
                        Files.move(p, dir.resolve(p.getFileName().toString()),
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
                fsyncDirectory(dir);
            }
            deleteRecursively(staging);
            deleteRecursively(backup);
            fsyncDirectory(dir);
        } catch (IOException e) {
            throw new FolesiumException("Failed to finish the reshard swap in " + dir, e);
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Distinct keyspace names that have at least one shard file in {@code dir}. */
    static List<String> discoverKeyspaces(Path dir) {
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                Matcher m = SHARD_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    names.add(m.group(1));
                }
            });
        } catch (IOException e) {
            throw new FolesiumException("Cannot list store directory " + dir, e);
        }
        return new ArrayList<>(names);
    }

    /** Shard logs and their index hints, directly inside {@code dir} (scratch dirs excluded). */
    private static List<Path> listShardFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".flog") || n.endsWith(".flog.fidx");
                    })
                    .toList();
        }
    }

    private static Integer committedShardCountIfValid(Path staging) {
        Path marker = staging.resolve(COMMIT_MARKER);
        try {
            if (Files.size(marker) > 64) {
                return null;
            }
            String raw = Files.readString(marker, StandardCharsets.UTF_8).trim();
            int count = Integer.parseInt(raw);
            return Integer.bitCount(count) == 1 && count >= 1 && count <= 1024 ? count : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** Ensures the staged files represent exactly the count named by COMMIT. */
    private static boolean validStagedLayout(Path staging, int count) {
        try {
            for (String name : discoverKeyspaces(staging)) {
                for (int i = 0; i < count; i++) {
                    if (!Files.isRegularFile(staging.resolve(String.format("%s-%04d.flog", name, i)))) {
                        return false;
                    }
                }
                try (Stream<Path> files = Files.list(staging)) {
                    long actual = files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().startsWith(name + "-")
                                    && p.getFileName().toString().endsWith(".flog"))
                            .count();
                    if (actual != count) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * True when every shard of the new layout is present in {@code dir} or still in
     * {@code staging}, i.e. the staged-to-dir move was interrupted rather than lost and
     * {@link #finishSwap} can drive it to completion. Only consulted once the {@code MOVED}
     * marker exists, so the shard files in {@code dir} are freshly swapped-in new files
     * rather than the old set.
     */
    private static boolean completeNewLayout(Path dir, Path staging, int count) {
        try {
            TreeSet<String> names = new TreeSet<>();
            names.addAll(discoverKeyspaces(dir));
            names.addAll(discoverKeyspaces(staging));
            for (String name : names) {
                for (int i = 0; i < count; i++) {
                    if (!Files.isRegularFile(dir.resolve(String.format("%s-%04d.flog", name, i)))
                            && !Files.isRegularFile(staging.resolve(String.format("%s-%04d.flog", name, i)))) {
                        return false;
                    }
                }
                if (countShardFiles(dir, name) + countShardFiles(staging, name) != count) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Number of {@code .flog} shard files of one keyspace directly inside {@code dir}. */
    private static long countShardFiles(Path dir, String keyspace) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(keyspace + "-")
                            && p.getFileName().toString().endsWith(".flog"))
                    .count();
        }
    }

    /** Idempotently records {@code store.shardCount = newCount} (see {@link #recover}). */
    private static void applyShardCountMetadata(Path dir, int newCount) {
        Path meta = dir.resolve(FolesiumDatabase.METADATA_FILE);
        int oldCount = newCount;
        if (Files.isRegularFile(meta)) {
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new FolesiumException("Cannot read " + meta, e);
            }
            String raw = p.getProperty("store.shardCount");
            if (raw != null) {
                try {
                    oldCount = Integer.parseInt(raw.trim());
                } catch (RuntimeException ignore) {
                    // keep newCount as both old and new; metadata remains complete.
                }
            }
        }
        try {
            updateShardCountMetadata(meta, oldCount, newCount);
        } catch (IOException e) {
            throw new FolesiumException("Cannot update shard count metadata in " + dir, e);
        }
    }

    /**
     * Repairs metadata that was left naming the old shard count after the swap evidence
     * was destroyed. Heals exactly one damage pattern: every keyspace on disk is uniformly
     * laid out with a valid power-of-two shard count that differs from
     * {@code store.shardCount} - i.e. the swap finished but the metadata update did not
     * survive. The metadata is brought in line with the files and the store proceeds to
     * open; a store whose files already match the metadata is left untouched.
     */
    private static void reconcileStaleMetadata(Path dir) {
        Path meta = dir.resolve(FolesiumDatabase.METADATA_FILE);
        if (!Files.isRegularFile(meta)) {
            return;
        }
        int fileCount = consistentOnDiskShardCount(dir);
        if (fileCount < 0) {
            return;
        }
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new FolesiumException("Cannot read " + meta, e);
        }
        String raw = p.getProperty("store.shardCount");
        if (raw == null) {
            return;
        }
        int metaCount;
        try {
            metaCount = Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return;
        }
        if (metaCount == fileCount) {
            return;
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Folesium: shard files of {0} hold {1} shards but the metadata says {2}; "
                        + "repairing the metadata to match the files",
                dir, fileCount, metaCount);
        try {
            updateShardCountMetadata(meta, metaCount, fileCount);
        } catch (IOException e) {
            throw new FolesiumException("Cannot repair shard count metadata in " + dir, e);
        }
    }

    /**
     * The shard count every keyspace's on-disk files agree on, or -1 when the layout is
     * not uniform: keyspaces disagree with each other, a keyspace is missing shard
     * indices, or there are no shard files at all.
     */
    private static int consistentOnDiskShardCount(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            HashMap<String, TreeSet<Integer>> indices = new HashMap<>();
            files.filter(Files::isRegularFile).forEach(p -> {
                Matcher m = SHARD_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    indices.computeIfAbsent(m.group(1), k -> new TreeSet<>())
                            .add(Integer.parseInt(m.group(2)));
                }
            });
            if (indices.isEmpty()) {
                return -1;
            }
            int count = -1;
            for (TreeSet<Integer> set : indices.values()) {
                int size = set.size();
                if (set.first() != 0 || set.last() != size - 1) {
                    return -1;
                }
                if (count == -1) {
                    count = size;
                } else if (count != size) {
                    return -1;
                }
            }
            if (Integer.bitCount(count) != 1 || count < 1 || count > 1024) {
                return -1;
            }
            return count;
        } catch (IOException e) {
            return -1;
        }
    }

    private static void updateShardCountMetadata(Path meta, int oldCount, int newCount) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(meta)) {
            try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }
        p.setProperty("store.shardCount", Integer.toString(newCount));
        p.setProperty("store.previousShardCount", Integer.toString(oldCount));
        p.setProperty("store.reshardedAt", Long.toString(System.currentTimeMillis()));
        FolesiumDatabase.writeMetadataAtomically(meta, p);
    }

    private static void closeQuietly(ShardFile[] shards) {
        for (ShardFile s : shards) {
            if (s == null) {
                continue;
            }
            try {
                s.close();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Folesium: error closing a staged shard", e);
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new FolesiumException("Cannot remove " + root, e);
        }
    }

    /**
     * Best-effort directory fsync so that renames survive a power cut. Opening a directory
     * for reading is not portable (it fails on Windows), and a failure here only widens the
     * crash window rather than corrupting anything, so it is deliberately swallowed.
     */
    private static void fsyncDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (var ch = java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Not supported on this platform/filesystem.
        }
    }
}
