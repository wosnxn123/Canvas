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
import dev.folesium.core.index.DictionaryStore;
import dev.folesium.core.util.LongKeys;
import dev.folesium.core.util.ZstdNative;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a Minecraft dimension between Anvil directories
 * ({@code region/}, {@code entities/}, {@code poi/}) and a Folesium store.
 *
 * <p>Chunk payloads are treated as opaque NBT byte blobs: the converter never
 * parses NBT, so it is version-independent and lossless.</p>
 *
 * <p>Anvil {@code region/entities/poi} directories map to Folesium keyspaces
 * {@code chunks/entities/poi}; keys are {@link LongKeys#chunkKey(int, int)}.</p>
 */
public final class WorldConverter {
    private static final Pattern MCA = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** Anvil dir name -> Folesium keyspace name. */
    public static final Map<String, String> DIR_TO_KEYSPACE = Map.of(
            "region", FolesiumDatabase.KS_CHUNKS,
            "entities", FolesiumDatabase.KS_ENTITIES,
            "poi", FolesiumDatabase.KS_POI
    );

    public record Stats(long chunks, long bytes, long millis) {
    }

    private final int threads;

    public WorldConverter() {
        this(Math.min(16, Runtime.getRuntime().availableProcessors()));
    }

    public WorldConverter(int threads) {
        this.threads = Math.max(1, threads);
    }

    // ------------------------------------------------------- anvil -> folesium

    /**
     * Converts an Anvil dimension directory into a Folesium store.
     * Parallelism is per region file; the Folesium store is shared (it is
     * thread-safe by design).
     *
     * <p>The write is <em>merge</em> mode: a chunk already present in the store
     * (e.g. migrated live by a running server before the conversion ran) is kept,
     * and only chunks missing from the store are taken from Anvil. This makes the
     * "enable Folesium on an un-converted world, then convert later" path safe: no
     * player edits are clobbered by the older Anvil bytes. A fresh (empty) store
     * behaves exactly like a full overwrite.</p>
     *
     * <p>With {@code backupOnConvert} the existing store is moved to a
     * {@code .folesium-backup-*} sibling before the fresh store is created, turning
     * the merge into a full rebuild of the store (the previous store is preserved
     * under the backup name).</p>
     *
     * <p>When dictionary compression is enabled, the per-keyspace dictionaries
     * ({@code <store>/idx/<name>/dict.bin}) of the converted region keyspaces are trained
     * after the flush, so codec-3 (ZSTD_DICT) writes begin on the next open; see
     * {@link #trainDictionariesIfMissing(Path, List, FolesiumConfig)}.</p>
     */
    public Stats anvilToFolesium(Path dimensionDir, Path folesiumDir, FolesiumConfig config) throws IOException {
        long start = System.nanoTime();
        AtomicLong chunkCount = new AtomicLong();
        AtomicLong byteCount = new AtomicLong();

        if (config.backupOnConvert() && Files.isDirectory(folesiumDir)) {
            movePath(folesiumDir, backupPath(folesiumDir), false);
        }

        try (FolesiumDatabase db = FolesiumDatabase.open(folesiumDir,
                config.withDurability(FolesiumConfig.DurabilityMode.EXPLICIT))) {
            List<Keyspace> converted = new ArrayList<>();
            for (Map.Entry<String, String> e : DIR_TO_KEYSPACE.entrySet()) {
                Path src = dimensionDir.resolve(e.getKey());
                if (!Files.isDirectory(src)) {
                    continue;
                }
                Keyspace ks = db.keyspace(e.getValue());
                converted.add(ks);
                List<Path> mcaFiles;
                try (var s = Files.list(src)) {
                    mcaFiles = s.filter(p -> MCA.matcher(p.getFileName().toString()).matches()).toList();
                }
                runParallel(mcaFiles, mca -> {
                    Matcher m = MCA.matcher(mca.getFileName().toString());
                    if (!m.matches()) {
                        return;
                    }
                    int regionX;
                    int regionZ;
                    try {
                        regionX = Integer.parseInt(m.group(1));
                        regionZ = Integer.parseInt(m.group(2));
                        // Reject coordinates whose region -> chunk shift would overflow int
                        // (|region| >= 2^26); such names are corrupt or foreign files. Compare in
                        // long so that region == Integer.MIN_VALUE cannot slip past Math.abs
                        // (whose overflow keeps the value negative).
                        long rx = regionX;
                        long rz = regionZ;
                        if (rx >= (1L << 26) || rx <= -(1L << 26)
                                || rz >= (1L << 26) || rz <= -(1L << 26)) {
                            throw new NumberFormatException("region coordinate out of range");
                        }
                    } catch (NumberFormatException ex) {
                        // Out-of-range coordinates (e.g. r.9999999999999999.0.mca from a
                        // corrupt or foreign file): skip the file instead of aborting the
                        // whole conversion.
                        System.err.println("Folesium: skipping " + mca.getFileName()
                                + ": region coordinates out of range");
                        return;
                    }
                    try (AnvilRegionFile rf = new AnvilRegionFile(mca)) {
                        for (int[] xz : rf.listChunks()) {
                            byte[] payload = rf.readChunk(xz[0], xz[1]);
                            if (payload == null) {
                                continue;
                            }
                            int cx = (regionX << 5) + xz[0];
                            int cz = (regionZ << 5) + xz[1];
                            if (ks.putIfAbsent(LongKeys.chunkKey(cx, cz), payload)) {
                                chunkCount.incrementAndGet();
                                byteCount.addAndGet(payload.length);
                            }
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException("Failed converting " + mca, ex);
                    }
                });
            }
            db.flush();
            trainDictionariesIfMissing(folesiumDir, converted, config);
        }
        return new Stats(chunkCount.get(), byteCount.get(), (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Converter-tail dictionary bootstrap: after a successful conversion (every region record
     * written and flushed, close pending), train the per-keyspace zstd dictionary
     * ({@code <store>/idx/<name>/dict.bin}) for each converted region-keyed keyspace whose
     * dictionary is still missing, so codec-3 (ZSTD_DICT) writes begin on the next open of the
     * store. The records just written are plain-compressed - their shards snapshot the
     * dictionary at construction, when dict.bin did not exist yet - so training here is safe:
     * no record on disk depends on the new dictionary. A keyspace already carrying a dictionary
     * (a previous conversion, or a manual {@code DictionaryStore.train}) is left untouched.
     *
     * <p>Sampling mirrors the store's own scheme: the first 64 live record values, keys
     * collected via a keys-only pass ({@link Keyspace#forEachKey}) and the values read back
     * individually. A keyspace with no live records cannot yield a useful dictionary and is
     * skipped. Training failure never fails the conversion: the store is simply left without a
     * dictionary (plain compression) and a warning is logged.</p>
     */
    private static void trainDictionariesIfMissing(Path storeDir, List<Keyspace> converted,
                                                   FolesiumConfig config) {
        if (!config.dictionaryCompression() || !ZstdNative.dictAvailable()) {
            return;
        }
        for (Keyspace ks : converted) {
            Path dictFile = storeDir.resolve("idx").resolve(ks.name()).resolve("dict.bin");
            if (DictionaryStore.exists(dictFile)) {
                // Already trained; a different dictionary would make existing codec-3 records
                // undecodable, so it is never retrained.
                continue;
            }
            if (ks.count() == 0) {
                // Nothing to sample: an empty keyspace cannot yield a useful dictionary.
                continue;
            }
            List<byte[]> sampleKeys = new ArrayList<>(64);
            ks.forEachKey(key -> {
                if (sampleKeys.size() < 64) {
                    sampleKeys.add(key);
                }
            });
            if (sampleKeys.isEmpty()) {
                continue;
            }
            List<byte[]> samples = new ArrayList<>(sampleKeys.size());
            for (byte[] key : sampleKeys) {
                byte[] value = ks.get(key);
                if (value != null) {
                    samples.add(value);
                }
            }
            if (samples.isEmpty()) {
                continue;
            }
            try {
                DictionaryStore.trainIfMissing(dictFile, samples);
            } catch (IOException | RuntimeException e) {
                // Never block the conversion on a dictionary; the store stays on plain
                // compression and codec-3 writes begin only after a successful retrain.
                System.getLogger("Folesium").log(System.Logger.Level.WARNING,
                        "Folesium: could not train the dictionary of keyspace '" + ks.name()
                                + "' from " + samples.size() + " samples; the store stays on plain "
                                + "compression until a retrain succeeds: " + e);
            }
        }
    }

    // ------------------------------------------------------- folesium -> anvil

    /**
     * Materializes the Folesium keyspaces as authoritative Anvil directories.
     *
     * <p>Default (cesium-fabric parity): each keyspace is written <em>in place</em>
     * into the target directory - existing {@code .mca} files are opened and the
     * stored chunks overwrite their slots, so no backup or staging tree is left
     * behind and a repeated conversion simply overwrites the previous result.</p>
     *
     * <p>With {@code backupOnConvert} each keyspace is built in a sibling staging
     * directory first and then atomically swapped in; the previous directory is
     * retained under a unique {@code .folesium-backup-*} name (older backup trees
     * are pruned), so records absent from the store cannot survive in the restored
     * tree.</p>
     */
    public Stats folesiumToAnvil(Path folesiumDir, Path dimensionDir, FolesiumConfig config) throws IOException {
        long start = System.nanoTime();
        AtomicLong chunkCount = new AtomicLong();
        AtomicLong byteCount = new AtomicLong();
        if (!Files.isDirectory(folesiumDir)) {
            // Nothing to roll back; opening would create an empty store out of thin air.
            return new Stats(0, 0, (System.nanoTime() - start) / 1_000_000);
        }

        // Export only: read the store's existing layout without rewriting it first.
        try (FolesiumDatabase db = FolesiumDatabase.open(folesiumDir,
                FolesiumConfig.defaults().withDurability(FolesiumConfig.DurabilityMode.EXPLICIT),
                FolesiumDatabase.StoreRole.DIMENSION, false)) {
            for (Map.Entry<String, String> e : DIR_TO_KEYSPACE.entrySet()) {
                Path out = dimensionDir.resolve(e.getKey());
                Keyspace ks = db.keyspace(e.getValue());
                if (ks.count() == 0 && !Files.exists(out)) {
                    // Keep brand-new worlds free of meaningless empty Anvil roots.
                    continue;
                }
                if (config.backupOnConvert()) {
                    convertKeyspaceViaStaging(out, ks, chunkCount, byteCount);
                } else {
                    convertKeyspaceInPlace(out, ks, chunkCount, byteCount);
                }
            }
        }
        return new Stats(chunkCount.get(), byteCount.get(), (System.nanoTime() - start) / 1_000_000);
    }

    /** Backup-mode path: write a clean staging tree, swap it in, keep the old tree as backup. */
    private void convertKeyspaceViaStaging(Path out, Keyspace ks, AtomicLong chunkCount, AtomicLong byteCount)
            throws IOException {
        Path staging = siblingPath(out, ".folesium-staging-");
        Files.createDirectories(staging);
        try {
            writeKeyspace(staging, ks, chunkCount, byteCount);
            replaceDirectory(out, staging);
        } catch (RuntimeException | IOException ex) {
            deleteTreeQuietly(staging);
            throw ex;
        }
    }

    /** Default path: write chunks straight into the target directory, reusing existing region files. */
    private void convertKeyspaceInPlace(Path out, Keyspace ks, AtomicLong chunkCount, AtomicLong byteCount)
            throws IOException {
        Files.createDirectories(out);
        writeKeyspace(out, ks, chunkCount, byteCount);
    }

    private void writeKeyspace(Path writeRoot, Keyspace ks,
                               AtomicLong chunkCount, AtomicLong byteCount) throws IOException {
        Map<Long, List<Long>> byRegion = new ConcurrentHashMap<>();
        ks.forEachKey(k -> {
            long key = LongKeys.decode(k);
            int cx = LongKeys.chunkX(key);
            int cz = LongKeys.chunkZ(key);
            long regionKey = LongKeys.chunkKey(cx >> 5, cz >> 5);
            byRegion.computeIfAbsent(regionKey, r -> new ArrayList<>()).add(key);
        });

        runParallel(new ArrayList<>(byRegion.keySet()), regionKey -> {
            int rx = LongKeys.chunkX(regionKey);
            int rz = LongKeys.chunkZ(regionKey);
            Path mca = writeRoot.resolve("r." + rx + "." + rz + ".mca");
            try (AnvilRegionFile rf = new AnvilRegionFile(mca)) {
                for (long key : byRegion.get(regionKey)) {
                    byte[] payload = ks.get(key);
                    if (payload == null) {
                        continue;
                    }
                    rf.writeChunk(LongKeys.chunkX(key) & 31, LongKeys.chunkZ(key) & 31, payload);
                    chunkCount.incrementAndGet();
                    byteCount.addAndGet(payload.length);
                }
                rf.sync();
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed writing " + mca, ex);
            }
        });
    }

    private static Path siblingPath(Path destination, String marker) {
        Path parent = destination.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            parent = destination.toAbsolutePath().normalize().getRoot();
        }
        return parent.resolve(destination.getFileName() + marker + UUID.randomUUID());
    }

    /** The (pruned) {@code .folesium-backup-*} sibling name for a target path. */
    private static Path backupPath(Path destination) throws IOException {
        pruneOldBackups(destination);
        return siblingPath(destination, ".folesium-backup-");
    }

    /** Replaces a destination directory while retaining the previous tree as a backup. */
    private static void replaceDirectory(Path destination, Path staging) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
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
     * after this call.
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
                .comparingLong(WorldConverter::lastModifiedMillis)
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
                    // The original destination remains in its backup if cleanup fails.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup of an uncommitted staging tree.
        }
    }

    private <T> void runParallel(List<T> items, java.util.function.Consumer<T> task) {
        if (items.isEmpty()) {
            return;
        }
        int n = Math.min(threads, items.size());
        if (n == 1) {
            items.forEach(task);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(n,
                Thread.ofPlatform().name("folesium-convert-", 0).factory());
        List<Future<?>> futures = new ArrayList<>(items.size());
        CompletionService<Void> completed = new ExecutorCompletionService<>(pool);
        RuntimeException failure = null;
        boolean interrupted = false;
        try {
            for (T item : items) {
                futures.add(completed.submit(() -> {
                    task.accept(item);
                    return null;
                }));
            }
            for (int i = 0; i < items.size(); i++) {
                completed.take().get();
            }
        } catch (InterruptedException e) {
            failure = new RuntimeException("Conversion interrupted", e);
            interrupted = true;
            futures.forEach(f -> f.cancel(true));
        } catch (java.util.concurrent.ExecutionException e) {
            failure = new RuntimeException("Conversion task failed", e.getCause());
            futures.forEach(f -> f.cancel(true));
        } finally {
            if (failure != null) {
                futures.forEach(f -> f.cancel(true));
                pool.shutdownNow();
            } else {
                pool.shutdown();
            }
            for (;;) {
                try {
                    if (pool.awaitTermination(1, TimeUnit.DAYS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    failure = failure != null ? failure : new RuntimeException("Conversion interrupted", e);
                    futures.forEach(f -> f.cancel(true));
                    pool.shutdownNow();
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
