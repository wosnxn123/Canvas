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
import java.time.Instant;
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
 *   <li>the machine-tuned {@link #autoTunedConfig()}</li>
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
    /** Mtime of the config file {@link #fileProperties} was loaded under, or -1 when no file
     *  backed the load (missing/read-only fallback). {@link #fileProperties()} re-reads the
     *  file when the current mtime differs, so an edit made while no store was open (and the
     *  watcher therefore stopped) is seen by the next acquire()/isEnabled() instead of a
     *  frozen cache -- the watcher's own {@link #configFileStamp} tracks a different, committed
     *  stamp and must not be reused here. */
    private static long filePropertiesStamp;
    private static Boolean enabledCache;

    /**
     * The enabled value worlds last bound, saved when a config edit lands between that
     * query and a later {@link #reload()}: {@link #isEnabled()} refreshes
     * {@link #enabledCache} to the edited value, so without this the
     * "enabled flips take effect on the next world load" warning in reload() silently
     * disappears whenever a world happens to load between the edit and the reload.
     */
    private static Boolean enabledBeforeEdit;

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
        if (fileProperties != null && !configFileChanged()) {
            return fileProperties;
        }
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
        // The cache is invalidated by configFileChanged() on every access: the file's mtime
        // is compared against the stamp it was loaded under, so an edit made while no store
        // was open (watcher stopped) is picked up by the next acquire()/isEnabled().
        // enabledCache is derived from this Properties and must not outlive a reload of it
        // (e.g. after the file was deleted and regenerated with the defaults).
        fileProperties = p;
        filePropertiesStamp = configFileTimestamp();
        enabledCache = null;
        return fileProperties;
    }

    /**
     * True when the config file's mtime differs from the stamp {@link #fileProperties} was
     * loaded under, i.e. the cached properties (and {@link #enabledCache}) are stale. A
     * missing file reads as -1, which only invalidates a cache loaded from a file that
     * existed (the file was deleted since), never the read-only fallback cache.
     */
    private static boolean configFileChanged() {
        return configFileTimestamp() != filePropertiesStamp;
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
    /**
     * The auto-tuned default configuration text that {@link #generateDefaultConfigFile}
     * writes. Deterministic per machine (the auto-tuned values and the machine description
     * do not change within a JVM run), which is what lets the config watcher recognise a
     * regenerated file - a deleted {@code folesium.properties} recreated by
     * {@link #fileProperties()} - and skip reloading it as if it were an operator edit.
     *
     * <p>Known boundary: the text embeds {@link #describeMachine()} (which reports the
     * zstd-jni availability) and the machine-tuned defaults, both fixed for the life of a
     * JVM in production ({@code ZstdNative.available()} caches its probe; cores and heap
     * do not change). Only the test-only {@code ZstdNative.setForcedUnavailable} switch
     * can change them mid-run, which would make a file generated before the flip fail the
     * exact-content comparison in {@link #reload()} / {@link #watchConfigFile()} and be
     * misread as an operator edit. The failure is confined to that test switch (no test
     * exercises the regeneration path today), and comparing key-value pairs instead of
     * full text would not fix it either: the flip also changes the generated
     * {@code compression}/{@code compressionLevel} defaults, and excluding those
     * machine-dependent keys would weaken the protection against operator edits of
     * {@code shards}/{@code compression}. Accepted as a known boundary.</p>
     */
    private static String defaultConfigContent() {
        // The generated file carries this machine's auto-tuned defaults (docs/AUTO-CONFIG.md),
        // so a fresh install already uses ZSTD + a shard count matched to its CPU cores.
        FolesiumConfig defaults = autoTunedConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("# Folesium configuration - auto-generated by Folesium on first run.\n");
        sb.append("# Folesium is OPT-IN: 'enabled' is intentionally false, so the server behaves like stock\n");
        sb.append("# Folia/Canvas (writes .mca) until you set enabled=true. Edit values freely; this file is\n");
        sb.append("# written only once. System properties -Dfolesium.<key>=<value> override anything here.\n");
        sb.append("# Most values below can be changed while the server runs: Folesium notices that this file was\n");
        sb.append("# edited and applies it within a few seconds. Exceptions: 'enabled' applies when a world is next\n");
        sb.append("# loaded, 'shards' by an automatic reshard of the store on the next start, and indexCacheBytes /\n");
        sb.append("# indexMode / dictionaryCompression / backupOnConvert on the next store open or conversion.\n");
        sb.append("# 'autoReload' itself is read when the server starts: it decides whether this file is watched at\n");
        sb.append("# all, so changing it takes effect only on the next start (restart after flipping it).\n");
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
        return sb.toString();
    }

    private static void generateDefaultConfigFile(Path file) {
        String content = defaultConfigContent();
        try {
            // Write through a sibling temporary file and an atomic rename: a reader (the
            // config watcher poll or another acquire) must never observe a truncated or
            // half-written config, and a crash mid-write must not leave one behind.
            FolesiumDatabase.writeAtomically(file, content);
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
        // Boolean.parseBoolean silently maps every unrecognised value to false; a typo
        // like folesium.enabled=treu would quietly flip the engine off. Warn instead,
        // mirroring intProperty()/doubleProperty(), so 'folesium.<key>=<bad>' is
        // diagnosable.
        String value = property(key, Boolean.toString(def));
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: bad boolean for folesium.{0}, using {1}", key, def);
            return def;
        }
        return Boolean.parseBoolean(value);
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
     * A config file that exists but cannot be read is treated as disabled (with a WARNING):
     * Folesium is opt-in, so a config read failure never crashes the world load path.
     *
     * <p>The cached result is invalidated when the config file's mtime changes (see
     * {@link #configFileChanged()}), so an operator edit that happened while no store was
     * open -- the config watcher is stopped then -- is honoured by the next world load
     * instead of being silently ignored by a frozen cache.</p>
     */
    public static synchronized boolean isEnabled() {
        if (enabledCache == null || configFileChanged()) {
            if (enabledCache != null) {
                // An edit landed since the last query and the cache held the value
                // worlds actually bound: keep it for reload()'s enabled-flip warning
                // (see enabledBeforeEdit).
                enabledBeforeEdit = enabledCache;
            }
            enabledCache = null;
            try {
                enabledCache = boolProperty("enabled", false);
            } catch (UncheckedIOException e) {
                // A config file that exists but cannot be read (fileProperties() throws
                // UncheckedIOException) must not crash the world load path: Folesium is
                // opt-in, so degrade to disabled. The outcome is cached like every other
                // isEnabled() result; reload()/the config watcher re-read the file and
                // clear the cache when it becomes readable again.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: cannot read {0} ({1}); treating Folesium as disabled (opt-in:"
                                + " a config read failure must not crash the server)",
                        configFilePath().toAbsolutePath(), e.toString());
                enabledCache = false;
            }
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
     * The effective configuration for the property-reading {@link #acquire(Path)} entry
     * points, or {@code null} when the config file exists but cannot be read
     * ({@code fileProperties()} throws {@link UncheckedIOException}): like
     * {@link #isEnabled()}, a read failure degrades to disabled instead of crashing the
     * world load path (opt-in contract), with a WARNING logged.
     */
    private static FolesiumConfig configuredOrDefault() {
        try {
            return configFromProperties();
        } catch (UncheckedIOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: cannot read {0} ({1}); treating Folesium as disabled (opt-in:"
                            + " a config read failure must not crash the server)",
                    configFilePath().toAbsolutePath(), e.toString());
            return null;
        }
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
        // Programmatic reloads apply the same parse precheck as the watcher: an existing
        // file with a malformed backslash-u escape must not silently fall back to the
        // auto-tuned defaults (fileProperties() discards the unparseable prefix and yields
        // defaults) - keep the previous configuration instead. parsesAsProperties() logged
        // the clear error. A missing file proceeds from system properties + defaults only
        // when -Dfolesium.* overrides exist; otherwise there is nothing to apply and the
        // default file must not be regenerated (see below).
        Path configFile = configFilePath();
        if (Files.isRegularFile(configFile)) {
            String content = configFileContent();
            if (content == null) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Folesium: cannot read {0} for reload; keeping the previous configuration",
                        configFile.toAbsolutePath());
                return List.of();
            }
            if (!parsesAsProperties(content)) {
                return List.of();
            }
            // Same regenerated-file protection as the watcher (see watchConfigFile): a file
            // our own machinery recreated (the previous file was deleted and fileProperties()
            // regenerated the auto-tuned default) is not an operator edit. Applying the
            // defaults would silently override the running server's current configuration,
            // so skip it exactly like the watcher does - UNLESS the operator explicitly
            // drives this reload through -Dfolesium.* system properties (the documented
            // programmatic-reload contract): then the file is only one input and the
            // properties must still be applied. The generated content is deterministic per
            // machine, so this comparison is exact.
            if (content.equals(defaultConfigContent()) && !hasSystemPropertyOverrides()) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Folesium: {0} is the auto-generated default (previously deleted?);"
                                + " keeping the running configuration",
                        configFile.toAbsolutePath());
                // Same reconciliation as the watcher's regeneration skip (watchConfigFile):
                // the file on disk is the regenerated default, so a fileProperties /
                // enabledCache loaded from the deleted pre-regeneration file must not be
                // served by the next acquire()/isEnabled() - clear both so the next access
                // re-reads the file unconditionally.
                synchronized (FolesiumRegistry.class) {
                    fileProperties = null;
                    enabledCache = null;
                    enabledBeforeEdit = null;
                }
                return List.of();
            }
        } else if (!hasSystemPropertyOverrides()) {
            // A missing config file is not an operator edit: proceeding would make
            // fileProperties() regenerate the auto-tuned default file and push those
            // defaults onto the running stores, silently overriding the current
            // configuration - the same regenerated-file protection the watcher applies
            // (it recognises the generated content and skips it, see watchConfigFile).
            // With neither a file nor -Dfolesium.* overrides there is nothing to apply:
            // keep the running configuration and report nothing, without regenerating the
            // file. System-property-only deployments still reload, because their values
            // are real overrides, not regenerated defaults.
            return List.of();
        }
        List<FolesiumDatabase> snapshot;
        FolesiumConfig cfg;
        boolean enabledBefore;
        try {
            synchronized (FolesiumRegistry.class) {
                // Capture the previous effective value from the cache BEFORE isEnabled()
                // re-reads the (already edited) file: isEnabled() refreshes the cache when
                // the file changed, so calling it here first would make both sides of the
                // comparison below read the new value and the warning dead code. The cache
                // holds the value running worlds actually bound; null means no world has
                // loaded since the last cache clear (nothing to warn about), so fall back
                // to isEnabled() to keep the comparison harmless. A world load between the
                // edit and this reload refreshes enabledCache to the new value - the
                // pre-edit value is then taken from enabledBeforeEdit, saved by isEnabled()
                // exactly for this warning.
                Boolean beforeEdit = enabledBeforeEdit;
                enabledBeforeEdit = null;
                enabledBefore = beforeEdit != null
                        ? beforeEdit
                        : (enabledCache != null ? enabledCache : isEnabled());
                fileProperties = null;
                enabledCache = null;
                cfg = configFromProperties();
                snapshot = openDatabases();
            }
        } catch (UncheckedIOException e) {
            // The reload re-reads the file inside the lock (configFromProperties() ->
            // property() -> fileProperties(), whose cache was cleared above), and a file
            // that exists but cannot be read right now throws UncheckedIOException. That
            // must not escape a programmatic reload: the watcher guards its own reload()
            // call with the same catch, and a direct caller gets the same treatment here
            // - keep the previous configuration and report an empty result instead of
            // throwing (the change is retried the next time reload() is called).
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: cannot read {0} for reload; keeping the previous configuration: {1}",
                    configFilePath().toAbsolutePath(), e.toString());
            return List.of();
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
        if (configWatcher != null) {
            return;
        }
        // Baseline the watcher at the last APPLIED configuration stamp instead of the
        // current file stamp: boolProperty(...) -> fileProperties() re-reads the file
        // when its mtime changed and commits that read into filePropertiesStamp, so a
        // probe-first order would baseline the watcher at the post-edit stamp and the
        // very edit that started it would never be detected or applied to the running
        // stores. This also covers an edit that landed while the watcher was stopped
        // (e.g. an autoReload=false->true flip that also retuned other settings): the
        // probe absorbs it, but the baseline stays at the last-applied stamp, so the
        // first poll sees the absorbed edit as a change and applies it. With no
        // outstanding edit the stamps agree and the first poll applies nothing.
        long appliedStamp = filePropertiesStamp;
        if (!boolProperty("autoReload", true)) {
            return;
        }
        configFileStamp = appliedStamp;
        Thread t = Thread.ofPlatform().daemon().name("folesium-config-watch")
                .unstarted(FolesiumRegistry::watchConfigFile);
        configWatcher = t;
        t.start();
    }

    private static long configFileTimestamp() {
        Path file = configFilePath();
        try {
            if (!Files.isRegularFile(file)) {
                return -1L;
            }
            // Nanosecond-precision epoch: toMillis() truncates, so two writes within the
            // same millisecond would be indistinguishable and the change would be missed
            // by the watcher and configFileChanged(). Preserving the Instant's nanos makes
            // same-millisecond writes visible. (Epoch nanos fit comfortably in a long.)
            Instant t = Files.getLastModifiedTime(file).toInstant();
            return t.getEpochSecond() * 1_000_000_000L + t.getNano();
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
     * Whether any {@code -Dfolesium.*} system property is set. Used by {@link #reload()} to
     * tell a headless deployment that configures Folesium entirely through system properties
     * (which must keep applying even without a config file) apart from a truly unconfigured
     * server (where a reload must not regenerate the default file and push its defaults onto
     * the running stores).
     */
    private static boolean hasSystemPropertyOverrides() {
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("folesium.")) {
                return true;
            }
        }
        return false;
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
                    try {
                        seconds = Math.max(1, intProperty("autoReloadSeconds", 10));
                    } catch (UncheckedIOException e) {
                        // The poll head re-reads the config file (intProperty -> property ->
                        // fileProperties()); a transient read failure - e.g. an editor is
                        // still writing - must not kill the watcher (same guard as the
                        // reload path below). Log a warning and fall back to the default
                        // interval instead of continuing straight back to the poll head: a
                        // persistent read failure must not busy-spin the watcher. Every
                        // round still sleeps (autoReloadSeconds, 10 by default), so the
                        // retry happens on the next poll exactly as before.
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Folesium: cannot read {0} to determine the reload interval;"
                                        + " will retry on the next poll: {1}",
                                configFilePath().toAbsolutePath(), e.toString());
                        seconds = 10;
                    }
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
                // A file that our own machinery regenerated (the previous file was deleted and
                // fileProperties() recreated the auto-tuned default) is not an operator edit:
                // applying the defaults would silently override the running server's current
                // configuration. Recognise it by its exact generated content (deterministic
                // per machine) and skip the reload. The stamp is committed so the skip is not
                // re-evaluated - and the message re-logged - on every poll until the
                // operator actually edits the file.
                if (after.equals(defaultConfigContent())) {
                    LOGGER.log(System.Logger.Level.INFO,
                            "Folesium: {0} was regenerated (previously deleted?); keeping the running configuration",
                            configFilePath().toAbsolutePath());
                    synchronized (FolesiumRegistry.class) {
                        configFileStamp = stamp;
                        // The skip commits the stamp but must not leave the properties /
                        // enabled caches serving the pre-regeneration content: the file on
                        // disk already reads as the regenerated default, so a cache still
                        // holding the deleted file's values (e.g. enabled=true) would be
                        // served by the next acquire()/isEnabled() until a later mtime
                        // change happened to re-read it. Clear both here so the next access
                        // re-reads the file unconditionally (same reconciliation that
                        // reload() performs).
                        fileProperties = null;
                        enabledCache = null;
                        enabledBeforeEdit = null;
                    }
                    continue;
                }
                LOGGER.log(System.Logger.Level.INFO,
                        "Folesium: {0} changed on disk, applying it to the running server", configFilePath());
                boolean anyStoreFailed = false;
                try {
                    for (ReloadReport r : reload()) {
                        if (r.error() != null) {
                            // One failing store must not commit the stamp: the change is
                            // retried on the next poll (the same promise as the exception
                            // paths below), so a transient per-store failure cannot be
                            // skipped forever.
                            anyStoreFailed = true;
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
                } catch (UncheckedIOException e) {
                    // reload() re-reads the file to apply the change (fileProperties() throws
                    // UncheckedIOException on a read failure); a transient failure - e.g. the
                    // editor is still writing - must not kill the watcher. Log a warning and
                    // keep the stamp stale so the change is retried on the next poll instead
                    // of being silently skipped forever.
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Folesium: cannot re-read {0} while applying it, will retry on the next poll: {1}",
                            configFilePath().toAbsolutePath(), e.toString());
                    continue;
                } catch (RuntimeException ex) {
                    LOGGER.log(System.Logger.Level.ERROR, "Folesium: automatic reload failed", ex);
                    // The stamp stays stale, so the change is retried on the next poll.
                    continue;
                }
                if (anyStoreFailed) {
                    // At least one store rejected the change: leave the stamp stale so the
                    // whole change is retried on the next poll (the same promise as the
                    // exception paths above) instead of being skipped forever.
                    continue;
                }
                // The reload ran to completion without an exception and every store accepted
                // the new configuration: only now is the stamp committed. A change whose
                // reload failed (above) leaves the stamp stale and is retried on the next
                // poll instead of being skipped forever.
                synchronized (FolesiumRegistry.class) {
                    configFileStamp = stamp;
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

    /**
     * Opens (or joins) the dimension store in {@code dir} and increments its reference count.
     *
     * <p>An already-open store is always joined without consulting the configuration: a store
     * binds its backend at open time, so a transiently unreadable config file (e.g. while
     * the watcher or an editor rewrites it) never misreports an open store as disabled.
     * Only a store that is not open yet reads the config file.</p>
     *
     * @return the store, or {@code null} when the store is not open yet and the config file
     *         exists but cannot be read - a read failure degrades to disabled (Folesium is
     *         opt-in: a config read failure never crashes the world load path) and is
     *         logged as a WARNING
     */
    public static synchronized FolesiumDatabase acquire(Path dir) {
        return acquire(dir, null, FolesiumDatabase.StoreRole.DIMENSION);
    }

    public static synchronized FolesiumDatabase acquire(Path dir, FolesiumConfig config) {
        return acquire(dir, config, FolesiumDatabase.StoreRole.DIMENSION);
    }

    /**
     * Opens (or joins) the store in {@code dir} with the given role, using configured defaults.
     *
     * <p>An already-open store is always joined without consulting the configuration (see
     * {@link #acquire(Path)}).</p>
     *
     * @return the store, or {@code null} when the store is not open yet and the config file
     *         exists but cannot be read - a read failure degrades to disabled (see
     *         {@link #acquire(Path)})
     */
    public static synchronized FolesiumDatabase acquire(Path dir, FolesiumDatabase.StoreRole role) {
        return acquire(dir, null, role);
    }

    /**
     * Opens (or joins) the store in {@code dir} with the given role.
     *
     * <p>An already-open store is joined before any config file access - the config argument
     * is ignored on that path (a store binds its backend at open time), so a transiently
     * unreadable config file never misreports an open store as disabled; the watcher restart
     * is best-effort only. The configuration is consulted exclusively on the open path: the
     * property-reading forms pass {@code null} and resolve it here, degrading to disabled
     * ({@code null}) when the config file exists but cannot be read; the explicit-config
     * forms pass a ready config, and only the watcher's {@code autoReload} read can still
     * fail, which logs a warning and leaves the watcher off without disabling the store
     * (opt-in: a config read failure never crashes the world load path).</p>
     */
    public static synchronized FolesiumDatabase acquire(Path dir, FolesiumConfig config, FolesiumDatabase.StoreRole role) {
        ensureShutdownHook();
        Path key = canonical(dir);
        Entry entry = OPEN.get(key);
        if (entry != null && !entry.db.isClosed()) {
            // Join an already-open store before any config file access. The watcher restart
            // must not fail the join either: a transient read failure here logs and joins
            // anyway (the watcher retries on its own polling).
            try {
                ensureConfigWatcher();
            } catch (UncheckedIOException e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: cannot read {0} ({1}); the config watcher will not be"
                                + " (re)started now",
                        configFilePath().toAbsolutePath(), e.toString());
            }
            if (entry.db.role() != role) {
                throw new FolesiumException("Folesium store " + key + " is already open as "
                        + entry.db.role() + "; cannot also open it as " + role);
            }
            entry.refCount++;
            return entry.db;
        }
        // The store is not open yet - the only path that consults the configuration. The
        // property-reading acquire() forms pass null and resolve it here, degrading to
        // disabled (null) when the config file exists but cannot be read.
        if (config == null) {
            config = configuredOrDefault();
            if (config == null) {
                return null;
            }
        }
        try {
            ensureConfigWatcher();
        } catch (UncheckedIOException e) {
            // ensureConfigWatcher() -> boolProperty("autoReload", ...) -> property() ->
            // fileProperties() re-reads the config file, which throws UncheckedIOException
            // when the file exists but cannot be read. The explicit-config forms already
            // hold a ready config, so only the watcher's autoReload probe can fail here;
            // that must not disable the store: log and continue exactly like the join
            // path above (the watcher stays off and retries on its own polling - a config
            // read failure never crashes the world load path).
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: cannot read {0} ({1}); the config watcher will not be"
                            + " started now",
                    configFilePath().toAbsolutePath(), e.toString());
        }
        entry = new Entry(FolesiumDatabase.open(key, config, role));
        OPEN.put(key, entry);
        LOGGER.log(System.Logger.Level.INFO,
                "Folesium: opened {0} store {1} (shards={2}, durability={3}, compression={4})",
                entry.db.role(), key, entry.db.config().shardCount(),
                entry.db.config().durability(), entry.db.config().compression());
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
                // The save thread is never the group-commit flusher, so a pass it drives
                // must not abort when the store's flusher is retired.
                db.compactIfNeededThrottled(false);
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
