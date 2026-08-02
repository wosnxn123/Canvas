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

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.folesium.core.shard.ShardFile;
import dev.folesium.core.util.ZstdNative;

/**
 * Top-level Folesium store for a single Minecraft dimension, or for a world's
 * player data.
 *
 * <p>Layout on disk:</p>
 * <pre>
 * &lt;dir&gt;/folesium.properties       store metadata (format version, role, shard count, compression)
 * &lt;dir&gt;/chunks-0000.flog          append-only shard logs
 * &lt;dir&gt;/chunks-0000.flog.fidx     clean-shutdown index hint (optional, regenerable)
 * ...
 * </pre>
 *
 * <p>Both kinds of store live inside the save directory next to the vanilla data
 * they mirror, exactly like cesium-fabric's {@code chunks.db} / {@code players.db}:</p>
 * <pre>
 * &lt;world&gt;/folesium/                             role=players   (playerdata, advancements, stats)
 * &lt;world&gt;/dimensions/&lt;ns&gt;/&lt;path&gt;/folesium/       role=dimension (chunks, entities, poi)
 * </pre>
 *
 * <p>The {@link StoreRole role} is recorded in the metadata file rather than being
 * inferred from the directory name. Tools therefore never have to guess what a
 * {@code folesium/} directory contains - see {@link #readRole(Path)}.</p>
 *
 * <p>Thread model: fully thread-safe. Keyspaces are created lazily via a
 * {@link ConcurrentHashMap}; per-key mutual exclusion is provided by the owning
 * shard. Folia region threads may call {@link Keyspace#put} concurrently for
 * chunks belonging to different regions with no cross-region blocking.</p>
 */
