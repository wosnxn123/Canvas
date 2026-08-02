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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import dev.folesium.core.util.ZstdNative;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide, reference-counted registry of open {@link FolesiumDatabase} instances.
 *
 * <p>The server opens one {@code RegionFileStorage} per (dimension, data type)
 * - {@code region}, {@code poi} and {@code entities} - but all three must share
 * a single Folesium store per dimension, otherwise three independent group-commit
 * threads and shard sets would be created for the same directory. This registry
 * keys databases by their canonical (absolute, normalised) directory and hands
 * out the same instance to every caller, closing it when the last user releases it.</p>
 *
 * <p>Thread-safety: all methods are synchronised on the registry class. They are
 * only called on world load/unload, never on the chunk I/O hot path.</p>
 *
 * <p>Configuration precedence (highest first):</p>
 * <ol>
 *   <li>system properties {@code folesium.*}</li>
 *   <li>{@code folesium.properties} in the server working directory</li>
 *   <li>{@link FolesiumConfig#defaults()}</li>
 * </ol>
 */
public final class FolesiumRegistry {

    private static final System.Logger LOGGER = System.getLogger("Folesium");

    /** Name of the optional configuration file, resolved against the working directory. */
    public static final String CONFIG_FILE = "folesium.properties";

    private static final Map<Path, Entry> OPEN = new HashMap<>();

    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean UTF8_LOGGING_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean ASCII_LOGGING_INSTALLED = new AtomicBoolean();

    private static Properties fileProperties;
    private static Boolean enabledCache;

    private static Thread configWatcher;
    private static long configFileStamp;

    private FolesiumRegistry() {
    }

    private static final class Entry {
        final FolesiumDatabase db;
        int refCount;

        Entry(FolesiumDatabase db) {
            this.db = db;
            this.refCount = 0;
        }
    }

    /* ------------------------------------------------------------------ */
    /* configuration                                                       */
    /* ------------------------------------------------------------------ */

    /** Where {@code folesium.properties} is read from; {@code -Dfolesium.configFile} overrides it. */
    public static Path configFilePath() {
        return Path.of(System.getProperty("folesium.configFile", CONFIG_FILE));
    }

    private static synchronized Properties fileProperties() {
        installAsciiConsoleLogging();
        ensureUtf8Logging();
        if (fileProperties == null) {
            Properties p = new Properties();
            Path file = configFilePath();
            if (Files.isRegularFile(file)) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(reader);
                    LOGGER.log(System.Logger.Level.INFO, "Folesium: loaded configuration from {0}", file.toAbsolutePath());
                } catch (IllegalArgumentException e) {
                    // A malformed backslash-u escape (e.g. a pasted Windows path in a value) must
                    // never abort startup; fall back to the built-in defaults. Discard anything
                    // read so far - a half-parsed prefix must not apply to a running server.
                    LOGGER.log(System.Logger.Level.ERROR,
                            "Folesium: cannot parse {0} (malformed backslash-u escape?): {1}; using built-in defaults (enabled=false)",
                            file.toAbsolutePath(), e.toString());
                    p = new Properties();
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read " + file.toAbsolutePath(), e);
                }
            } else {
                // No config file present: try to auto-generate one with machine-tuned defaults.
                // Folesium stays opt-in (enabled=false) until an operator turns it on. If the
                // working directory is read-only or the write fails for any reason, we must NOT
                // crash the server (Folesium is opt-in) - fall back to an in-memory default.
                try {
                    generateDefaultConfigFile(file);
                    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        p.load(reader);
                    }
                } catch (IllegalArgumentException e) {
                    // Same malformed backslash-u guard as above; nearly unreachable because the
                    // file was just generated, but a load failure must never abort the server.
                    LOGGER.log(System.Logger.Level.ERROR,
                            "Folesium: cannot parse generated {0} ({1}); using built-in defaults (enabled=false)",
                            file.toAbsolutePath(), e.toString());
                    p = new Properties();
                } catch (RuntimeException | IOException e) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Folesium: cannot create/read {0} ({1}); using built-in defaults (enabled=false)",
                            file, e.toString());
                    p.setProperty("enabled", "false");
                }
            }
            fileProperties = p;
        }
        return fileProperties;
    }

    /**
     * Creates a self-documenting {@code folesium.properties} on first run. The file lists
     * every tunable option with a comment and its documented default value (docs/CONFIG.md),
     * but leaves the engine disabled ({@code enabled=false}) so an unconfigured server keeps
     * vanilla Anvil behaviour.
     * Operators edit this file (or pass {@code -Dfolesium.*} system properties) to activate and
     * tune Folesium. The file is written exactly once; later runs load it as-is.
     *
     * <p>Important: {@link Properties#load} does not understand inline comments, so every
     * {@code key=value} is written on its own line with no trailing comment.</p>
     */
    private static void generateDefaultConfigFile(Path file) {
        // The generated file carries this machine's auto-tuned defaults (docs/AUTO-CONFIG.md),
        // so a fresh install already uses ZSTD + a shard count matched to its CPU cores.
        FolesiumConfig defaults = autoTunedConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("# Folesium configuration - auto-generated by Folesium on first run.\n");
        sb.append("# Folesium is OPT-IN: 'enabled' is intentionally false, so the server behaves like stock\n");
        sb.append("# Folia/Canvas (writes .mca) until you set enabled=true. Edit values freely; this file is\n");
        sb.append("# written only once. System properties -Dfolesium.<key>=<value> override anything here.\n");
        sb.append("# Every value below can be changed while the server runs: Folesium notices that this file was\n");
        sb.append("# edited and applies it within a few seconds. Exceptions: 'enabled' applies when a world is next\n");
        sb.append("# loaded, 'shards' by an automatic reshard of the store on the next start, and indexCacheBytes /\n");
        sb.append("# indexMode / dictionaryCompression / backupOnConvert on the next store open or conversion.\n");
        sb.append("# Auto-tuned for this machine: ").append(describeMachine()).append('\n');
        sb.append('\n');
        sb.append("# Master switch. false = vanilla Anvil behaviour; true = use Folesium storage.\n");
        sb.append("enabled=false\n");
        sb.append('\n');
        sb.append("# Number of independent log shards per keyspace (must be a power of two, 1..1024).\n");
        sb.append("# More shards = more write parallelism. Matched to this machine's CPU cores;\n");
        sb.append("# edit freely (an automatic reshard rewrites the store on the next start).\n");
        sb.append("shards=").append(defaults.shardCount()).append('\n');
        sb.append('\n');
        sb.append("# Durability: ALWAYS (fsync every write) | BATCH (background group commit) | EXPLICIT (fsync on flush/close).\n");
        sb.append("durability=").append(defaults.durability().name()).append('\n');
        sb.append('\n');
        sb.append("# Group-commit interval in milliseconds (used when durability=BATCH).\n");
        sb.append("batchFlushMillis=").append(defaults.batchFlushMillis()).append('\n');
        sb.append('\n');
        sb.append("# Per-record compression: NONE | DEFLATE | ZSTD. Auto-tuned to this machine:\n");
        sb.append("# ZSTD when zstd-jni is present (Folia/Canvas ship it), otherwise DEFLATE.\n");
        sb.append("compression=").append(defaults.compression().name()).append('\n');
        sb.append('\n');
        sb.append("# Compression level. Valid range depends on the codec: DEFLATE 1-9, ZSTD 1-22.\n");
        sb.append("# Auto-tuned: 9 with ZSTD (~vanilla zlib-6 write CPU, better ratio), 4 with DEFLATE.\n");
        sb.append("compressionLevel=").append(defaults.compressionLevel()).append('\n');
        sb.append('\n');
        sb.append("# Compact a shard when its dead (overwritten/deleted) bytes exceed this fraction of the file size.\n");
        sb.append("compactRatio=").append(defaults.compactRatio()).append('\n');
        sb.append('\n');
        sb.append("# Never compact shards smaller than this many bytes.\n");
        sb.append("compactMinBytes=").append(defaults.compactMinBytes()).append('\n');
        sb.append('\n');
        sb.append("# Re-verify each record's CRC32C on every read (~2x read I/O). Leave false unless diagnosing corruption.\n");
        sb.append("verifyChecksums=").append(defaults.verifyChecksums()).append('\n');
        sb.append('\n');
        sb.append("# When converting a world, keep the previous tree at the target location under a\n");
        sb.append("# '.folesium-backup-*' sibling name (both directions) instead of overwriting it in place.\n");
        sb.append("backupOnConvert=").append(defaults.backupOnConvert()).append('\n');
        sb.append('\n');
        sb.append("# Bytes of region-page index cache per keyspace (0 disables the page index, pure v1 behaviour).\n");
        sb.append("# Auto-tuned: min(64 MiB, 2% of max heap).\n");
        sb.append("indexCacheBytes=").append(defaults.indexCacheBytes()).append('\n');
        sb.append('\n');
        sb.append("# Page-index mode: AUTO (page first, hash fallback) | PAGE (page only). Takes effect when a\n");
        sb.append("# world is next loaded. Invalid values fall back to AUTO.\n");
        sb.append("indexMode=").append(defaults.indexMode().name()).append('\n');
        sb.append('\n');
        sb.append("# Compress new region records with a per-keyspace zstd dictionary (codec 3). Requires zstd-jni;\n");
        sb.append("# the dictionary is trained by the conversion pipeline at the end of a conversion\n");
        sb.append("# (new writes fall back to plain ZSTD while it is missing). Off by default.\n");
        sb.append("dictionaryCompression=").append(defaults.dictionaryCompression()).append('\n');
        sb.append('\n');
        sb.append("# Prioritise compacting the shards with the most write churn instead of a pure dead-ratio order.\n");
        sb.append("# Off by default.\n");
        sb.append("workloadCompaction=").append(defaults.workloadCompaction()).append('\n');
        sb.append('\n');
        sb.append("# Cap compaction I/O at this many bytes/second (0 = unlimited).\n");
        sb.append("compactIoLimit=").append(defaults.compactIoLimit()).append('\n');
        sb.append('\n');
        sb.append("# Watch this file and apply edits to the running server without a restart.\n");
        sb.append("autoReload=true\n");
        sb.append('\n');
        sb.append("# How often (seconds) this file is checked for edits when autoReload is on.\n");
        sb.append("autoReloadSeconds=10\n");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            LOGGER.log(System.Logger.Level.INFO,
                    "Folesium: no {0} found; created auto-tuned default at {1} (enabled=false). Edit it to enable.",
                    CONFIG_FILE, file.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write auto-generated " + file.toAbsolutePath(), e);
        }
    }

    /**
     * Derives a performance-oriented default from the host: CPU cores select the shard count
     * (so write parallelism matches the machine's concurrency), and an available zstd-jni selects
     * the compression codec. Everything else is a single, server-grade default - we deliberately
     * do NOT down-tune for weak hardware, because the file is regenerated on each machine's first
     * run and should reflect that machine's own capabilities. Durability defaults to BATCH (group
     * commit), which is strictly stronger than vanilla Anvil's fsync-on-close.
     */
    /**
     * Shard count for a given logical-core count (see {@link #autoTunedConfig()}).
     * Deliberately coarse: shards only affect write-lock granularity and file count — the
     * in-memory index total is independent of the shard count — and the format requires a
     * power of two, so there is no point in one shard per core. Pure function for tests.
     */
    static int autoTunedShards(int cores) {
        if (cores <= 4)       return 8;
        else if (cores <= 8)  return 16;
        else if (cores <= 16) return 32;
        else if (cores <= 32) return 64;
        else                  return 128;
    }

    private static FolesiumConfig autoTunedConfig() {
        int cores = Runtime.getRuntime().availableProcessors();
        // available() caches its probe after the first call (see ZstdNative); the
        // folesium.zstd.forceUnavailable test switch applies by refreshing that cache via
        // ZstdNative.setForcedUnavailable (the switch-gated tests do this before rebuilding
        // the registry config).
        boolean zstd = ZstdNative.available();

        int shards = autoTunedShards(cores);

        FolesiumConfig.Compression compression =
                zstd ? FolesiumConfig.Compression.ZSTD : FolesiumConfig.Compression.DEFLATE;
        // ZSTD 9 costs about the same CPU as vanilla zlib 6 while compressing ~10-15% better
        // (measured on a 1.9M-chunk world); DEFLATE 4 ≈ vanilla zlib ratio at lower CPU.
        int compressionLevel = zstd ? 9 : 4;
        int batchFlushMillis = 500;
        double compactRatio = 0.5;
        long compactMinBytes = 8L * 1024 * 1024;
        boolean verifyChecksums = false;
        boolean backupOnConvert = false; // cesium parity: converters write targets in place
        // Page index cache: 2% of max heap, capped at 64 MiB (0 = pure v1 hash behaviour).
        long indexCacheBytes = Math.min(64L * 1024 * 1024,
                (long) (Runtime.getRuntime().maxMemory() * 0.02));
        boolean dictionaryCompression = false; // Phase 3: per-keyspace zstd dict codec, opt-in
        boolean workloadCompaction = false; // Phase 3: write-churn-aware compaction priority, opt-in
        long compactIoLimit = 0; // compaction I/O cap in bytes/sec, 0 = unlimited

        return new FolesiumConfig(
                shards,
                FolesiumConfig.DurabilityMode.BATCH,
                batchFlushMillis,
                compression,
                compressionLevel,
                compactRatio,
                compactMinBytes,
                verifyChecksums,
                backupOnConvert,
                indexCacheBytes,
                FolesiumConfig.IndexMode.AUTO,
                dictionaryCompression,
                workloadCompaction,
                compactIoLimit
        );
    }

    private static String describeMachine() {
        int cores = Runtime.getRuntime().availableProcessors();
        long maxMemMb = Runtime.getRuntime().maxMemory() / (1024L * 1024);
        boolean zstd = ZstdNative.available();
        return String.format("%d cores, %d MB max heap, zstd-jni %s",
                cores, maxMemMb, zstd ? "available" : "unavailable");
    }

    /** Reads {@code folesium.<key>} from system properties, then the config file, then {@code def}. */
    public static String property(String key, String def) {
        String sys = System.getProperty("folesium." + key);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String fromFile = fileProperties().getProperty(key);
        return fromFile == null || fromFile.isBlank() ? def : fromFile.trim();
    }

    private static int intProperty(String key, int def) {
        try {
            return Integer.parseInt(property(key, Integer.toString(def)));
        } catch (NumberFormatException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: bad integer for folesium.{0}, using {1}", key, def);
            return def;
        }
    }

    private static boolean boolProperty(String key, boolean def) {
        return Boolean.parseBoolean(property(key, Boolean.toString(def)));
    }

    private static double doubleProperty(String key, double def) {
        try {
            return Double.parseDouble(property(key, Double.toString(def)));
        } catch (NumberFormatException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: bad number for folesium.{0}, using {1}", key, def);
            return def;
        }
    }

    private static long longProperty(String key, long def) {
        try {
            return Long.parseLong(property(key, Long.toString(def)));
        } catch (NumberFormatException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: bad number for folesium.{0}, using {1}", key, def);
            return def;
        }
    }

    /**
     * Whether the server integration should route chunk I/O through Folesium.
     * Defaults to {@code false}: an unconfigured server keeps vanilla Anvil behaviour.
     */
    public static synchronized boolean isEnabled() {
        if (enabledCache == null) {
            enabledCache = boolProperty("enabled", false);
        }
        return enabledCache;
    }

    /** Test hook: forget cached configuration (used by unit tests only). */
    public static synchronized void resetConfigCacheForTesting() {
        fileProperties = null;
        enabledCache = null;
    }

    /** Builds the effective configuration from properties. */
    public static FolesiumConfig configFromProperties() {
        FolesiumConfig d = autoTunedConfig();
        FolesiumConfig.DurabilityMode durability;
        String durabilityName = property("durability", d.durability().name()).toUpperCase(Locale.ROOT);
        try {
            durability = FolesiumConfig.DurabilityMode.valueOf(durabilityName);
        } catch (IllegalArgumentException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: unknown durability ''{0}'', using {1}", durabilityName, d.durability());
            durability = d.durability();
        }
        FolesiumConfig.Compression compression;
        String compressionName = property("compression", d.compression().name()).toUpperCase(Locale.ROOT);
        try {
            compression = FolesiumConfig.Compression.valueOf(compressionName);
        } catch (IllegalArgumentException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: unknown compression ''{0}'', using {1}", compressionName, d.compression());
            compression = d.compression();
        }
        // ZSTD_DICT needs the zstd-jni dictionary API on top of plain ZSTD, so each codec is
        // probed with its own gate: ZSTD against ZstdNative.available(), ZSTD_DICT against
        // dictAvailable() (which also covers the library being entirely absent).
        if ((compression == FolesiumConfig.Compression.ZSTD && !ZstdNative.available())
                || (compression == FolesiumConfig.Compression.ZSTD_DICT && !ZstdNative.dictAvailable())) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: compression={0} requested but {1}, using {2}",
                    compression,
                    compression == FolesiumConfig.Compression.ZSTD_DICT
                            ? "the zstd-jni dictionary API is not available"
                            : "zstd-jni is not available",
                    d.compression());
            compression = d.compression();
        }
        // Cross-check with the dictionary flag (FolesiumConfig forbids codec 3 without
        // dictionaryCompression): an operator typo of compression=ZSTD_DICT with the flag
        // off must fall back like every other unusable value here, not abort server start
        // (and never reach ShardFile's per-record ZSTD_DICT failure).
        boolean dictionaryCompression = boolProperty("dictionaryCompression", d.dictionaryCompression());
        if (compression == FolesiumConfig.Compression.ZSTD_DICT && !dictionaryCompression) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: compression=ZSTD_DICT requires dictionaryCompression=true, using ZSTD");
            compression = FolesiumConfig.Compression.ZSTD;
        }
        int shards = intProperty("shards", d.shardCount());
        if (Integer.bitCount(shards) != 1 || shards < 1 || shards > 1024) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: invalid shards={0}, using {1}", shards, d.shardCount());
            shards = d.shardCount();
        }
        // Deflate tops out at 9, zstd at 22 - validate against the codec actually in use.
        int maxLevel = FolesiumConfig.maxCompressionLevel(compression);
        int level = intProperty("compressionLevel", d.compressionLevel());
        if (level < 1 || level > maxLevel) {
            // Clamp the operator's own value into the codec's valid range (same policy as
            // FolesiumDatabase.applyRuntimeConfig) instead of substituting the auto-tuned
            // default: 0 or 99 in the file becomes 1 or maxLevel for the codec in use.
            int fallback = FolesiumConfig.clampCompressionLevel(compression, level);
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: compressionLevel={0} is out of range [1,{1}] for {2}, using {3}",
                    level, maxLevel, compression, fallback);
            level = fallback;
        }
        // Everything below must fall back rather than throw: an unusable value in
        // folesium.properties is an operator typo, not a reason to abort server start.
        int batchFlushMillis = intProperty("batchFlushMillis", d.batchFlushMillis());
        if (batchFlushMillis < 1) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: batchFlushMillis={0} must be >= 1, using {1}", batchFlushMillis, d.batchFlushMillis());
            batchFlushMillis = d.batchFlushMillis();
        }
        double compactRatio = doubleProperty("compactRatio", d.compactRatio());
        if (!(compactRatio > 0) || compactRatio > 1) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: compactRatio={0} must be in (0,1], using {1}", compactRatio, d.compactRatio());
            compactRatio = d.compactRatio();
        }
        long compactMinBytes = longProperty("compactMinBytes", d.compactMinBytes());
        if (compactMinBytes < 0) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: compactMinBytes={0} must be >= 0, using {1}", compactMinBytes, d.compactMinBytes());
            compactMinBytes = d.compactMinBytes();
        }
        long indexCacheBytes = longProperty("indexCacheBytes", d.indexCacheBytes());
        if (indexCacheBytes < 0) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: indexCacheBytes={0} must be >= 0, using {1}", indexCacheBytes, d.indexCacheBytes());
            indexCacheBytes = d.indexCacheBytes();
        }
        FolesiumConfig.IndexMode indexMode;
        String indexModeName = property("indexMode", d.indexMode().name()).toUpperCase(Locale.ROOT);
        try {
            indexMode = FolesiumConfig.IndexMode.valueOf(indexModeName);
        } catch (IllegalArgumentException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: unknown indexMode ''{0}'', using {1}", indexModeName, d.indexMode());
            indexMode = d.indexMode();
        }
        boolean workloadCompaction = boolProperty("workloadCompaction", d.workloadCompaction());
        long compactIoLimit = longProperty("compactIoLimit", d.compactIoLimit());
        if (compactIoLimit < 0) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: compactIoLimit={0} must be >= 0, using 0", compactIoLimit);
            compactIoLimit = 0;
        }
        return new FolesiumConfig(
                shards,
                durability,
                batchFlushMillis,
                compression,
                level,
                compactRatio,
                compactMinBytes,
                boolProperty("verifyChecksums", d.verifyChecksums()),
                boolProperty("backupOnConvert", d.backupOnConvert()),
                indexCacheBytes,
                indexMode,
                dictionaryCompression,
                workloadCompaction,
                compactIoLimit
        );
    }

    /**
     * Re-reads {@code folesium.properties} (and {@code -Dfolesium.*}) and pushes the result to
     * every open store, so operators can retune a running server without restarting it.
     *
     * <p>{@code enabled} and {@code shards} are the two settings a reload cannot fully apply:
     * worlds bind their storage backend when they load, and the shard count is physical. Both
     * are reported back in the result instead of being silently dropped - {@code shards} is
     * then applied automatically by a reshard on the next start. The open-only tunables
     * ({@code indexCacheBytes}, {@code indexMode}, {@code dictionaryCompression},
     * {@code backupOnConvert}) are likewise not applied to the running store and are
     * reported in the result's {@code notes}, taking effect on the next store open / conversion.</p>
     *
     * @return one entry per open store, in directory order
     */
    public static List<ReloadReport> reload() {
        List<FolesiumDatabase> snapshot;
        FolesiumConfig cfg;
        boolean enabledBefore;
        synchronized (FolesiumRegistry.class) {
            enabledBefore = isEnabled();
            fileProperties = null;
            enabledCache = null;
            cfg = configFromProperties();
            snapshot = openDatabases();
        }
        boolean enabledAfter = isEnabled();
        if (enabledBefore != enabledAfter) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: enabled={0} takes effect when a world is (re)loaded; running worlds keep their"
                            + " current storage backend", enabledAfter);
        }
        List<ReloadReport> out = new ArrayList<>(snapshot.size());
        for (FolesiumDatabase db : snapshot) {
            try {
                out.add(new ReloadReport(db.directory(), db.applyRuntimeConfig(cfg), null));
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: reload failed for " + db.directory(), ex);
                // toString(), never getMessage(): a message-less RuntimeException would make
                // error() null too, and callers use "error != null" to tell failure apart.
                out.add(new ReloadReport(db.directory(), null, ex.toString()));
            }
        }
        out.sort(java.util.Comparator.comparing(r -> r.directory().toString()));
        return out;
    }

    /**
     * Per-store outcome of {@link #reload()}.
     *
     * @param result {@code null} when the store could not be reconfigured, see {@code error}
     * @param error  failure message, or {@code null} on success
     */
    public record ReloadReport(Path directory, FolesiumDatabase.ConfigReloadResult result, String error) {
    }

    /* ------------------------------------------------------------------ */
    /* automatic reload                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Starts the watcher that applies edits to {@code folesium.properties} to the running
     * server. Without it, retuning a setting would mean a restart - and a restart is exactly
     * what an operator whose settings do not suit the machine cannot afford.
     *
     * <p>Disabled with {@code folesium.autoReload=false}; the poll interval is
     * {@code folesium.autoReloadSeconds} (default 10). The thread is a daemon and stops on
     * its own once the last store is closed.</p>
     */
    private static void ensureConfigWatcher() {
        if (configWatcher != null || !boolProperty("autoReload", true)) {
            return;
        }
        configFileStamp = configFileTimestamp();
        Thread t = Thread.ofPlatform().daemon().name("folesium-config-watch")
                .unstarted(FolesiumRegistry::watchConfigFile);
        configWatcher = t;
        t.start();
    }

    private static long configFileTimestamp() {
        Path file = configFilePath();
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Raw text of the configuration file, or {@code null} when it is missing or unreadable. */
    private static String configFileContent() {
        Path file = configFilePath();
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * True when {@code content} parses as a Java properties file — in particular without an
     * {@link IllegalArgumentException} for a malformed backslash-u escape. Logs a clear error
     * on failure so the caller can keep the previous configuration instead of applying defaults.
     */
    private static boolean parsesAsProperties(String content) {
        if (content == null) {
            return false;
        }
        try {
            new Properties().load(new java.io.StringReader(content));
            return true;
        } catch (IllegalArgumentException e) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Folesium: cannot parse {0} (malformed backslash-u escape?): {1}; keeping previous configuration",
                    configFilePath().toAbsolutePath(), e.toString());
            return false;
        } catch (IOException e) {
            // A StringReader cannot throw IOException; this is defensive for future readers.
            LOGGER.log(System.Logger.Level.ERROR,
                    "Folesium: cannot read {0}: {1}; keeping previous configuration",
                    configFilePath().toAbsolutePath(), e.toString());
            return false;
        }
    }

    /**
     * Clears {@link #configWatcher} when the finishing thread is still the registered watcher.
     * A replacement watcher may have been started while this one was unwinding (e.g. after
     * {@code OPEN} emptied and was re-filled), so never clear a watcher that is not this thread.
     */
    private static void clearConfigWatcherIfCurrent() {
        synchronized (FolesiumRegistry.class) {
            if (configWatcher == Thread.currentThread()) {
                configWatcher = null;
            }
        }
    }

    private static void watchConfigFile() {
        try {
            while (true) {
                int seconds;
                synchronized (FolesiumRegistry.class) {
                    if (OPEN.isEmpty()) {
                        // Nothing to reconfigure; a later acquire() starts a fresh watcher.
                        configWatcher = null;
                        return;
                    }
                    seconds = Math.max(1, intProperty("autoReloadSeconds", 10));
                }
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long stamp = configFileTimestamp();
                boolean changed;
                synchronized (FolesiumRegistry.class) {
                    changed = stamp != -1L && stamp != configFileStamp;
                }
                if (!changed) {
                    continue;
                }
                // Let a half-written file settle: editors truncate first and write after.
                // Re-read twice with a short delay and apply only when the content is stable
                // AND parses cleanly, so a torn write can never apply auto-tuned defaults to
                // a running server. On any failure keep the previous configuration and log.
                String before = configFileContent();
                if (before == null) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            "Folesium: {0} changed on disk but cannot be read; keeping previous configuration",
                            configFilePath().toAbsolutePath());
                    continue;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String after = configFileContent();
                if (after == null || !after.equals(before)) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Folesium: {0} changed again while being re-read (still being written?);"
                                    + " keeping previous configuration",
                            configFilePath().toAbsolutePath());
                    continue;
                }
                if (!parsesAsProperties(after)) {
                    // parsesAsProperties() logged the clear error; keep the previous configuration.
                    continue;
                }
                // Commit the stamp only after the change has been verified stable, so a change
                // that was still settling is retried on the next poll instead of being skipped.
                synchronized (FolesiumRegistry.class) {
                    configFileStamp = stamp;
                }
                LOGGER.log(System.Logger.Level.INFO,
                        "Folesium: {0} changed on disk, applying it to the running server", configFilePath());
                try {
                    for (ReloadReport r : reload()) {
                        if (r.error() != null) {
                            LOGGER.log(System.Logger.Level.ERROR, "Folesium: {0}: {1}", r.directory(), r.error());
                        }
                        if (r.result() == null) {
                            continue;
                        }
                        if (r.result().changed()) {
                            LOGGER.log(System.Logger.Level.INFO, "Folesium: {0}: {1}",
                                    r.directory(), String.join(", ", r.result().changes()));
                        }
                        for (String note : r.result().notes()) {
                            LOGGER.log(System.Logger.Level.WARNING, "Folesium: {0}: {1}", r.directory(), note);
                        }
                    }
                } catch (RuntimeException ex) {
                    LOGGER.log(System.Logger.Level.ERROR, "Folesium: automatic reload failed", ex);
                }
            }
        } finally {
            // However this thread exits - including InterruptedException during the settle
            // sleep - release the watcher slot so a later acquire() can start a fresh watcher.
            clearConfigWatcherIfCurrent();
        }
    }

    /* ------------------------------------------------------------------ */
    /* registry                                                            */
    /* ------------------------------------------------------------------ */

    private static Path canonical(Path dir) {
        return dir.toAbsolutePath().normalize();
    }

    /**
     * Installs a JVM shutdown hook that flushes and closes every open store. Without this,
     * stores that the server forgot to release (e.g. non-overworld dimensions or the player
     * store) would never be flushed/closed on exit, losing the last BATCH window and their
     * clean-shutdown index hints. The hook is idempotent.
     */
    private static void ensureShutdownHook() {
        if (SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                    Thread.ofPlatform().name("folesium-shutdown").unstarted(() -> {
                        try {
                            closeAll();
                        } catch (Throwable t) {
                            LOGGER.log(System.Logger.Level.ERROR, "Folesium: shutdown closeAll failed", t);
                        }
                    }));
        }
    }

    /**
     * Installs an ASCII-only console formatter for the Folesium loggers.
     *
     * <p>JUL's default {@code SimpleFormatter} localises the timestamp (Chinese-locale
     * AM/PM markers, e.g. {@code 涓嬪崍}) and the level label ({@code 淇℃伅} for INFO). On a
     * Windows console whose code page is not UTF-8 those bytes render as mojibake
     * regardless of {@code ensureUtf8Logging()}. The formatter below emits an ISO
     * timestamp, the English level name and the (already ASCII) message, so the output
     * is byte-identical under UTF-8 and GBK consoles. Idempotent, never throws.</p>
     */
    private static void installAsciiConsoleLogging() {
        if (!ASCII_LOGGING_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            java.util.logging.Formatter formatter = new java.util.logging.Formatter() {
                @Override
                public String format(java.util.logging.LogRecord record) {
                    // formatMessage() expands the {0}/{1} placeholders; getMessage()
                    // would leak the raw pattern.
                    return String.format(java.util.Locale.ROOT, "%1$tF %1$tT [%2$s] %3$s%n",
                            new java.util.Date(record.getMillis()), record.getLevel().getName(),
                            formatMessage(record));
                }
            };
            for (String name : new String[] {"Folesium", "dev.folesium"}) {
                java.util.logging.Logger logger = java.util.logging.Logger.getLogger(name);
                logger.setUseParentHandlers(false);
                logger.setLevel(java.util.logging.Level.ALL);
                java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
                handler.setLevel(java.util.logging.Level.ALL);
                handler.setFormatter(formatter);
                logger.addHandler(handler);
            }
        } catch (Throwable t) {
            // Logging cosmetics must never crash the server.
        }
    }

    /**
     * Best-effort: forces the JDK logging (java.util.logging) handlers to emit UTF-8.
     *
     * <p>On platforms whose default charset is not UTF-8 (notably Chinese / Korean / Japanese
     * Windows), JUL's ConsoleHandler emits using the platform charset, so Chinese-locale log lines
     * (the localized INFO / SEVERE labels and the localized date in the timestamp) render as
     * mojibake in a UTF-8 terminal. Folesium re-encodes the root handlers to UTF-8 so the server
     * log is readable. This is idempotent, never throws, and can be disabled with
     * {@code -Dfolesium.logging.utf8=false}.</p>
     */
    private static void ensureUtf8Logging() {
        if (!UTF8_LOGGING_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        if ("false".equalsIgnoreCase(System.getProperty("folesium.logging.utf8"))) {
            return;
        }
        if (java.nio.charset.Charset.defaultCharset() == java.nio.charset.StandardCharsets.UTF_8) {
            // JVM already UTF-8; any remaining console mismatch is outside our control.
            return;
        }
        try {
            var root = java.util.logging.Logger.getLogger("");
            for (var h : root.getHandlers()) {
                if (h instanceof java.util.logging.StreamHandler) {
                    try {
                        h.setEncoding("UTF-8");
                    } catch (java.io.UnsupportedEncodingException | SecurityException ignored) {
                        // Keep the handler's current encoding.
                    }
                }
            }
        } catch (Throwable t) {
            // Logging cosmetics must never crash the server.
        }
    }

    /** Opens (or joins) the dimension store in {@code dir} and increments its reference count. */
    public static synchronized FolesiumDatabase acquire(Path dir) {
        return acquire(dir, configFromProperties(), FolesiumDatabase.StoreRole.DIMENSION);
    }

    public static synchronized FolesiumDatabase acquire(Path dir, FolesiumConfig config) {
        return acquire(dir, config, FolesiumDatabase.StoreRole.DIMENSION);
    }

    /** Opens (or joins) the store in {@code dir} with the given role, using configured defaults. */
    public static synchronized FolesiumDatabase acquire(Path dir, FolesiumDatabase.StoreRole role) {
        return acquire(dir, configFromProperties(), role);
    }

    public static synchronized FolesiumDatabase acquire(Path dir, FolesiumConfig config, FolesiumDatabase.StoreRole role) {
        ensureShutdownHook();
        ensureConfigWatcher();
        Path key = canonical(dir);
        Entry entry = OPEN.get(key);
        if (entry == null || entry.db.isClosed()) {
            entry = new Entry(FolesiumDatabase.open(key, config, role));
            OPEN.put(key, entry);
            LOGGER.log(System.Logger.Level.INFO,
                    "Folesium: opened {0} store {1} (shards={2}, durability={3}, compression={4})",
                    entry.db.role(), key, entry.db.config().shardCount(),
                    entry.db.config().durability(), entry.db.config().compression());
        } else if (entry.db.role() != role) {
            throw new FolesiumException("Folesium store " + key + " is already open as "
                    + entry.db.role() + "; cannot also open it as " + role);
        }
        entry.refCount++;
        return entry.db;
    }

    /** Decrements the reference count of {@code dir}; closes the store when it reaches zero. */
    public static synchronized void release(Path dir) {
        release(dir, null);
    }

    /**
     * Like {@link #release(Path)} but, when {@code expected} is non-null, only releases if the
     * currently-registered store for {@code dir} is exactly {@code expected}. This prevents a
     * stale reference (e.g. held before a {@link #closeAll()} reopened the store) from releasing
     * a brand-new store opened by another caller. Callers that hold the database instance should
     * pass it here; the single-argument form is kept for compatibility.
     */
    public static synchronized void release(Path dir, FolesiumDatabase expected) {
        Path key = canonical(dir);
        Entry entry = OPEN.get(key);
        if (entry == null) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: release called for unknown store {0}", key);
            return;
        }
        if (expected != null && entry.db != expected) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: stale release for {0} ignored (store was reopened)", key);
            return;
        }
        if (entry.refCount > 0) {
            entry.refCount--;
        } else {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: retrying close of {0} after a prior close failure", key);
        }
        if (entry.refCount != 0) {
            return;
        }
        try {
            entry.db.close();
            OPEN.remove(key, entry);
            LOGGER.log(System.Logger.Level.INFO, "Folesium: closed store {0}", key);
        } catch (RuntimeException ex) {
            // Keep the zero-ref entry and its dirty keyspaces reachable. A later release or
            // closeAll can retry after the transient I/O failure clears.
            LOGGER.log(System.Logger.Level.ERROR, "Folesium: error closing " + key, ex);
        }
    }

    /** Current reference count, for diagnostics and tests. */
    public static synchronized int refCount(Path dir) {
        Entry entry = OPEN.get(canonical(dir));
        return entry == null ? 0 : entry.refCount;
    }

    public static synchronized List<FolesiumDatabase> openDatabases() {
        List<FolesiumDatabase> out = new ArrayList<>(OPEN.size());
        for (Entry e : OPEN.values()) {
            out.add(e.db);
        }
        return out;
    }

    /** fsyncs every open store. Used by the server's global save/flush path. */
    public static void flushAll() {
        List<FolesiumDatabase> snapshot;
        synchronized (FolesiumRegistry.class) {
            snapshot = openDatabases();
        }
        for (FolesiumDatabase db : snapshot) {
            try {
                db.flush();
                // The server's save path is also where a store gets the chance to reclaim
                // dead bytes; throttled internally, and a no-op unless a shard is bloated.
                db.compactIfNeededThrottled();
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: flush failed " + db.directory(), ex);
            }
        }
    }

    /** Closes every open store regardless of reference count (server shutdown hook). */
    public static synchronized void closeAll() {
        var iterator = OPEN.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry e = iterator.next().getValue();
            try {
                e.db.flush();
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: flush failed " + e.db.directory(), ex);
            }
            try {
                e.db.close();
                if (e.db.isClosed()) {
                    iterator.remove();
                }
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: error closing " + e.db.directory(), ex);
                // Leave the entry registered so shutdown/release can retry the close.
            }
        }
    }
}
