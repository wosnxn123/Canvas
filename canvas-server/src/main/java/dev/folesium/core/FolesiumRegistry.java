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
import java.util.logging.StreamHandler;
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

    private static final boolean ZSTD_AVAILABLE = ZstdNative.available();
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean UTF8_LOGGING_INSTALLED = new AtomicBoolean();

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
        ensureUtf8Logging();
        if (fileProperties == null) {
            Properties p = new Properties();
            Path file = configFilePath();
            if (Files.isRegularFile(file)) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(reader);
                    LOGGER.log(System.Logger.Level.INFO, "Folesium: loaded configuration from {0}", file.toAbsolutePath());
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
     * every tunable option with a comment and a machine-tuned value, but leaves the engine
     * disabled ({@code enabled=false}) so an unconfigured server keeps vanilla Anvil behaviour.
     * Operators edit this file (or pass {@code -Dfolesium.*} system properties) to activate and
     * tune Folesium. The file is written exactly once; later runs load it as-is.
     *
     * <p>Important: {@link Properties#load} does not understand inline comments, so every
     * {@code key=value} is written on its own line with no trailing comment.</p>
     */
    private static void generateDefaultConfigFile(Path file) {
        FolesiumConfig tuned = autoTunedConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("# Folesium configuration - auto-generated by Folesium on first run.\n");
        sb.append("# Folesium is OPT-IN: 'enabled' is intentionally false, so the server behaves like stock\n");
        sb.append("# Folia/Canvas (writes .mca) until you set enabled=true. Edit values freely; this file is\n");
        sb.append("# written only once. System properties -Dfolesium.<key>=<value> override anything here.\n");
        sb.append("# Every value below can be changed while the server runs: Folesium notices that this file was\n");
        sb.append("# edited and applies it within a few seconds. Two exceptions: 'enabled' applies when a world is\n");
        sb.append("# next loaded, and 'shards' by an automatic reshard of the store on the next start.\n");
        sb.append("# Auto-tuned for this machine: ").append(describeMachine()).append('\n');
        sb.append('\n');
        sb.append("# Master switch. false = vanilla Anvil behaviour; true = use Folesium storage.\n");
        sb.append("enabled=false\n");
        sb.append('\n');
        sb.append("# Number of independent log shards per keyspace (must be a power of two, 1..1024).\n");
        sb.append("# More shards = more write parallelism; matched to this machine's CPU cores.\n");
        sb.append("shards=").append(tuned.shardCount()).append('\n');
        sb.append('\n');
        sb.append("# Durability: ALWAYS (fsync every write) | BATCH (background group commit) | EXPLICIT (fsync on flush/close).\n");
        sb.append("durability=").append(tuned.durability().name()).append('\n');
        sb.append('\n');
        sb.append("# Group-commit interval in milliseconds (used when durability=BATCH).\n");
        sb.append("batchFlushMillis=").append(tuned.batchFlushMillis()).append('\n');
        sb.append('\n');
        sb.append("# Per-record compression: NONE | DEFLATE | ZSTD. ZSTD is chosen automatically when zstd-jni is present.\n");
        sb.append("compression=").append(tuned.compression().name()).append('\n');
        sb.append('\n');
        sb.append("# Compression level. Valid range depends on the codec: DEFLATE 1-9, ZSTD 1-22.\n");
        sb.append("compressionLevel=").append(tuned.compressionLevel()).append('\n');
        sb.append('\n');
        sb.append("# Compact a shard when its dead (overwritten/deleted) bytes exceed this fraction of the file size.\n");
        sb.append("compactRatio=").append(tuned.compactRatio()).append('\n');
        sb.append('\n');
        sb.append("# Never compact shards smaller than this many bytes.\n");
        sb.append("compactMinBytes=").append(tuned.compactMinBytes()).append('\n');
        sb.append('\n');
        sb.append("# Re-verify each record's CRC32C on every read (~2x read I/O). Leave false unless diagnosing corruption.\n");
        sb.append("verifyChecksums=").append(tuned.verifyChecksums()).append('\n');
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
    private static FolesiumConfig autoTunedConfig() {
        int cores = Runtime.getRuntime().availableProcessors();
        boolean zstd = ZSTD_AVAILABLE;

        int shards;
        if (cores <= 4)       shards = 8;
        else if (cores <= 8)  shards = 16;
        else if (cores <= 16) shards = 32;
        else if (cores <= 32) shards = 64;
        else                  shards = 128;

        FolesiumConfig.Compression compression =
                zstd ? FolesiumConfig.Compression.ZSTD : FolesiumConfig.Compression.DEFLATE;
        int compressionLevel = 4;
        int batchFlushMillis = 500;
        double compactRatio = 0.5;
        long compactMinBytes = 8L * 1024 * 1024;
        boolean verifyChecksums = false;

        return new FolesiumConfig(
                shards,
                FolesiumConfig.DurabilityMode.BATCH,
                batchFlushMillis,
                compression,
                compressionLevel,
                compactRatio,
                compactMinBytes,
                verifyChecksums
        );
    }

    private static String describeMachine() {
        int cores = Runtime.getRuntime().availableProcessors();
        long maxMemMb = Runtime.getRuntime().maxMemory() / (1024L * 1024);
        boolean zstd = ZSTD_AVAILABLE;
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
        if (compression == FolesiumConfig.Compression.ZSTD && !ZSTD_AVAILABLE) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: compression=ZSTD requested but zstd-jni is not available, using {0}", d.compression());
            compression = d.compression();
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
            int fallback = FolesiumConfig.clampCompressionLevel(compression, d.compressionLevel());
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
        return new FolesiumConfig(
                shards,
                durability,
                batchFlushMillis,
                compression,
                level,
                compactRatio,
                compactMinBytes,
                boolProperty("verifyChecksums", d.verifyChecksums())
        );
    }

    /**
     * Re-reads {@code folesium.properties} (and {@code -Dfolesium.*}) and pushes the result to
     * every open store, so operators can retune a running server without restarting it.
     *
     * <p>{@code enabled} and {@code shards} are the two settings a reload cannot fully apply:
     * worlds bind their storage backend when they load, and the shard count is physical. Both
     * are reported back in the result instead of being silently dropped - {@code shards} is
     * then applied automatically by a reshard on the next start.</p>
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
                out.add(new ReloadReport(db.directory(), null, ex.getMessage()));
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

    private static void watchConfigFile() {
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
                synchronized (FolesiumRegistry.class) {
                    configWatcher = null;
                }
                return;
            }
            long stamp = configFileTimestamp();
            boolean changed;
            synchronized (FolesiumRegistry.class) {
                changed = stamp != -1L && stamp != configFileStamp;
                if (changed) {
                    configFileStamp = stamp;
                }
            }
            if (!changed) {
                continue;
            }
            try {
                // Let a half-written file settle: editors truncate first and write after.
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            LOGGER.log(System.Logger.Level.INFO,
                    "Folesium: {0} changed on disk, applying it to the running server", configFilePath());
            try {
                for (ReloadReport r : reload()) {
                    if (r.error() != null) {
                        LOGGER.log(System.Logger.Level.ERROR, "Folesium: {0}: {1}", r.directory(), r.error());
                    } else if (r.result().changed()) {
                        LOGGER.log(System.Logger.Level.INFO, "Folesium: {0}: {1}",
                                r.directory(), String.join(", ", r.result().changes()));
                    }
                    if (r.result() != null) {
                        for (String note : r.result().notes()) {
                            LOGGER.log(System.Logger.Level.WARNING, "Folesium: {0}: {1}", r.directory(), note);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: automatic reload failed", ex);
            }
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
        if (--entry.refCount < 0) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: reference count underflow for {0}", key);
            entry.refCount = 0;
        }
        if (entry.refCount == 0) {
            OPEN.remove(key);
            try {
                entry.db.close();
                LOGGER.log(System.Logger.Level.INFO, "Folesium: closed store {0}", key);
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: error closing {0}", key, ex);
            }
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
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: flush failed " + db.directory(), ex);
            }
        }
    }

    /** Closes every open store regardless of reference count (server shutdown hook). */
    public static synchronized void closeAll() {
        for (Entry e : OPEN.values()) {
            try {
                e.db.flush();
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: flush failed " + e.db.directory(), ex);
            }
        }
        for (Entry e : OPEN.values()) {
            try {
                e.db.close();
            } catch (RuntimeException ex) {
                LOGGER.log(System.Logger.Level.ERROR, "Folesium: error closing " + e.db.directory(), ex);
            }
        }
        OPEN.clear();
    }
}
