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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Process-wide, reference-counted registry of open {@link FolesiumDatabase} instances.
 *
 * <p>The server opens one {@code RegionFileStorage} per (dimension, data type)
 * — {@code region}, {@code poi} and {@code entities} — but all three must share
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

    private static Properties fileProperties;
    private static Boolean enabledCache;

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

    private static synchronized Properties fileProperties() {
        if (fileProperties == null) {
            Properties p = new Properties();
            Path file = Path.of(System.getProperty("folesium.configFile", CONFIG_FILE));
            if (Files.isRegularFile(file)) {
                try (var in = Files.newInputStream(file)) {
                    p.load(in);
                    LOGGER.log(System.Logger.Level.INFO, "Folesium: loaded configuration from {0}", file.toAbsolutePath());
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read " + file.toAbsolutePath(), e);
                }
            }
            fileProperties = p;
        }
        return fileProperties;
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
        FolesiumConfig d = FolesiumConfig.defaults();
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
        return new FolesiumConfig(
                intProperty("shards", d.shardCount()),
                durability,
                intProperty("batchFlushMillis", d.batchFlushMillis()),
                compression,
                intProperty("compressionLevel", d.compressionLevel()),
                Double.parseDouble(property("compactRatio", Double.toString(d.compactRatio()))),
                Long.parseLong(property("compactMinBytes", Long.toString(d.compactMinBytes()))),
                boolProperty("verifyChecksums", d.verifyChecksums())
        );
    }

    /* ------------------------------------------------------------------ */
    /* registry                                                            */
    /* ------------------------------------------------------------------ */

    private static Path canonical(Path dir) {
        return dir.toAbsolutePath().normalize();
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
        Path key = canonical(dir);
        Entry entry = OPEN.get(key);
        if (entry == null) {
            return;
        }
        if (--entry.refCount <= 0) {
            OPEN.remove(key);
            entry.db.close();
            LOGGER.log(System.Logger.Level.INFO, "Folesium: closed store {0}", key);
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
    public static synchronized void flushAll() {
        for (Entry e : OPEN.values()) {
            e.db.flush();
        }
    }

    /** Closes every open store regardless of reference count (server shutdown hook). */
    public static synchronized void closeAll() {
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
