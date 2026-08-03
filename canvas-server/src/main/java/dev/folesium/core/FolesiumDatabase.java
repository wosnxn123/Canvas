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
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
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
    /**
     * The group-commit thread, or {@code null} when group commit is not running.
     * Volatile because the compaction pass re-reads it without {@link #flusherLock} at
     * each per-iteration checkpoint (and {@link #flushLoop} reads it once at loop start);
     * every write happens under the lock, so a volatile read always sees the latest
     * retirement decision.
     */
    private volatile Thread flusher;

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

    /**
     * Reconciles a requested configuration to the on-disk layout of an existing store, for
     * callers that open a store WRITABLE without intending layout changes (the converter's
     * merge import): {@link #reconcileMetadata} would otherwise reshard the store to the
     * requested shard count and rewrite the compression field whenever they differ. The CLI
     * resolves its configuration from the working directory only, so its auto-tuned shard
     * count can easily differ from the server's - without alignment every routine merge
     * conversion would silently rewrite the whole store (multi-GB worlds appear hung), drop
     * the page index, and ping-pong if the server later reshards back. No-op when the
     * directory is not a store yet (a first conversion creates it with the request).
     * A corrupt or unreadable metadata file fails loudly here, exactly as open() would.
     */
    public static FolesiumConfig alignToDiskLayout(Path dir, FolesiumConfig requested) {
        Path meta = dir.resolve(METADATA_FILE);
        if (!Files.isRegularFile(meta)) {
            return requested;
        }
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(meta, java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new FolesiumException("Cannot read " + meta, e);
        }
        int shards;
        try {
            shards = Integer.parseInt(p.getProperty("store.shardCount", "").trim());
        } catch (RuntimeException e) {
            throw new FolesiumException("Missing/invalid store.shardCount in " + meta, e);
        }
        FolesiumConfig.Compression comp;
        try {
            comp = FolesiumConfig.Compression.valueOf(
                    p.getProperty("store.compression", "").trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw new FolesiumException("Missing/unknown store.compression in " + meta, e);
        }
        return requested.withShardCount(shards).withCompression(comp);
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

        if (!readOnly) {
            // Converge any reshard that a previous run was killed in the middle of, before a
            // single shard file is opened. Skipped for read-only opens: recover() deletes
            // staging/backup trees, moves shard files into place and rewrites metadata - a
            // converter/export (applyLayoutChanges == false) must never rewrite or delete a
            // store it is about to abandon. A read-only open instead reads the interrupted
            // layout exactly as it lies: missing, torn or partial shards surface as absent
            // data, which is what a read-only export needs. A consumer that requires
            // recover()'s full-layout consistency must open the store writable.
            StoreResharder.recover(dir);
        }

        this.config = reconcileMetadata(requested, applyLayoutChanges);

        // Covers the read-as-is path, where the store's own codec wins (reconcileMetadata
        // keeps the on-disk codec for read-only opens). Skipped when the directory holds no
        // metadata: there is no store and therefore no record to decode, so a read-only
        // export pointed at an arbitrary path with a requested codec this environment
        // cannot decode (e.g. ZSTD_DICT without the zstd-jni dictionary API) must not
        // abort. A read-only open never writes, so an on-disk codec this environment
        // cannot decode is not fatal either: records encoded with it surface as per-record
        // decode errors with the normal fallback, and an export/inspection must not be
        // locked out by a disk codec intent it can never satisfy. Writable opens are
        // already covered by the upfront requested-codec check plus the on-disk-codec
        // check in reconcileMetadata, so only the read-only path needs this non-fatal
        // disk-codec check.
        if (readOnly && Files.isRegularFile(dir.resolve(METADATA_FILE))) {
            warnCompressionUnusable(dir, config.compression());
        }

        startFlusherIfNeeded();
    }

    /**
     * Why {@code compression} cannot be used in this environment, or {@code null} when it can.
     *
     * <p>ZSTD_DICT needs the zstd-jni dictionary API on top of plain ZSTD, so each codec is
     * probed with its own gate: ZSTD against {@link ZstdNative#available()}, ZSTD_DICT against
     * {@link ZstdNative#dictAvailable()} (which also covers the library being entirely absent).</p>
     */
    private static String compressionUnusableReason(FolesiumConfig.Compression compression) {
        if ((compression == FolesiumConfig.Compression.ZSTD && !ZstdNative.available())
                || (compression == FolesiumConfig.Compression.ZSTD_DICT && !ZstdNative.dictAvailable())) {
            return compression == FolesiumConfig.Compression.ZSTD_DICT
                    ? "the zstd-jni dictionary API is not available"
                    : "zstd-jni is not available";
        }
        return null;
    }

    private void requireCompressionUsable(FolesiumConfig.Compression compression) {
        String reason = compressionUnusableReason(compression);
        if (reason == null) {
            return;
        }
        String remedy = compression == FolesiumConfig.Compression.ZSTD_DICT
                ? "Run on a Folia/Canvas server with zstd-jni >= 1.5.x (which ships the dictionary API)"
                        + " or add com.github.luben:zstd-jni."
                : "Run on a Folia/Canvas server (which ships zstd-jni) or add com.github.luben:zstd-jni.";
        throw new FolesiumException("Folesium store at " + dir + " is configured for "
                + compression + " compression, but " + reason + ". " + remedy);
    }

    /** Logs a warning (never throws) when {@code compression} is unusable in this environment. */
    private static void warnCompressionUnusable(Path storeDir, FolesiumConfig.Compression compression) {
        String reason = compressionUnusableReason(compression);
        if (reason == null) {
            return;
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Folesium: store at {0} records {1} compression, but {2}; records encoded with it"
                        + " will fail to decode per record - proceeding without it (a read-only open"
                        + " never writes, so the on-disk codec intent must not lock out an"
                        + " export/inspection)",
                storeDir, compression, reason);
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
            if (!applyLayoutChanges) {
                // A read-only open must never materialize metadata on a directory that
                // is not (yet) a store: exporters and inspectors may be pointed at an
                // arbitrary path, and creating folesium.properties + shard files there
                // would silently turn it into one.
                return requested;
            }
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
            // Existing records keep the on-disk codec, so the switch itself needs no
            // migration and is always safe to allow. When the on-disk codec is usable
            // here (e.g. switching a ZSTD_DICT store away on a server that has the
            // zstd-jni dictionary API) the old records stay decodable. When it is not
            // (e.g. the store records ZSTD_DICT but this environment lacks the zstd-jni
            // dictionary API), the old records are already unreadable - that is the
            // degraded-environment reality - so switching to the requested (usable,
            // pre-checked) codec only improves the store, and refusing would lock a
            // writable open out of a store whose records it cannot decode anyway. The
            // only action, then, is a warning naming the disk codec this environment
            // cannot satisfy.
            String diskReason = compressionUnusableReason(comp);
            if (diskReason != null) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: {0} records {1} compression, but {2}; those records are already"
                                + " undecodable here, allowing the switch to {3} anyway",
                        dir, comp, diskReason, requested.compression());
            }
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

    /**
     * Records a runtime compression switch so the next open does not report it again.
     *
     * @return {@code true} when the codec was actually written to the metadata,
     *         {@code false} when the persist was skipped (no metadata file - see below);
     *         the caller must not treat a skipped persist as if the disk now records the
     *         codec
     */
    private boolean persistCompression(FolesiumConfig.Compression compression) {
        Path meta = dir.resolve(METADATA_FILE);
        if (!Files.isRegularFile(meta)) {
            // No metadata file: writing one now would create an incomplete properties file
            // (store.compression only - the open path also requires store.version, store.role
            // and store.shardCount), which the next open would reject and thereby lock the
            // store. Skip the persist; diskCompression() keeps reporting null, so the persist
            // gate in applyRuntimeConfig simply retries once the metadata exists.
            return false;
        }
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new FolesiumException("Cannot read " + meta, e);
        }
        p.setProperty("store.compression", compression.name());
        writeMetadataAtomically(meta, p);
        return true;
    }

    /**
     * The codec currently recorded in this store's metadata, or {@code null} when the
     * metadata file does not exist or records no codec. Compared against the requested
     * codec in {@link #applyRuntimeConfig}'s persist gate to keep the persist idempotent
     * (skip the fsync-rewrite when the disk already records the same value); the metadata
     * is written at open, so {@code null} is a corrupt/foreign-directory case, not a
     * normal state.
     */
    private FolesiumConfig.Compression diskCompression() {
        Path meta = dir.resolve(METADATA_FILE);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new FolesiumException("Cannot read " + meta, e);
        }
        String raw = p.getProperty("store.compression");
        if (raw == null) {
            return null;
        }
        try {
            return FolesiumConfig.Compression.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw new FolesiumException("Unknown store.compression '" + raw + "' in " + meta, e);
        }
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
     *       last two by {@link #compactIfNeeded(boolean)}).</li>
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
        // A read-only store must never be reconfigured: persisting a changed compression
        // would materialize metadata (and with it a shard layout) on a directory that is
        // not (yet) a store, silently corrupting an arbitrary export path into one. Read-only
        // opens are for exporters/inspectors that keep the on-disk layout exactly as it lies.
        if (readOnly) {
            throw new FolesiumException("Cannot reconfigure read-only store " + dir + ": store is read-only");
        }
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
            // The codec this reload resolved to before any session-only degradation below.
            // The persist gate records this requested codec (never the degraded fallback),
            // so the on-disk codec stays the persistence of the operator's intent: a
            // degradation is a session-only accommodation and must not leak into the
            // metadata, which is what lets the next open re-evaluate with the operator's
            // codec instead of inheriting a fallback it can never correct.
            FolesiumConfig.Compression requestedCodec = effective.compression();

            if ((effective.compression() == FolesiumConfig.Compression.ZSTD && !ZstdNative.available())
                    || (effective.compression() == FolesiumConfig.Compression.ZSTD_DICT
                            && !ZstdNative.dictAvailable())) {
                // Degradation is a session-only accommodation: the fallback (the codec
                // `effective` is rewritten to below) is never persisted - the persist gate
                // further down records `requestedCodec` instead, so the metadata keeps the
                // operator's requested codec and the next open re-evaluates (the note
                // explains what actually happened).
                notes.add("compression=" + effective.compression() + " ignored: "
                        + (effective.compression() == FolesiumConfig.Compression.ZSTD_DICT
                                ? "the zstd-jni dictionary API is not available"
                                : "zstd-jni is not available")
                        + "; keeping " + current.compression());
                effective = effective.withCompression(current.compression())
                        .withCompressionLevel(FolesiumConfig.clampCompressionLevel(
                                current.compression(), effective.compressionLevel()));
            }
            // ZSTD_DICT additionally needs a trained per-keyspace dictionary. Dictionaries are
            // minted by the conversion pipeline at the end of a conversion - never on store open -
            // so a live store can be asked for ZSTD_DICT while one or more open keyspaces have no
            // dict.bin (e.g. the players keyspace, which is not region-keyed, or a store that never
            // ran a conversion). New writes to such a keyspace would otherwise fail record by record
            // in ShardFile, so degrade the config-level codec to ZSTD. withCompression(ZSTD) keeps
            // dictionaryCompression unchanged (it only forces the flag on for ZSTD_DICT), which is
            // intentional: keyspaces that do have a dictionary keep writing codec 3 via ShardFile's
            // dictionary gate, and the dict-less ones fall back to plain ZSTD there.
            if (effective.compression() == FolesiumConfig.Compression.ZSTD_DICT) {
                boolean missingDictionary = false;
                for (Keyspace ks : keyspaces.values()) {
                    if (ks.keyspaceDict() == null) {
                        missingDictionary = true;
                        break;
                    }
                }
                if (missingDictionary) {
                    notes.add("compression=ZSTD_DICT ignored: no per-keyspace dictionary exists; "
                            + "using ZSTD for this session (dictionaryCompression stays enabled, so "
                            + "keyspaces that have a dictionary keep writing codec 3)");
                    effective = effective.withCompression(FolesiumConfig.Compression.ZSTD)
                            .withCompressionLevel(FolesiumConfig.clampCompressionLevel(
                                    FolesiumConfig.Compression.ZSTD, effective.compressionLevel()));
                }
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
            // Persist gate: the on-disk codec is the persistence of the operator's intent,
            // so a reload records a codec only when it actually made a codec decision:
            // this reload changed the codec (`next.compression()` differs from the reload
            // baseline - i.e. `changes` contains a compression entry), the requested
            // codec is usable in this environment, and the disk does not already record it
            // (`requestedCodec != diskCompression()` keeps the persist idempotent: in a
            // degraded session - the disk already carries the intent an earlier reload
            // persisted, while the session runs the fallback - an unrelated later reload
            // would otherwise fsync-rewrite byte-identical metadata on every poll). A
            // degradation is a session-only accommodation and is never persisted, and an
            // unrelated reload (e.g. a durability tweak) must not rewrite an intent
            // recorded by an earlier reload. When a codec is recorded it is the
            // pre-degradation `requestedCodec` (never the fallback), so the metadata
            // carries the operator's intent and the next open re-evaluates - exactly what
            // the degradation note promises. An operator who explicitly switches to the
            // codec a previous reload degraded to (attribute zstd == this session's ZSTD)
            // produces no compression change and is therefore not persisted either:
            // acceptable - the next open's reconcileMetadata corrects the disk codec from
            // the attribute (the degraded value was never recorded, so the disk still
            // carries the pre-degradation intent, and the attribute now agrees with the
            // session fallback).
            boolean persistedCodec = false;
            if (next.compression() != requestedBaseline.compression()
                    && compressionUnusableReason(requestedCodec) == null
                    && requestedCodec != diskCompression()) {
                // Only claim the codec was persisted when the persist actually wrote it: a
                // skipped persist (no metadata file) must not suppress the no-change early
                // return below as if the disk now recorded the intent.
                persistedCodec = persistCompression(requestedCodec);
            }
            if (changes.isEmpty() && !persistedCodec) {
                return new ConfigReloadResult(current, changes, notes, reshardRequired);
            }
            this.config = effective;
            for (Keyspace ks : keyspaces.values()) {
                ks.applyRuntimeConfig(effective);
            }
            result = new ConfigReloadResult(effective, changes, notes, reshardRequired);
        }

        // Post-lock reconciliation of the group-commit thread against the FINAL config.
        // A concurrent reload may have flipped durability (BATCH <-> EXPLICIT) after this
        // reload computed `result`, and reconciling from our own result could retire the
        // thread while the final config is still BATCH - fsync would then silently stop
        // until the next reload, risking data loss on a crash. The decision and the field
        // mutation are one atomic step under flusherLock; joining a retired thread happens
        // outside the lock so the woken thread can reacquire the monitor and exit. A failed
        // flush is not swallowed: the reload aborts loudly (the caller records the
        // RuntimeException) instead of silently degrading durability.
        FolesiumConfig.DurabilityMode finalDurability;
        Thread retired;
        synchronized (flusherLock) {
            finalDurability = config.durability();
            if (finalDurability == FolesiumConfig.DurabilityMode.BATCH) {
                startFlusherIfNeeded();
                flusherLock.notifyAll();
                retired = null;
            } else {
                retired = flusher;
                flusher = null;
                flusherLock.notifyAll();
            }
        }
        if (retired != null && retired != Thread.currentThread()) {
            awaitFlusherExit(retired);
        }
        if (finalDurability != FolesiumConfig.DurabilityMode.BATCH) {
            try {
                flush();
            } catch (RuntimeException e) {
                // The retirement's last-resort interrupt can land on the retired
                // thread's in-flight force and close a shard channel; the retired
                // thread's own ERROR path reopens it, but if it is still blocked on a
                // shard lock this flush may win the lock first and see the closed
                // channel (flushIfDirty throws for exactly that state). Reopen and
                // retry once before failing the whole durability change.
                if (isChannelClosedFailure(e) && !closed.get()) {
                    try {
                        reopenInterruptClosedShards();
                        flush();
                    } catch (RuntimeException retryFailure) {
                        throw new FolesiumException("Cannot flush " + dir
                                + " during durability change", retryFailure);
                    }
                } else {
                    throw new FolesiumException("Cannot flush " + dir + " during durability change", e);
                }
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
            // Clamp against the codec that actually won the merge: under concurrent reloads the
            // raw next level may be out of range for the current codec, and withCompressionLevel
            // would throw IllegalArgumentException for it. mergeRuntimeConfig is called under the
            // lock, but the codec itself may still have been changed by another reload in between.
            merged = merged.withCompressionLevel(
                    FolesiumConfig.clampCompressionLevel(merged.compression(), next.compressionLevel()));
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

    /**
     * Rewrites every shard that currently needs it (see {@link ShardFile#needsCompaction()}),
     * ordered by workload priority and rate-limited by {@code compactIoLimit}.
     *
     * @param callerIsFlusher whether the calling thread is the {@link #flusher} - a
     *                        flusher-class caller is one whose retirement must stop the
     *                        pass (see the per-iteration checkpoint below), while a
     *                        non-flusher caller (the server save thread via
     *                        {@link FolesiumRegistry#flushAll()}) legitimately runs a
     *                        pass on its own thread and must never abort on retirement.
     *                        The classification is captured by the caller, never sampled
     *                        here: the group-commit thread captures it once at
     *                        {@link #flushLoop()} start (its identity is stable for its
     *                        whole lifetime), so a retirement landing between the loop's
     *                        checkpoint and this pass cannot misclassify a retired
     *                        flusher as a non-flusher caller.
     * @return {@code true} when the pass ran to completion, {@code false} when it aborted
     *         early at the retirement/closed checkpoint (or was never started because the
     *         store is closed) - the caller ({@link #compactIfNeededThrottled(boolean)})
     *         treats an aborted pass as if no check ran, so the next driver retries
     *         immediately instead of burning the five-minute throttle on a pass that
     *         stopped early
     */
    public boolean compactIfNeeded(boolean callerIsFlusher) {
        if (closed.get()) {
            return false; // do not touch shards that close() is tearing down
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
        // The caller's flusher classification arrived as a parameter (see the javadoc): a
        // flusher-class caller is one whose retirement must stop the pass, while each
        // per-iteration checkpoint below re-reads the volatile flusher field, so the "still
        // the flusher" decision is always made against the latest state. A non-flusher
        // caller (the server save thread via {@link FolesiumRegistry#flushAll()}, which
        // also drives {@link #compactIfNeededThrottled(boolean)}) legitimately runs this
        // pass on its own thread and is never the flusher, so the identity comparison
        // stays false for it and never aborts the pass.
        for (ShardFile s : candidates) {
            // Retirement/closed checkpoint on every iteration, BEFORE the next compact(): a
            // store that was closed (close() is tearing shards down) must not keep compacting,
            // and a group-commit thread that was retired/replaced in the meantime (durability
            // switched away from BATCH, or a new flusher owns the loop now) must stop too - but
            // only when the caller IS the flusher: `flusher != Thread.currentThread()` is always
            // true for a non-flusher caller, so the identity comparison is skipped for them.
            // The check sits OUTSIDE the rate-limit block so it runs on every iteration even
            // when compactIoLimit is 0 (unlimited - the default): the old placement inside the
            // `sleepMillis > 0` branch was unreachable with the default configuration, so a
            // store closed during an unlimited pass kept compacting into channels close() was
            // closing. The flusher field is volatile (all writes are under flusherLock), so this
            // re-read at every iteration always sees the latest retirement decision. Abort the
            // pass here - before compact(), whose channel I/O may hit channels close() is
            // closing.
            if (closed.get() || (callerIsFlusher && flusher != Thread.currentThread())) {
                // Aborted early: a store that was closed (close() is tearing shards down) or a
                // flusher that was retired/replaced must not keep compacting. Report the early
                // abort so compactIfNeededThrottled() can retry immediately once the store is
                // healthy again instead of waiting out the whole throttle interval.
                return false;
            }
            s.compact();
            // compactIoLimit: cap compaction I/O near `limit` bytes/second. Simple
            // version - accumulate the post-compaction size of every shard rewritten in
            // this pass and, once the accumulated budget crosses 1 ms of I/O time
            // (bytes / limit * 1000 ms), sleep it off. limit <= 0 means unlimited.
            if (ioLimit > 0) {
                bytesSinceSleep += s.sizeBytes();
                long sleepMillis = bytesSinceSleep * 1000 / ioLimit;
                if (sleepMillis > 0) {
                    // Rate-limit sleep; abort the pass when the sleep was interrupted (a
                    // retirement/close wakeup - the only interrupts this thread receives) or
                    // when the store was closed / this flusher was retired while we slept,
                    // so a throttled pass never lingers through a long sleep beside a
                    // closed store or a replacement flusher (zombie flusher elimination).
                    if (!sleepQuietly(sleepMillis)
                            || closed.get()
                            || (callerIsFlusher && flusher != Thread.currentThread())) {
                        return false;
                    }
                    bytesSinceSleep = 0;
                }
            }
        }
        return true;
    }

    /**
     * Sleeps for {@code millis} as a compaction rate-limiter. An interrupt during the sleep
     * is restored as a pending flag via {@link Thread#interrupt()} so the caller's
     * interrupted status is not silently dropped, and is reported to the caller.
     *
     * @return {@code true} when the sleep ran to completion, {@code false} when it was
     *         interrupted - the caller treats an interrupted sleep like the closed/retired
     *         checkpoint and aborts the pass, so a retirement wakeup is not swallowed into
     *         further compaction
     */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** How often a store looks at its shards to decide whether any of them needs compacting. */
    private static final long COMPACT_CHECK_INTERVAL_NANOS = 5L * 60 * 1_000_000_000L;

    private final java.util.concurrent.atomic.AtomicLong lastCompactCheck =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    /**
     * Whether the previous {@link #compactIfNeededThrottled(boolean)} attempt ended in an
     * exception. Drives the conditional throttle reset in the exception path: a fresh
     * failure (the previous attempt did not throw) resets the slot so the next driver
     * retries immediately, while a failure directly after another failure skips the reset
     * so the five-minute interval bounds a persistently failing pass instead of retrying
     * it on every group-commit wakeup. Volatile: written by whichever driver attempted
     * last, read by the next.
     */
    private volatile boolean lastCompactAttemptThrew;

    /**
     * {@link #compactIfNeeded(boolean)}, rate-limited to once every five minutes per store.
     *
     * <p>Compaction has to be driven by <em>something</em>: an append-only log that is never
     * compacted grows without bound and read amplification grows with it. Rather than relying
     * on the host server to call it (nothing did), the engine drives it itself from the
     * group-commit loop and from {@link FolesiumRegistry#flushAll()}. The check itself is a
     * per-shard read lock plus two comparisons, and the thresholds
     * ({@code compactMinBytes} / {@code compactRatio}) mean a rewrite only happens for shards
     * that really are mostly dead bytes.</p>
     *
     * @param callerIsFlusher the caller's flusher classification, forwarded to
     *                        {@link #compactIfNeeded(boolean)} - see its javadoc
     * @return {@code true} if the check ran (not that anything was compacted)
     */
    public boolean compactIfNeededThrottled(boolean callerIsFlusher) {
        if (closed.get()) {
            return false;
        }
        long now = System.nanoTime();
        long previous = lastCompactCheck.get();
        if (now - previous < COMPACT_CHECK_INTERVAL_NANOS || !lastCompactCheck.compareAndSet(previous, now)) {
            return false;
        }
        try {
            boolean completed = compactIfNeeded(callerIsFlusher);
            // A completed or cleanly-aborted attempt ends any failure streak: the next
            // exception is a fresh failure and resets the slot (see the catch below).
            lastCompactAttemptThrew = false;
            if (!completed) {
                // The pass aborted before rewriting every candidate - the retirement/closed
                // checkpoint fired, or the store was closed the moment the pass started. The
                // five-minute throttle must not be spent on a pass that never ran: reset the
                // check slot so the next driver (a fresh flusher, the next flushAll) retries
                // immediately instead of waiting out the interval. The reset is a CAS against
                // this call's own stamp: a concurrent driver that already re-stamped the slot
                // (only possible if the pass outlived the whole interval) must not be clobbered
                // back to 0, or it would lose its own throttle slot.
                lastCompactCheck.compareAndSet(now, 0);
            }
        } catch (RuntimeException | Error e) {
            // A pass that threw did not run to completion either - but the reset is now
            // CONDITIONAL, unlike the abort path above. A fresh failure (the previous attempt
            // did not throw) reopens the slot for an immediate retry: the pass never ran, so
            // the interval must not be burned on a transient failure. A failure directly after
            // another failure must KEEP the stamp instead: resetting would make the next driver
            // (the group-commit loop, which wakes every batchFlushMillis - 500 ms by default)
            // retry a persistently failing pass on every wakeup - a tight retry loop that burns
            // CPU and spams the error log. With the reset skipped the five-minute boundary
            // holds, so a continuous failure is retried at most once per interval. Then
            // propagate the failure to the caller (the group-commit loop or
            // {@link FolesiumRegistry#flushAll()}).
            if (!lastCompactAttemptThrew) {
                lastCompactCheck.compareAndSet(now, 0);
            }
            lastCompactAttemptThrew = true;
            throw e;
        }
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
        if (t != null && t != Thread.currentThread()) {
            awaitFlusherExit(t);
        }
    }

    /**
     * Waits for a retired group-commit thread to finish its current cycle.
     *
     * <p>It is woken through the monitor rather than interrupted, because an interrupt during
     * {@code FileChannel.force} closes the channel ({@link java.nio.channels.ClosedByInterruptException}).
     * Interruption is kept only as a last resort if the thread does not come back in time.</p>
     *
     * <p>The interrupt is purely a retirement wakeup: it is only ever sent after the thread
     * was retired ({@code flusher} set to {@code null}) or the store closed, so the
     * retirement conditions in {@link #flushLoop} hold when it lands and the thread exits
     * without touching any channel. {@code flushLoop} itself guards against a stray or
     * external interrupt: it clears the pending interrupt status and keeps looping instead
     * of letting the next blocking {@code FileChannel} operation close the shard channel
     * permanently (a set interrupt status closes an interruptible channel on the next
     * blocking operation).</p>
     */
    private void awaitFlusherExit(Thread t) {
        try {
            t.join(5000);
            if (t.isAlive()) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: group-commit thread for {0} did not stop in time; interrupting", dir);
                t.interrupt();
                t.join(1000);
            }
        } catch (InterruptedException e) {
            // Deliberately do NOT re-assert the interrupt status: both callers resume
            // blocking FileChannel I/O right after this returns (applyRuntimeConfig's
            // post-retirement flush, close()'s per-keyspace close) and a set status would
            // make the next blocking channel operation throw ClosedByInterruptException,
            // permanently closing that shard channel (the same hazard flushLoop defends
            // against by clearing the status). The join was already interrupted - the
            // flusher either exited or will be retired on its next checkpoint - so the
            // remaining teardown must proceed with a clean status. (An external interrupt
            // aimed at this thread is consumed; that is the accepted cost of finishing the
            // teardown safely.)
            Thread.interrupted();
        }
    }

    /**
     * Whether {@code t} or any of its causes is an exception that left a shard channel
     * closed: {@link ClosedByInterruptException} - the signature of an interrupt landing
     * on a blocking {@code FileChannel} operation (flush {@code ->} force), which closes
     * the channel permanently - or {@link ClosedChannelException}, the same end state
     * when the channel was closed some other way. Either means the affected shard can
     * never be flushed (or written) again until its channel is reopened.
     */
    private static boolean isChannelClosedFailure(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClosedByInterruptException || cause instanceof ClosedChannelException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reopens every shard channel that a channel-closing flush failure left unusable (see
     * {@link #isChannelClosedFailure} and {@code ShardFile.reopenIfClosed}). Only the
     * interrupted group-commit thread's in-flight {@code force} could have closed a
     * channel, so at most one shard actually reopens; iterating all shards is simply the
     * most robust way to reach it without parsing exception messages, and healthy shards
     * make it a no-op. Skipped while the store is closing: {@code close()} is tearing the
     * shards down anyway and reopening would only fight it. Shards whose channel is
     * {@code null} (a failed compaction restore) are deliberately left alone - see
     * {@code ShardFile.reopenIfClosed}.
     */
    private void reopenInterruptClosedShards() {
        if (closed.get()) {
            return;
        }
        for (Keyspace ks : keyspaces.values()) {
            ks.reopenClosedShards();
        }
    }

    private void flushLoop() {
        // Whether this thread is the group-commit flusher - captured ONCE at loop start
        // instead of being sampled at each compaction pass start. The thread's identity is
        // stable for its whole lifetime: startFlusherIfNeeded registers it as `flusher`
        // before start(), and any retirement (durability switched away from BATCH, store
        // close) makes the loop exit at its next checkpoint without another pass. Sampling
        // at pass start instead would let a retirement landing between this loop's own
        // checkpoint and the pass misclassify a retired flusher as a non-flusher caller,
        // and the pass would then keep compacting - potentially beside a replacement
        // flusher's own pass - instead of aborting at its next iteration. (The comparison
        // can already be false only if the thread was retired before its first statement;
        // the loop's first checkpoint then exits without ever running a pass.)
        boolean amFlusher = flusher == Thread.currentThread();
        try {
            while (true) {
                // Clear any stray interrupt that landed outside the wait() below (e.g. an
                // external interrupt, or awaitFlusherExit's last-resort interrupt arriving
                // between the wait and the next iteration): a set interrupt status closes
                // an interruptible FileChannel on the next blocking operation (flush ->
                // force), so it must never survive into this iteration's channel I/O. The
                // retirement checks inside the lock are the only exit decision - an
                // external interrupt is never a reason to stop a healthy flusher.
                Thread.interrupted();
                synchronized (flusherLock) {
                    // Either the store is closing or this thread has been superseded/retired.
                    if (closed.get() || flusher != Thread.currentThread()) {
                        return;
                    }
                    if (config.durability() != FolesiumConfig.DurabilityMode.BATCH) {
                        // Switched away from BATCH while we slept; the reload's stop path
                        // takes care of the final flush. Retire ourselves atomically with
                        // the decision, so a concurrent reload that flips the config back
                        // to BATCH sees flusher == null and starts a fresh thread instead
                        // of no-op'ing on this exiting one.
                        flusher = null;
                        flusherLock.notifyAll();
                        return;
                    }
                    try {
                        // Read the interval INSIDE the lock. The actual protocol:
                        // applyRuntimeConfig writes the new config under keyspaceLock,
                        // then notifyAll()s under flusherLock; config is volatile, so
                        // this in-lock read sees the latest value. Reading under the
                        // lock makes read+wait atomic against the notify: a flusher
                        // that read the interval before a reload's notify will be
                        // waiting (holding no lock) when the notify fires and wakes
                        // immediately with the new config - no notification is lost,
                        // so a shortened interval applies immediately as documented.
                        flusherLock.wait(Math.max(1, config.batchFlushMillis()));
                    } catch (InterruptedException e) {
                        // Retirement wakeup: awaitFlusherExit's last-resort interrupt (or
                        // the store closing) arrives only after this thread was retired
                        // (flusher set to null) or closed was set, so exit without doing
                        // any more channel I/O. Only exit when the retirement conditions
                        // actually hold: an external/spurious interrupt must not silently
                        // stop group commit for a healthy store.
                        if (closed.get() || flusher != Thread.currentThread()) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        // External/spurious interrupt while this thread is still the
                        // active flusher: clear the pending interrupt status instead of
                        // exiting. A left-over interrupt would be picked up by the next
                        // blocking FileChannel operation (flush -> force) and close the
                        // shard channel permanently (FileChannel contract: an interrupt
                        // before a blocking operation closes an interruptible channel),
                        // breaking every subsequent write with ClosedChannelException.
                        // Keep looping with a clean status.
                        Thread.interrupted();
                    }
                    if (closed.get() || flusher != Thread.currentThread()) {
                        return;
                    }
                }
                try {
                    flush();
                    // Off the region threads and rate-limited: this is the only thing that
                    // keeps an append-only store from growing without bound.
                    compactIfNeededThrottled(amFlusher);
                } catch (RuntimeException | Error e) {
                    // Error is caught too: compactIfNeededThrottled() rethrows Error (e.g. a
                    // native zstd failure) after recording the failure slot, and an uncaught
                    // Error would kill this thread silently - BATCH group commit would stop
                    // with nothing logged. Log it and EXIT the loop; the restart below
                    // (after the finally clears this thread's registration) starts a fresh
                    // group-commit thread immediately, so a persistent failure (e.g. an
                    // unwritable shard) is retried at most once per batch interval - the
                    // fresh thread's first flush still happens after a full batchFlushMillis
                    // wait - instead of silently degrading to no flusher or spamming the
                    // error log in a tight loop.
                    if (isChannelClosedFailure(e)) {
                        // awaitFlusherExit's last-resort interrupt landed on a blocking
                        // FileChannel operation inside flush() (e.g. channel.force):
                        // ClosedByInterruptException closes the shard channel permanently,
                        // so every later write/read of that shard would fail - the
                        // interrupt was only meant as a retirement wakeup, not as a reason
                        // to kill the shard. Reopen the affected shard's channel so the
                        // store keeps serving; the fresh group-commit thread started below
                        // resumes flushing on it.
                        try {
                            reopenInterruptClosedShards();
                        } catch (RuntimeException reopenFailure) {
                            // The reopen itself failed (e.g. an IOException re-opening a
                            // shard): log it and continue the ERROR path. A throw here
                            // would skip the ERROR log AND the startFlusherIfNeeded()
                            // restart at the method tail, silently stopping BATCH group
                            // commit - exactly what the restart exists to prevent.
                            LOGGER.log(System.Logger.Level.ERROR,
                                    "Folesium: failed to reopen shard channels after a"
                                            + " channel-closing flush failure", reopenFailure);
                        }
                        // The reopened channels still carry their dirty state: flush once
                        // more NOW so the interrupted force completes before the fresh
                        // thread's first pass (which waits a full batchFlushMillis). A
                        // ClosedByInterruptException leaves the interrupt STATUS set
                        // (InterruptibleChannel contract), which would re-close the just
                        // reopened channel on the very next force - clear it first, the
                        // same hygiene the loop top applies. A second channel-closing
                        // failure is logged and left for the fresh thread's retry - never
                        // a reason to skip the ERROR path.
                        Thread.interrupted();
                        try {
                            flush();
                        } catch (RuntimeException flushRetryFailure) {
                            LOGGER.log(System.Logger.Level.ERROR,
                                    "Folesium: flush after reopening shard channels failed"
                                            + " (will retry on the fresh group-commit thread)",
                                    flushRetryFailure);
                        }
                    }
                    LOGGER.log(System.Logger.Level.ERROR, "Folesium group-commit failed for " + dir, e);
                    break;
                }
            }
        } finally {
            synchronized (flusherLock) {
                if (flusher == Thread.currentThread()) {
                    flusher = null;
                }
            }
        }
        // The ERROR catch above returned with this thread still registered as `flusher`;
        // the finally just cleared the registration, so BATCH group commit would silently
        // stop until the next driver (flushAll, reload) woke it again. Restart a fresh
        // group-commit thread now instead of degrading to no flusher. The guards inside
        // startFlusherIfNeeded() - closed, an existing flusher, durability != BATCH - make
        // this a no-op for every non-failure exit (store close, retirement, durability
        // switched away): only a genuine ERROR exit leaves BATCH + not closed + flusher ==
        // null. The fresh thread's first flush still happens after a full batchFlushMillis
        // wait (see the top of the loop), so a persistent failure is retried at most once
        // per interval - never in a tight restart loop.
        startFlusherIfNeeded();
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
