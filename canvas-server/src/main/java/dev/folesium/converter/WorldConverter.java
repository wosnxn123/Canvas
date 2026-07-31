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
import dev.folesium.core.util.LongKeys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
     */
    public Stats anvilToFolesium(Path dimensionDir, Path folesiumDir, FolesiumConfig config) throws IOException {
        long start = System.nanoTime();
        AtomicLong chunkCount = new AtomicLong();
        AtomicLong byteCount = new AtomicLong();

        try (FolesiumDatabase db = FolesiumDatabase.open(folesiumDir,
                config.withDurability(FolesiumConfig.DurabilityMode.EXPLICIT))) {
            for (Map.Entry<String, String> e : DIR_TO_KEYSPACE.entrySet()) {
                Path src = dimensionDir.resolve(e.getKey());
                if (!Files.isDirectory(src)) {
                    continue;
                }
                Keyspace ks = db.keyspace(e.getValue());
                List<Path> mcaFiles;
                try (var s = Files.list(src)) {
                    mcaFiles = s.filter(p -> MCA.matcher(p.getFileName().toString()).matches()).toList();
                }
                runParallel(mcaFiles, mca -> {
                    Matcher m = MCA.matcher(mca.getFileName().toString());
                    if (!m.matches()) {
                        return;
                    }
                    int regionX = Integer.parseInt(m.group(1));
                    int regionZ = Integer.parseInt(m.group(2));
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
        }
        return new Stats(chunkCount.get(), byteCount.get(), (System.nanoTime() - start) / 1_000_000);
    }

    // ------------------------------------------------------- folesium -> anvil

    /**
     * Converts a Folesium store back into Anvil directories. Regions are
     * grouped first so each region file is written by exactly one task.
     *
     * <p>Like cesium-fabric's converter, <em>nothing is deleted</em>: the Folesium
     * store is left in place as a backup. Delete it manually once the restored Anvil
     * world has been verified -- and always before re-converting to Folesium after
     * having played on Anvil, because {@link #anvilToFolesium} merges and would keep
     * the (older) store records over the newer Anvil chunks.</p>
     */
    public Stats folesiumToAnvil(Path folesiumDir, Path dimensionDir) throws IOException {
        long start = System.nanoTime();
        AtomicLong chunkCount = new AtomicLong();
        AtomicLong byteCount = new AtomicLong();
        if (!Files.isDirectory(folesiumDir)) {
            // Nothing to roll back; opening would create an empty store out of thin air.
            return new Stats(0, 0, (System.nanoTime() - start) / 1_000_000);
        }

        // Export only: open the store exactly as it lies on disk (applyLayoutChanges=false),
        // so a shard count or codec that differs from the defaults is read as-is instead of
        // triggering a pointless rewrite of a store we read once and then abandon.
        try (FolesiumDatabase db = FolesiumDatabase.open(folesiumDir,
                FolesiumConfig.defaults().withDurability(FolesiumConfig.DurabilityMode.EXPLICIT),
                FolesiumDatabase.StoreRole.DIMENSION, false)) {
            for (Map.Entry<String, String> e : DIR_TO_KEYSPACE.entrySet()) {
                String anvilDir = e.getKey();
                Keyspace ks = db.keyspace(e.getValue());
                if (ks.count() == 0) {
                    continue;
                }
                Path out = dimensionDir.resolve(anvilDir);
                Files.createDirectories(out);

                // Group chunk keys by region. Keys only: forEach would read back and
                // decompress every chunk here, and each one is read again below anyway -
                // that is a full extra decompression pass over the whole dimension.
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
                    Path mca = out.resolve("r." + rx + "." + rz + ".mca");
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
        }
        // Like cesium-fabric, the converter never deletes anything: the Anvil directories
        // now hold a complete copy of the world and the store stays behind as a backup
        // for the user to delete manually.
        return new Stats(chunkCount.get(), byteCount.get(), (System.nanoTime() - start) / 1_000_000);
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
        ExecutorService pool = Executors.newFixedThreadPool(n, Thread.ofPlatform().name("folesium-convert-", 0).factory());
        try {
            List<Future<?>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(pool.submit(() -> task.accept(item)));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Conversion interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("Conversion task failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
