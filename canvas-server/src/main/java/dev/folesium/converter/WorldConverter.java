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
import dev.folesium.core.FolesiumException;
import dev.folesium.core.Keyspace;
import dev.folesium.core.index.DictionaryStore;
import dev.folesium.core.util.LongKeys;
import dev.folesium.core.util.ZstdNative;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
     * under the backup name). If the rebuild fails, the backup is moved back to its
     * original location, so a failed backup-mode conversion leaves the previous
     * store in place instead of a half-written store.</p>
     *
     * <p>When dictionary compression is enabled, the per-keyspace dictionaries
     * ({@code <store>/idx/<name>/dict.bin}) of the converted region keyspaces are trained
     * after the flush, so codec-3 (ZSTD_DICT) writes begin on the next open; see
     * {@link #trainDictionariesIfMissing(Path, List, FolesiumConfig)}.</p>
     */
    public Stats anvilToFolesium(Path dimensionDir, Path folesiumDir, FolesiumConfig config) throws IOException {
        return anvilToFolesium(dimensionDir, folesiumDir, config, null);
    }

    /**
     * Same as {@link #anvilToFolesium(Path, Path, FolesiumConfig)}, but reports the backup
     * sibling created for a pre-existing store (only when {@code backupOnConvert} is set)
     * through {@code backupSink}, once the conversion has succeeded. The sink is never
     * invoked when the conversion fails, because the backup is then moved back to its
     * original location and the failed run leaves the canonical store untouched.
     *
     * @param backupSink receives the {@code .folesium-backup-*} sibling of {@code folesiumDir}
     *                   after a successful backup-mode rebuild; {@code null} to ignore
     */
    public Stats anvilToFolesium(Path dimensionDir, Path folesiumDir, FolesiumConfig config,
                                 Consumer<Path> backupSink) throws IOException {
        long start = System.nanoTime();
        AtomicLong chunkCount = new AtomicLong();
        AtomicLong byteCount = new AtomicLong();

        // With backupOnConvert the existing store is moved aside first; the whole move +
        // rebuild runs inside the try below, so a failed conversion restores the backup
        // instead of leaving the canonical path missing or holding a half-written store.
        // Only once the move succeeded (backedUp) may the rollback touch the canonical
        // path: when the initial move itself failed, the original store is still in
        // place and must not be deleted.
        Path backup = null;
        boolean backedUp = false;
        // Read once up front: the per-region worker below needs the effective flag to
        // decide whether a corrupt region aborts (backup mode: rollback protects the
        // pre-existing store) or is skipped (first conversion: the source keeps the data).
        boolean backupOnConvert = config.backupOnConvert();
        if (backupOnConvert && Files.isDirectory(folesiumDir) && !isEmptyDirectory(folesiumDir)) {
            backup = backupPath(folesiumDir);
        }
        try {
            if (backup != null) {
                movePath(folesiumDir, backup, false);
                backedUp = true;
            }

            try (FolesiumDatabase db = FolesiumDatabase.open(folesiumDir,
                    FolesiumDatabase.alignToDiskLayout(folesiumDir,
                            config.withDurability(FolesiumConfig.DurabilityMode.EXPLICIT)),
                    FolesiumDatabase.StoreRole.DIMENSION, true)) {
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
                    } catch (NoSuchFileException ex) {
                        // The whole source directory vanished between the isDirectory check
                        // above and the listing (concurrent cleanup) - the same transient
                        // race the per-file level skips. In backup mode the rebuild would
                        // silently lack this keyspace while the old store sits aside until
                        // pruned, so abort and roll back; otherwise skip with a warning.
                        if (backupOnConvert) {
                            throw ex;
                        }
                        System.err.println("Folesium: skipping " + src
                                + ": it disappeared while importing (its keyspace stays absent from the store)");
                        continue;
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
                            // whole conversion - except in backup mode, where skipping would
                            // silently drop the region's data when the backup tree is pruned
                            // after the "successful" rebuild (same rule as the read failure
                            // below).
                            if (backupOnConvert) {
                                throw new RuntimeException("Failed converting " + mca, ex);
                            }
                            System.err.println("Folesium: skipping " + mca.getFileName()
                                    + ": region coordinates out of range");
                            return;
                        }
                        try (AnvilRegionFile rf = AnvilRegionFile.openReadOnly(mca)) {
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
                            if (backupOnConvert) {
                                // Backup mode must abort on a corrupt region: the rollback
                                // restores the pre-existing store, whose data for this
                                // region would otherwise be silently lost when the backup
                                // tree is pruned after a "successful" rebuild.
                                throw new UncheckedIOException("Failed converting " + mca, ex);
                            }
                            // Mirror the export side (the TO_ANVIL pass skips unreadable
                            // records): a corrupt or torn region file must not abort the
                            // whole world adoption - skip it with a loud warning and
                            // convert what is readable. This is safe only on a first
                            // conversion: the source world keeps the data, so the region
                            // can be re-converted after a repair. The conversion is
                            // non-destructive (the source world is untouched), and a
                            // skipped region simply stays absent from the store, exactly
                            // like the export-side skip semantics.
                            System.err.println("Folesium: skipping corrupt region file " + mca
                                    + " (" + ex + "); its chunks stay absent from the store");
                        }
                    });
                }
                db.flush();
                trainDictionariesIfMissing(folesiumDir, converted, config);
            }
        } catch (IOException | RuntimeException ex) {
            if (backedUp) {
                rollbackFailedBackup(folesiumDir, backup, ex);
            }
            throw ex;
        }
        if (backup != null && backupSink != null) {
            backupSink.accept(backup);
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
     * skipped. Neither the sampling pass nor the training itself fails the conversion: the
     * store is simply left without a dictionary (plain compression) and a warning is logged.</p>
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
            int sampled = 0;
            try {
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
                sampled = samples.size();
                DictionaryStore.trainIfMissing(dictFile, samples);
            } catch (IOException | RuntimeException e) {
                // Never block the conversion on a dictionary - neither the training
                // itself nor the sampling pass (forEachKey / get): a shard read error
                // mid-scan must not roll back a store that was already fully written
                // and flushed. The store stays on plain compression and codec-3
                // writes begin only after a successful retrain.
                System.getLogger("Folesium").log(System.Logger.Level.WARNING,
                        "Folesium: could not train the dictionary of keyspace '" + ks.name()
                                + "' from " + sampled + " samples; the store stays on plain "
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
        return folesiumToAnvil(folesiumDir, dimensionDir, config,
                FolesiumDatabase.StoreRole.DIMENSION, null);
    }

    /**
     * Same as {@link #folesiumToAnvil(Path, Path, FolesiumConfig)}, but opens the source
     * store with the given {@code role} instead of assuming
     * {@link FolesiumDatabase.StoreRole#DIMENSION}. Pass the role recorded in the store's
     * metadata ({@link FolesiumDatabase#readRole(Path)}): a PLAYERS store opened as
     * DIMENSION would fail ("Refusing to mix player data and chunk data in one store"),
     * and a store without a recorded role would otherwise be converted as if it were an
     * empty dimension store.
     *
     * @param role the store role to open {@code folesiumDir} as
     * @throws FolesiumException if {@code role} is not
     *         {@link FolesiumDatabase.StoreRole#DIMENSION}: a PLAYERS store holds no chunk
     *         data, so exporting it to Anvil region files would silently write nothing
     */
    public Stats folesiumToAnvil(Path folesiumDir, Path dimensionDir, FolesiumConfig config,
                                 FolesiumDatabase.StoreRole role) throws IOException {
        return folesiumToAnvil(folesiumDir, dimensionDir, config, role, null);
    }

    /**
     * Same as {@link #folesiumToAnvil(Path, Path, FolesiumConfig, FolesiumDatabase.StoreRole)},
     * but reports each {@code .folesium-backup-*} sibling actually created by a backup-mode
     * swap through {@code backupSink}. The sink fires <em>eagerly</em>, right after each
     * keyspace's swap succeeds -- not only once the whole export has succeeded: keyspaces
     * are swapped one at a time, so when a later keyspace fails the dimension is left in a
     * mixed exported/untouched state and the backups already swapped (and already reported)
     * are exactly the trees the operator must restore. The sink is invoked only when a
     * pre-existing vanilla directory was moved aside (an empty store replaces nothing),
     * so callers can tell the operator exactly which vanilla trees were kept.
     *
     * @param role       the store role to open {@code folesiumDir} as
     * @param backupSink receives the {@code .folesium-backup-*} sibling of each replaced
     *                   vanilla directory right after its swap succeeds;
     *                   {@code null} to ignore
     * @throws FolesiumException if {@code role} is not
     *         {@link FolesiumDatabase.StoreRole#DIMENSION}: a PLAYERS store holds no chunk
     *         data, so exporting it to Anvil region files would silently write nothing
     */
    public Stats folesiumToAnvil(Path folesiumDir, Path dimensionDir, FolesiumConfig config,
                                 FolesiumDatabase.StoreRole role, Consumer<Path> backupSink) throws IOException {
        if (role != FolesiumDatabase.StoreRole.DIMENSION) {
            throw new FolesiumException("to-anvil export requires a DIMENSION store; "
                    + "use the world convert command for PLAYERS stores");
        }
        long start = System.nanoTime();
        AtomicLong chunkCount = new AtomicLong();
        AtomicLong byteCount = new AtomicLong();
        if (!Files.isDirectory(folesiumDir)) {
            // Nothing to roll back; opening would create an empty store out of thin air.
            return new Stats(0, 0, (System.nanoTime() - start) / 1_000_000);
        }

        // Export only: read the store's existing layout without rewriting it first. Backup
        // mode swaps each keyspace independently, so a failure mid-export leaves the
        // dimension in a mixed state; collect the .folesium-backup-* trees created by the
        // swaps that succeeded so the failure path can point at them explicitly.
        List<Path> swapped = new ArrayList<>();
        Consumer<Path> sink = swapped::add;
        if (backupSink != null) {
            sink = sink.andThen(backupSink);
        }
        try (FolesiumDatabase db = FolesiumDatabase.open(folesiumDir,
                FolesiumConfig.defaults().withDurability(FolesiumConfig.DurabilityMode.EXPLICIT),
                role, false)) {
            for (Map.Entry<String, String> e : DIR_TO_KEYSPACE.entrySet()) {
                Path out = dimensionDir.resolve(e.getKey());
                Keyspace ks = db.keyspace(e.getValue());
                if (ks.count() == 0 && !Files.exists(out)) {
                    // Keep brand-new worlds free of meaningless empty Anvil roots.
                    continue;
                }
                // An EMPTY existing directory (e.g. an empty region/ root) holds nothing
                // to preserve: backing it up would create a permanent empty
                // .folesium-backup-* tree (pruneOldBackups keeps the newest of each
                // class) and a misleading 'kept as backups' log entry.
                if (ks.count() == 0 && WorldConverter.isEmptyDirectory(out)) {
                    continue;
                }
                if (config.backupOnConvert()) {
                    convertKeyspaceViaStaging(out, ks, chunkCount, byteCount, sink);
                } else {
                    convertKeyspaceInPlace(out, ks, chunkCount, byteCount);
                }
            }
        } catch (RuntimeException | IOException ex) {
            if (config.backupOnConvert() && !swapped.isEmpty()) {
                // Keyspaces are swapped one at a time (a backup-mode dimension export is
                // not atomic): the ones swapped before the failure hold the exported data,
                // the rest are untouched, and the replaced vanilla trees survive only under
                // the .folesium-backup-* names. Say where they are -- the driver's
                // finally-report lists them too, but without this warning the operator
                // would not know the export was left incomplete.
                System.err.println("Folesium: WARNING: dimension " + dimensionDir.toAbsolutePath().normalize()
                        + " left in mixed exported/untouched state: the export failed after some keyspaces");
                System.err.println("Folesium: were already swapped (keyspaces swap independently, so a dimension export");
                System.err.println("Folesium: is not atomic). The replaced vanilla trees are preserved as backups at:");
                for (Path backup : swapped) {
                    System.err.println("    " + backup.toAbsolutePath().normalize());
                }
                System.err.println("Folesium: restore those backups to undo the already-swapped keyspaces before re-running.");
            }
            throw ex;
        }
        return new Stats(chunkCount.get(), byteCount.get(), (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Backup-mode path: write a clean staging tree, swap it in, keep the old tree as backup.
     * The created {@code .folesium-backup-*} sibling (or {@code null} when the target did
     * not exist) is reported through {@code backupSink} so callers can print its exact path.
     */
    private void convertKeyspaceViaStaging(Path out, Keyspace ks, AtomicLong chunkCount, AtomicLong byteCount,
                                           Consumer<Path> backupSink) throws IOException {
        // This path only runs under backupOnConvert, so crash leftovers of an earlier
        // backup-mode run (a crash between staging and swap) must be collected before a
        // new staging tree is created - otherwise they accumulate next to the restored
        // trees instead of being swept by a later in-place run.
        cleanStagingSiblings(out);
        Path staging = siblingPath(out, ".folesium-staging-");
        Files.createDirectories(staging);
        try {
            writeKeyspace(staging, ks, chunkCount, byteCount);
            Path backup = replaceDirectory(out, staging);
            if (backup != null && backupSink != null) {
                backupSink.accept(backup);
            }
        } catch (RuntimeException | IOException ex) {
            deleteTreeQuietly(staging);
            throw ex;
        }
    }

    /** Default path: write chunks straight into the target directory, reusing existing region files. */
    private void convertKeyspaceInPlace(Path out, Keyspace ks, AtomicLong chunkCount, AtomicLong byteCount)
            throws IOException {
        Files.createDirectories(out);
        Map<Long, List<Long>> byRegion = writeKeyspace(out, ks, chunkCount, byteCount);
        // Staging mode starts from an empty tree, so records absent from the store cannot
        // survive a rebuild; the in-place path reuses the existing .mca files and would
        // otherwise resurrect chunks that were deleted from the store. Zero the slots of
        // every existing region file in the target tree that the store no longer contains,
        // including regions the store has dropped entirely (a shrunken or emptied store).
        pruneSlotsMissingFromStore(out, byRegion);
        cleanStagingSiblings(out);
    }

    /**
     * Best-effort removal of {@code .folesium-staging-*} siblings of {@code out} left
     * behind by an interrupted backup-mode conversion (crash between staging and swap).
     * {@code .folesium-backup-*} trees are left alone (backup-mode lifecycle); failures
     * are ignored (the leftovers are inert).
     */
    /** True when {@code dir} exists and holds no entries. */
    static boolean isEmptyDirectory(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findFirst().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static void cleanStagingSiblings(Path out) {
        Path parent = out.getParent();
        if (parent == null) {
            return;
        }
        String prefix = out.getFileName().toString() + ".folesium-staging-";
        try (Stream<Path> files = Files.list(parent)) {
            files.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(WorldConverter::deleteTreeQuietly);
        } catch (IOException ignored) {
            // best-effort only
        }
    }

    /**
     * In-place exports keep every chunk the store still has and delete the slots of chunks
     * the store dropped since the target .mca was written, mirroring the staging-mode
     * semantics "records absent from the store cannot survive". Every region file already
     * present in the target tree is compared against the keys the store still holds for
     * that region; a region the store holds NO key for at all is KEPT with a warning
     * (never swept) - it may have been skipped as corrupt at import time, and sweeping it
     * would destroy the only surviving copy, breaking the import's re-run-after-repair
     * promise (chunks of a genuinely shrunken store may be resurrected by a later
     * conversion - the accepted cost). Regions are only touched when their .mca exists,
     * so a fresh target tree stays untouched outside the regions
     * {@link #writeKeyspace} just wrote.
     */
    private void pruneSlotsMissingFromStore(Path out, Map<Long, List<Long>> byRegion) throws IOException {
        List<Path> mcaFiles;
        try (var s = Files.list(out)) {
            mcaFiles = s.filter(p -> MCA.matcher(p.getFileName().toString()).matches()).toList();
        }
        runParallel(mcaFiles, mca -> {
            Matcher m = MCA.matcher(mca.getFileName().toString());
            int rx;
            int rz;
            try {
                if (!m.matches()) {
                    return;
                }
                rx = Integer.parseInt(m.group(1));
                rz = Integer.parseInt(m.group(2));
                // Reject coordinates whose region -> chunk shift would overflow int
                // (|region| >= 2^26); such names are corrupt or foreign files. Compare in
                // long so that region == Integer.MIN_VALUE cannot slip past Math.abs
                // (whose overflow keeps the value negative). Mirrors the guard in
                // anvilToFolesium so both directions treat the same files identically.
                long rxL = rx;
                long rzL = rz;
                if (rxL >= (1L << 26) || rxL <= -(1L << 26)
                        || rzL >= (1L << 26) || rzL <= -(1L << 26)) {
                    throw new NumberFormatException("region coordinate out of range");
                }
            } catch (NumberFormatException ex) {
                // Foreign/corrupt name (out-of-range region coordinate); leave it alone.
                return;
            }
            // A target file shorter than the two header sectors is silently re-initialized
            // as an empty region by the writable constructor, discarding its leftover
            // bytes; warn about that data loss before the rebuild, mirroring writeKeyspace.
            if (Files.isRegularFile(mca)) {
                try {
                    long size = Files.size(mca);
                    if (size < 2L * AnvilRegionFile.SECTOR_BYTES) {
                        System.err.println("Folesium: region " + mca.getFileName() + " is truncated ("
                                + size + " bytes, shorter than the 8192-byte Anvil header); re-initializing it"
                                + " as an empty region discards any chunks it still referenced");
                    }
                } catch (IOException ignored) {
                    // Best-effort size probe; the open below reports real failures.
                }
            }
            // The store's keys for this region (at most 1024 = 32x32 chunk slots) are
            // looked up per region instead of being merged into one global set of every
            // stored key: that duplicate set would double the memory footprint of a large
            // keyspace. The linear scan stays bounded by the 1024 slots a region can hold,
            // so it is cheap even for a region full of chunks.
            List<Long> regionKeys = byRegion.get(LongKeys.chunkKey(rx, rz));
            if (regionKeys == null) {
                // The store holds NO key for this region. Sweeping it would destroy the
                // chunks of a region the import skipped as corrupt (the import promises
                // 'the source keeps the data, re-run after a repair'), or of a region
                // that never entered the store. Keep the region and warn; the operator
                // can delete the file manually.
                System.err.println("Folesium: keeping region " + mca.getFileName()
                        + ": the store holds no chunks for it (skipped as corrupt or never"
                        + " imported); delete the file manually to drop it");
                return;
            }
            try (AnvilRegionFile rf = new AnvilRegionFile(mca)) {
                for (int[] xz : rf.listChunks()) {
                    long key = LongKeys.chunkKey((rx << 5) + xz[0], (rz << 5) + xz[1]);
                    if (!regionKeys.contains(key)) {
                        rf.deleteChunk(xz[0], xz[1]);
                    }
                }
                rf.sync();
            } catch (IOException ex) {
                // A single corrupt or foreign region file (unreadable header, truncated
                // payload, ...) must not abort the whole export: skip it and warn. The
                // region is deliberately left untouched - a file that cannot be read
                // reliably is not swept, because a partial sweep could corrupt what is
                // still decodable there.
                System.err.println("Folesium: skipping region " + mca.getFileName()
                        + ": cannot read it, leaving its chunks untouched (" + ex.getMessage() + ")");
            }
        });
    }

    private Map<Long, List<Long>> writeKeyspace(Path writeRoot, Keyspace ks,
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
            // A target file shorter than the two header sectors is silently
            // re-initialized as an empty region by the writable constructor (the
            // read-only open on the source side rejects the same file outright).
            // When the file already exists, that re-init discards its leftover
            // bytes, so warn about the data loss instead of wiping it silently.
            if (Files.isRegularFile(mca)) {
                try {
                    long size = Files.size(mca);
                    if (size < 2L * AnvilRegionFile.SECTOR_BYTES) {
                        System.err.println("Folesium: region " + mca.getFileName() + " is truncated ("
                                + size + " bytes, shorter than the 8192-byte Anvil header); re-initializing it"
                                + " as an empty region discards any chunks it still referenced");
                    }
                } catch (IOException ignored) {
                    // Best-effort size probe; the open below reports real failures.
                }
            }
            AnvilRegionFile opened;
            try {
                opened = new AnvilRegionFile(mca);
            } catch (IOException ex) {
                // A single corrupt target region file (unreadable header) must not
                // abort the whole export: skip it and warn, mirroring
                // pruneSlotsMissingFromStore. Only the open is recoverable this way;
                // a write or sync failure afterwards is a real I/O error and still
                // aborts rather than silently dropping chunks.
                System.err.println("Folesium: skipping region " + mca.getFileName()
                        + ": cannot open it for writing, leaving that region's chunks unexported ("
                        + ex.getMessage() + ")");
                return;
            }
            try (AnvilRegionFile rf = opened) {
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
        return byRegion;
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

    /**
     * Replaces a destination directory while retaining the previous tree as a backup.
     * Returns the {@code .folesium-backup-*} sibling the previous tree was moved to, or
     * {@code null} when the destination did not exist (nothing was moved aside).
     */
    private static Path replaceDirectory(Path destination, Path staging) throws IOException {
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
        return backedUp ? backup : null;
    }

    /**
     * Restores the previous store after a failed backup-mode conversion, mirroring the
     * rollback of {@link #replaceDirectory}: the half-written new store at the canonical
     * path is removed and the backup is moved back into place. The original failure is
     * rethrown by the caller; a restore failure is attached to it as suppressed, with the
     * backup path in the message so the operator can find the retained data. When the
     * initial backup move itself failed, the backup does not exist and the canonical path
     * still holds the intact original, so nothing is deleted or moved.
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
                        "Failed restoring the previous store from " + backup
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
                // A task failed (or was interrupted): cancel the rest and stop waiting
                // promptly. A worker stuck in uninterruptible I/O would otherwise keep
                // the awaitTermination(1, DAYS) retry loop below spinning forever,
                // hanging a server-start conversion flag indefinitely instead of
                // surfacing the failure.
                futures.forEach(f -> f.cancel(true));
                pool.shutdownNow();
                try {
                    pool.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e2) {
                    interrupted = true;
                    Thread.currentThread().interrupt();
                }
            } else {
                pool.shutdown();
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