public final class FolesiumDatabase implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("Folesium");

    public static final String METADATA_FILE = "folesium.properties";
    public static final int STORE_VERSION = 1;

    /** Default store directory name, used both for dimension and player stores. */
    public static final String STORE_DIR_NAME = "folesium";

    public static final String KS_CHUNKS = "chunks";
    public static final String KS_ENTITIES = "entities";
    public static final String KS_POI = "poi";
    public static final String KS_PLAYERDATA = "playerdata";
    public static final String KS_ADVANCEMENTS = "advancements";
    public static final String KS_STATS = "stats";
    public static final String KS_MISC = "misc";

    /**
     * What a store directory holds. Recorded in {@code folesium.properties} as
     * {@code store.role} so that no tool has to infer a store's purpose from its
     * location - a world-root store and a dimension store may both be named
     * {@code folesium/} without any ambiguity.
     */
    public enum StoreRole {
        /** Per-dimension chunk data: {@code chunks}, {@code entities}, {@code poi}. */
        DIMENSION,
        /** Per-world player data: {@code playerdata}, {@code advancements}, {@code stats}. */
        PLAYERS;

        /** Stores written before roles existed are dimension stores. */
        public static final StoreRole LEGACY_DEFAULT = DIMENSION;
    }

    private final Path dir;
    /**
     * Live configuration. Volatile because {@link #applyRuntimeConfig} swaps it while other
     * threads are reading; {@link FolesiumConfig} is an immutable record, so every reader
     * sees a self-consistent snapshot.
     */
    private volatile FolesiumConfig config;
    private final StoreRole role;
    /**
     * {@code true} when the store was opened read-only ({@code applyLayoutChanges == false}):
     * no layout-changing writes are performed and the page index is built in memory only.
     */
    private final boolean readOnly;
    private final Map<String, Keyspace> keyspaces = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Serialises keyspace <em>creation</em> against {@link #applyRuntimeConfig}, so that a
     * keyspace can never be published with a configuration that was already superseded.
     * The hot path ({@link #keyspace} for an existing keyspace) never takes this lock.
     */
    private final Object keyspaceLock = new Object();

    /** Guards {@link #flusher} and doubles as the group-commit thread's wait monitor. */
    private final Object flusherLock = new Object();
    private Thread flusher;

    /**
     * Outcome of a runtime configuration change.
     *
     * @param applied         the configuration now in force
     * @param changes         human-readable {@code "key: old -> new"} entries that took effect
     * @param notes           warnings, e.g. a setting that had to be rejected or clamped
     * @param reshardRequired {@code true} when the requested {@code shardCount} differs from the
     *                        store's physical layout; it is applied by the automatic reshard on
     *                        the next server start, not live
     */
    public record ConfigReloadResult(FolesiumConfig applied, List<String> changes, List<String> notes,
                                     boolean reshardRequired) {
        public ConfigReloadResult {
            changes = List.copyOf(changes);
            notes = List.copyOf(notes);
        }

        public boolean changed() {
            return !changes.isEmpty();
        }
    }

    public static FolesiumDatabase open(Path dir, FolesiumConfig config) {
        return new FolesiumDatabase(dir, config, StoreRole.DIMENSION, true);
    }

    public static FolesiumDatabase open(Path dir) {
        return new FolesiumDatabase(dir, FolesiumConfig.defaults(), StoreRole.DIMENSION, true);
    }

    public static FolesiumDatabase open(Path dir, FolesiumConfig config, StoreRole role) {
        return new FolesiumDatabase(dir, config, role, true);
    }

    /**
     * @param applyLayoutChanges {@code true} (the default) means the requested configuration
     *                           wins over the store's recorded one: a changed {@code shards}
     *                           rewrites the store ({@link StoreResharder}) and a changed
     *                           {@code compression} is recorded as the codec for new writes,
     *                           so editing the configuration actually takes effect.
     *                           <p>{@code false} opens the store exactly as it lies on disk and
     *                           writes no metadata - what tools that only read a store (the
     *                           Folesium -&gt; Anvil export, inspectors) want, since they must not
     *                           rewrite a store they are about to abandon.</p>
     */
    public static FolesiumDatabase open(Path dir, FolesiumConfig config, StoreRole role, boolean applyLayoutChanges) {
        return new FolesiumDatabase(dir, config, role, applyLayoutChanges);
    }

    /**
     * Peeks at the role of the store in {@code dir} without opening it (no shard
     * files are touched, no group-commit thread is started).
     *
     * @return the recorded role, or {@code null} if {@code dir} holds no Folesium store
     */
    public static StoreRole readRole(Path dir) {
        Path meta = dir.resolve(METADATA_FILE);
        if (!Files.isRegularFile(meta)) {
            // A store directory always has metadata; treat anything else as "not a store".
            return null;
        }
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(meta, java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new FolesiumException("Cannot read " + meta, e);
        }
        return parseRole(p.getProperty("store.role"), meta);
    }

    private static StoreRole parseRole(String raw, Path meta) {
        if (raw == null || raw.isBlank()) {
            return StoreRole.LEGACY_DEFAULT;
        }
        try {
            return StoreRole.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FolesiumException("Unknown store.role '" + raw + "' in " + meta);
        }
    }

    private FolesiumDatabase(Path dir, FolesiumConfig requested, StoreRole requestedRole, boolean applyLayoutChanges) {
        this.dir = dir;
        this.role = requestedRole;
        this.readOnly = !applyLayoutChanges;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new FolesiumException("Cannot create store directory " + dir, e);
        }

        // Checked before reconcileMetadata so an unusable codec cannot be recorded in the
        // metadata of a store that then refuses to open.
        if (applyLayoutChanges) {
            requireCompressionUsable(requested.compression());
        }

        // Converge any reshard that a previous run was killed in the middle of, before a
        // single shard file is opened.
        StoreResharder.recover(dir);

        this.config = reconcileMetadata(requested, applyLayoutChanges);

        // Covers the read-as-is path, where the store's own codec wins.
        requireCompressionUsable(config.compression());

        startFlusherIfNeeded();
    }

    private void requireCompressionUsable(FolesiumConfig.Compression compression) {
        if (compression == FolesiumConfig.Compression.ZSTD && !ZstdNative.available()) {
            throw new FolesiumException("Folesium store at " + dir
                    + " is configured for ZSTD compression, but zstd-jni is not available on the classpath. "
                    + "Run on a Folia/Canvas server (which ships zstd-jni) or add com.github.luben:zstd-jni.");
        }
    }

    /**
     * Aligns the on-disk store with the requested configuration.
     *
     * <p>Only the shard count is physical: it routes keys and is stamped into every shard
     * header, so a change is applied by rewriting the store ({@link StoreResharder}) rather
     * than being silently ignored. {@code compression} is recorded for information only -
     * every record stores its own codec, so changing it just changes what new writes use and
     * needs no migration. Everything else lives purely in memory.</p>
     *
     * @return the configuration that actually applies to the store on disk
     */
    private FolesiumConfig reconcileMetadata(FolesiumConfig requested, boolean applyLayoutChanges) {
        Path meta = dir.resolve(METADATA_FILE);
        Properties p = new Properties();
        if (!Files.exists(meta)) {
            p.setProperty("store.version", Integer.toString(STORE_VERSION));
            p.setProperty("store.role", role.name());
            p.setProperty("store.shardCount", Integer.toString(requested.shardCount()));
            p.setProperty("store.compression", requested.compression().name());
            p.setProperty("store.created", Long.toString(System.currentTimeMillis()));
            writeMetadataAtomically(meta, p);
            return requested;
        }

        try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new FolesiumException("Cannot read " + meta, e);
        }
        int version;
        try {
            version = Integer.parseInt(p.getProperty("store.version", "0"));
        } catch (NumberFormatException e) {
            throw new FolesiumException("Corrupt store.version in " + meta
                    + ": '" + p.getProperty("store.version") + "' is not a number", e);
        }
        if (version != STORE_VERSION) {
            throw new FolesiumException("Unsupported Folesium store version " + version + " at " + dir
                    + " (this build supports " + STORE_VERSION + ")");
        }
        StoreRole onDisk = parseRole(p.getProperty("store.role"), meta);
        if (onDisk != role) {
            throw new FolesiumException("Folesium store at " + dir + " holds " + onDisk
                    + " data but was opened as " + role
                    + ". Refusing to mix player data and chunk data in one store.");
        }
        int shards;
        String shardsRaw = p.getProperty("store.shardCount");
        try {
            shards = Integer.parseInt(Objects.requireNonNull(shardsRaw, "store.shardCount").trim());
        } catch (RuntimeException e) {
            throw new FolesiumException("Missing/invalid store.shardCount '" + shardsRaw + "' in " + meta, e);
        }
        if (Integer.bitCount(shards) != 1 || shards < 1 || shards > 1024) {
            throw new FolesiumException("Invalid store.shardCount " + shards + " in " + meta);
        }
        String compRaw = p.getProperty("store.compression");
        FolesiumConfig.Compression comp;
        try {
            comp = FolesiumConfig.Compression.valueOf(
                    Objects.requireNonNull(compRaw, "store.compression").trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw new FolesiumException("Missing/unknown store.compression '" + compRaw + "' in " + meta, e);
        }

        if (!applyLayoutChanges) {
            return requested.withShardCount(shards).withCompression(comp);
        }

        if (comp != requested.compression()) {
            LOGGER.log(System.Logger.Level.INFO,
                    "Folesium: {0} switches compression {1} -> {2}; existing records keep their own codec",
                    dir, comp, requested.compression());
            p.setProperty("store.compression", requested.compression().name());
            writeMetadataAtomically(meta, p);
        }

        if (shards == requested.shardCount()) {
            return requested;
        }
        StoreResharder.reshard(dir, meta, requested.withShardCount(shards), requested.shardCount());
        return requested;
    }

    /** Writes metadata through a forced sibling temporary file and atomic replacement. */
    static void writeMetadataAtomically(Path meta, Properties p) {
        Path temp = null;
        try {
            temp = newTempFile(meta);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Writer writer = new OutputStreamWriter(java.nio.channels.Channels.newOutputStream(channel),
                         StandardCharsets.UTF_8)) {
                p.store(writer, "Folesium store metadata - do not edit while the server is running");
                writer.flush();
                channel.force(true);
            }
            replaceAtomically(temp, meta);
            temp = null;
            fsyncDirectory(meta.getParent());
        } catch (IOException e) {
            throw new FolesiumException("Cannot write " + meta, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Preserve the original write failure.
                }
            }
        }
    }

    /** Writes and forces a small marker using the same replacement protocol as metadata. */
    static void writeAtomically(Path target, String contents) throws IOException {
        Path temp = newTempFile(target);
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Writer writer = new OutputStreamWriter(java.nio.channels.Channels.newOutputStream(channel),
                         StandardCharsets.UTF_8)) {
                writer.write(contents);
                writer.flush();
                channel.force(true);
            }
            replaceAtomically(temp, target);
            moved = true;
            fsyncDirectory(target.getParent());
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static Path newTempFile(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void fsyncDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is unavailable on some Windows filesystems.
        }
    }

    /** Records a runtime compression switch so the next open does not report it again. */
    private void persistCompression(FolesiumConfig.Compression compression) {
        Path meta = dir.resolve(METADATA_FILE);
        Properties p = new Properties();
        if (Files.isRegularFile(meta)) {
            try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                p.load(reader);
            } catch (IOException e) {
                throw new FolesiumException("Cannot read " + meta, e);
            }
        }
        p.setProperty("store.compression", compression.name());
        writeMetadataAtomically(meta, p);
    }

    public Path directory() {
        return dir;
    }

    public FolesiumConfig config() {
        return config;
    }

    /** What this store holds; see {@link StoreRole}. */
    public StoreRole role() {
        return role;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Returns (creating on first use) the named keyspace. */
    public Keyspace keyspace(String name) {
        if (closed.get()) {
            throw new FolesiumException("Database is closed: " + dir);
        }
        Keyspace existing = keyspaces.get(name);
        if (existing != null) {
            return existing;
        }
        // Creation only: serialised against applyRuntimeConfig so that a keyspace built from
        // an about-to-be-replaced config can never escape into the map unconfigured.
        synchronized (keyspaceLock) {
            if (closed.get()) {
                throw new FolesiumException("Database is closed: " + dir);
            }
            return keyspaces.computeIfAbsent(name, n -> new Keyspace(dir, n, config, readOnly));
        }
    }

    /**
     * Applies a new configuration to this live store.
     *
     * <p>Most settings take effect on the very next operation: the shards read the
     * configuration afresh each time, so swapping the (immutable) config object is all
     * that is needed. Concretely:</p>
     * <ul>
     *   <li>{@code compression} / {@code compressionLevel} - used for new writes; existing
     *       records keep the codec stored in their own header, so nothing is migrated.</li>
     *   <li>{@code durability} - the group-commit thread is started or stopped to match.</li>
     *   <li>{@code batchFlushMillis} - the group-commit thread is woken to pick up the
     *       new interval immediately instead of after the old one elapses.</li>
     *   <li>{@code compactRatio} / {@code compactMinBytes} / {@code verifyChecksums} /
     *       {@code workloadCompaction} / {@code compactIoLimit} - read per operation (the
     *       last two by {@link #compactIfNeeded()}).</li>
     *   <li>{@code shardCount} - physical; recorded as pending and applied by the automatic
     *       reshard on the next store open. The live store keeps its current layout.</li>
     *   <li>{@code indexCacheBytes} / {@code indexMode} / {@code dictionaryCompression} -
     *       read only when a keyspace is opened, so they take effect on the next store
     *       open; a change is reported in {@code notes} instead of being applied.</li>
     *   <li>{@code backupOnConvert} - read only by the converters, so it takes effect on
     *       the next conversion; a change is reported in {@code notes} instead of being
     *       applied.</li>
     * </ul>
     *
     * @return what changed, plus any setting that had to be rejected or deferred
     */
    public ConfigReloadResult applyRuntimeConfig(FolesiumConfig next) {
        Objects.requireNonNull(next, "next");
        FolesiumConfig requestedBaseline = this.config;
        ConfigReloadResult result;
        synchronized (keyspaceLock) {
            if (closed.get()) {
                throw new FolesiumException("Database is closed: " + dir);
            }
            List<String> notes = new ArrayList<>();
            FolesiumConfig current = this.config;
            FolesiumConfig merged = mergeRuntimeConfig(current, requestedBaseline, next);
            boolean reshardRequired = next.shardCount() != requestedBaseline.shardCount();
            FolesiumConfig effective = merged.withShardCount(current.shardCount());

            if (effective.compression() == FolesiumConfig.Compression.ZSTD && !ZstdNative.available()) {
                notes.add("compression=ZSTD ignored: zstd-jni is not available; keeping " + current.compression());
                effective = effective.withCompression(current.compression())
                        .withCompressionLevel(FolesiumConfig.clampCompressionLevel(
                                current.compression(), effective.compressionLevel()));
            }
            if (reshardRequired) {
                notes.add("shards=" + next.shardCount() + " will be applied by an automatic reshard on the next"
                        + " server start (currently " + current.shardCount() + ")");
            }
            // Open-only settings: the engine does not re-read them on a live store, so they cannot
            // be applied here. Report them instead of silently dropping them (same treatment as
            // shards above); a changed value is picked up on the next store open / conversion.
            if (next.indexCacheBytes() != requestedBaseline.indexCacheBytes()) {
                notes.add("indexCacheBytes=" + next.indexCacheBytes()
                        + " takes effect on next store open (currently " + current.indexCacheBytes() + ")");
            }
            if (next.indexMode() != requestedBaseline.indexMode()) {
                notes.add("indexMode=" + next.indexMode()
                        + " takes effect on next store open (currently " + current.indexMode() + ")");
            }
            if (next.dictionaryCompression() != requestedBaseline.dictionaryCompression()) {
                notes.add("dictionaryCompression=" + next.dictionaryCompression()
                        + " takes effect on next store open (currently " + current.dictionaryCompression() + ")");
            }
            if (next.backupOnConvert() != requestedBaseline.backupOnConvert()) {
                notes.add("backupOnConvert=" + next.backupOnConvert()
                        + " is read by the converters and takes effect on the next conversion"
                        + " (currently " + current.backupOnConvert() + ")");
            }

            List<String> changes = current.diff(effective);
            if (changes.isEmpty()) {
                return new ConfigReloadResult(current, changes, notes, reshardRequired);
            }
            if (effective.compression() != current.compression()) {
                persistCompression(effective.compression());
            }
            this.config = effective;
            for (Keyspace ks : keyspaces.values()) {
                ks.applyRuntimeConfig(effective);
            }
            result = new ConfigReloadResult(effective, changes, notes, reshardRequired);
        }

        if (result.applied().durability() == FolesiumConfig.DurabilityMode.BATCH) {
            startFlusherIfNeeded();
            synchronized (flusherLock) {
                flusherLock.notifyAll();
            }
        } else {
            stopFlusher();
            try {
                flush();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Folesium: flush during durability change failed for " + dir, e);
            }
        }
        LOGGER.log(System.Logger.Level.INFO, "Folesium: {0} reconfigured - {1}",
                dir, String.join(", ", result.changes()));
        return result;
    }

    /** Merges only fields changed by this caller from its pre-lock snapshot. */
    private static FolesiumConfig mergeRuntimeConfig(FolesiumConfig current,
                                                       FolesiumConfig baseline,
                                                       FolesiumConfig next) {
        FolesiumConfig merged = current;
        if (next.durability() != baseline.durability()) {
            merged = merged.withDurability(next.durability());
        }
        if (next.batchFlushMillis() != baseline.batchFlushMillis()) {
            merged = merged.withBatchFlushMillis(next.batchFlushMillis());
        }
        if (next.compression() != baseline.compression()) {
            merged = merged.withCompression(next.compression());
        }
        if (next.compressionLevel() != baseline.compressionLevel()) {
            merged = merged.withCompressionLevel(next.compressionLevel());
        }
        if (Double.compare(next.compactRatio(), baseline.compactRatio()) != 0) {
            merged = merged.withCompactRatio(next.compactRatio());
        }
        if (next.compactMinBytes() != baseline.compactMinBytes()) {
            merged = merged.withCompactMinBytes(next.compactMinBytes());
        }
        if (next.verifyChecksums() != baseline.verifyChecksums()) {
            merged = merged.withVerifyChecksums(next.verifyChecksums());
        }
        if (next.workloadCompaction() != baseline.workloadCompaction()) {
            merged = merged.withWorkloadCompaction(next.workloadCompaction());
        }
        if (next.compactIoLimit() != baseline.compactIoLimit()) {
            merged = merged.withCompactIoLimit(next.compactIoLimit());
        }
        return merged;
    }

    public Keyspace chunks() {
        return keyspace(KS_CHUNKS);
    }

    public Keyspace entities() {
        return keyspace(KS_ENTITIES);
    }

    public Keyspace poi() {
        return keyspace(KS_POI);
    }

    public Keyspace playerData() {
        return keyspace(KS_PLAYERDATA);
    }

    public Keyspace advancements() {
        return keyspace(KS_ADVANCEMENTS);
    }

    public Keyspace stats() {
        return keyspace(KS_STATS);
    }

    public Map<String, Keyspace> openKeyspaces() {
        return Map.copyOf(keyspaces);
    }

    /** fsyncs every dirty shard of every open keyspace, then persists shard watermarks. */
    public void flush() {
        if (closed.get()) {
            return; // close() already forced every shard; nothing left to flush
        }
        for (Keyspace ks : keyspaces.values()) {
            ks.flush();
            // After the log force and page flush: persist the shard watermarks so a crash
            // never leaves the on-disk pages ahead of what the recovery anchor claims.
            ks.flushWatermarks();
        }
    }

    public void compactIfNeeded() {
        if (closed.get()) {
            return; // do not touch shards that close() is tearing down
        }
        // Collect every shard that needs a rewrite first, so the pass can be ordered
        // (workload mode) and rate-limited (compactIoLimit) without interleaving the
        // scheduling decisions with the rewrites. With workloadCompaction disabled the
        // collection preserves the historical iteration order: keyspaces in map order,
        // shards in routing order, each compacted in the order its need was observed.
        List<ShardFile> candidates = new ArrayList<>();
        for (Keyspace ks : keyspaces.values()) {
            for (ShardFile s : ks.shards()) {
                if (s.needsCompaction()) {
                    candidates.add(s);
                }
            }
        }
        if (config.workloadCompaction()) {
            // Workload compaction (DumpKV/ArceKV-style): reclaim the dead, write-hot
            // shards first instead of following iteration order, so the rewrite budget
            // is spent where it buys the most read/write amplification relief.
            candidates.sort(Comparator.comparingDouble(ShardFile::compactionPriority).reversed());
        }
        long ioLimit = config.compactIoLimit();
        long bytesSinceSleep = 0;
        for (ShardFile s : candidates) {
            s.compact();
            // compactIoLimit: cap compaction I/O near `limit` bytes/second. Simple
            // version - accumulate the post-compaction size of every shard rewritten in
            // this pass and, once the accumulated budget crosses 1 ms of I/O time
            // (bytes / limit * 1000 ms), sleep it off. limit <= 0 means unlimited.
            if (ioLimit > 0) {
                bytesSinceSleep += s.sizeBytes();
                long sleepMillis = bytesSinceSleep * 1000 / ioLimit;
                if (sleepMillis > 0) {
                    sleepQuietly(sleepMillis);
                    bytesSinceSleep = 0;
                }
            }
        }
    }

    /** Sleeps for {@code millis}, restoring the interrupt flag so the pass keeps going. */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** How often a store looks at its shards to decide whether any of them needs compacting. */
    private static final long COMPACT_CHECK_INTERVAL_NANOS = 5L * 60 * 1_000_000_000L;

    private final java.util.concurrent.atomic.AtomicLong lastCompactCheck =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    /**
     * {@link #compactIfNeeded()}, rate-limited to once every five minutes per store.
     *
     * <p>Compaction has to be driven by <em>something</em>: an append-only log that is never
     * compacted grows without bound and read amplification grows with it. Rather than relying
     * on the host server to call it (nothing did), the engine drives it itself from the
     * group-commit loop and from {@link FolesiumRegistry#flushAll()}. The check itself is a
     * per-shard read lock plus two comparisons, and the thresholds
     * ({@code compactMinBytes} / {@code compactRatio}) mean a rewrite only happens for shards
     * that really are mostly dead bytes.</p>
     *
     * @return {@code true} if the check ran (not that anything was compacted)
     */
    public boolean compactIfNeededThrottled() {
        if (closed.get()) {
            return false;
        }
        long now = System.nanoTime();
        long previous = lastCompactCheck.get();
        if (now - previous < COMPACT_CHECK_INTERVAL_NANOS || !lastCompactCheck.compareAndSet(previous, now)) {
            return false;
        }
        compactIfNeeded();
        return true;
    }

    /** Starts the group-commit thread if {@code durability == BATCH} and none is running. */
    private void startFlusherIfNeeded() {
        synchronized (flusherLock) {
            if (closed.get() || flusher != null
                    || config.durability() != FolesiumConfig.DurabilityMode.BATCH) {
                return;
            }
            Thread t = Thread.ofPlatform().daemon()
                    .name("folesium-groupcommit-" + dir.getFileName())
                    .unstarted(this::flushLoop);
            this.flusher = t;
            t.start();
        }
    }

    /**
     * Stops the group-commit thread and waits for it to finish its current cycle.
     *
     * <p>It is woken through the monitor rather than interrupted, because an interrupt during
     * {@code FileChannel.force} closes the channel ({@link java.nio.channels.ClosedByInterruptException}).
     * Interruption is kept only as a last resort if the thread does not come back in time.</p>
     */
    private void stopFlusher() {
        Thread t;
        synchronized (flusherLock) {
            t = flusher;
            flusher = null;
            flusherLock.notifyAll();
        }
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(5000);
            if (t.isAlive()) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: group-commit thread for {0} did not stop in time; interrupting", dir);
                t.interrupt();
                t.join(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void flushLoop() {
        try {
            while (true) {
                FolesiumConfig snapshot = config;
                synchronized (flusherLock) {
                    // Either the store is closing or this thread has been superseded/retired.
                    if (closed.get() || flusher != Thread.currentThread()
                            || snapshot.durability() != FolesiumConfig.DurabilityMode.BATCH) {
                        return;
                    }
                    try {
                        flusherLock.wait(Math.max(1, snapshot.batchFlushMillis()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (closed.get() || flusher != Thread.currentThread()) {
                        return;
                    }
                }
                if (config.durability() != FolesiumConfig.DurabilityMode.BATCH) {
                    // Switched away from BATCH while we slept; stopFlusher()/applyRuntimeConfig
                    // takes care of the final flush.
                    return;
                }
                try {
                    flush();
                    // Off the region threads and rate-limited: this is the only thing that
                    // keeps an append-only store from growing without bound.
                    compactIfNeededThrottled();
                } catch (RuntimeException e) {
                    LOGGER.log(System.Logger.Level.ERROR, "Folesium group-commit failed for " + dir, e);
                }
            }
        } finally {
            synchronized (flusherLock) {
                if (flusher == Thread.currentThread()) {
                    flusher = null;
                }
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopFlusher();
        FolesiumException first = null;
        synchronized (keyspaceLock) {
            for (var entry : keyspaces.entrySet()) {
                try {
                    entry.getValue().close();
                    keyspaces.remove(entry.getKey(), entry.getValue());
                } catch (RuntimeException e) {
                    if (first == null) {
                        first = e instanceof FolesiumException fe
                                ? fe : new FolesiumException("Cannot close keyspace " + entry.getKey(), e);
                    } else {
                        first.addSuppressed(e);
                    }
                }
            }
            if (first != null) {
                closed.set(false);
            }
        }
        if (first != null) {
            if (config.durability() == FolesiumConfig.DurabilityMode.BATCH) {
                startFlusherIfNeeded();
            }
            throw first;
        }
    }
}
