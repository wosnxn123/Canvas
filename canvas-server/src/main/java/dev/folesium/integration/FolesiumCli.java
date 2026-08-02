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

package dev.folesium.integration;

import dev.folesium.converter.WorldConversionService;
import dev.folesium.core.FolesiumConfig;
import dev.folesium.core.FolesiumRegistry;
import joptsimple.OptionSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Hook for the two Cesium-style startup flags:
 * <pre>
 *   --folesiumConvertToFolesium          # convert Anvil -> Folesium, then exit
 *   --folesiumConvertToAnvil             # convert Folesium -> Anvil, then exit
 *   --folesiumWorldDir &lt;path&gt;             # override -W for the conversion target
 * </pre>
 *
 * <p>Called from {@code org.bukkit.craftbukkit.Main#main} right after joptsimple
 * parses the argv and before any MC bootstrap. The handler is the ONLY place in
 * the server that uses the {@code dev.folesium.converter} package -- keeping it
 * here means the converter does not leak into the runtime classpath otherwise.</p>
 *
 * <p>The conversion runs against {@code -W &lt;worldDir&gt;}/{@code <worldName>},
 * matching the same path the rest of the server uses; when {@code -W} is not
 * supplied the server's default world container (the working directory) is used,
 * so a bare {@code --folesiumConvert*} invocation converts the same world a plain
 * server start would load. If the user supplied {@code --folesiumWorldDir}, that
 * path is used verbatim (no level-name appended), so operators can point it at
 * any directory containing Anvil subdirs.</p>
 */
public final class FolesiumCli {

    private static final Logger LOGGER = Logger.getLogger("Folesium");

    private FolesiumCli() {}

    /**
     * Inspects the parsed joptsimple options and, if a {@code --folesiumConvert*}
     * flag is present, runs the in-place conversion and returns {@code true}.
     * The caller is expected to {@code return} from {@code main} when this returns
     * {@code true} so the server does not start up afterwards -- the conversion
     * is the entire purpose of the invocation.
     */
    public static boolean handle(OptionSet options) {
        boolean toFolesium = options.has("folesiumConvertToFolesium");
        boolean toAnvil    = options.has("folesiumConvertToAnvil");
        if (!toFolesium && !toAnvil) {
            return false;
        }
        if (toFolesium && toAnvil) {
            LOGGER.severe("--folesiumConvertToFolesium and --folesiumConvertToAnvil are mutually exclusive");
            System.exit(2);
        }

        Path worldDir = resolveWorldDir(options);
        if (!java.nio.file.Files.isDirectory(worldDir)) {
            LOGGER.severe("Folesium: world directory does not exist: " + worldDir);
            System.exit(2);
        }

        WorldConversionService.Direction dir = toFolesium
                ? WorldConversionService.Direction.TO_FOLESIUM
                : WorldConversionService.Direction.TO_ANVIL;

        // The operator's own configuration, not the library defaults: a store created here
        // with defaults() would be stamped with a shard count / codec the running server
        // never asked for, forcing a full reshard on the first real start.
        FolesiumConfig config = FolesiumRegistry.configFromProperties();
        LOGGER.info(() -> "Folesium: starting " + dir + " for " + worldDir
                + " (shards=" + config.shardCount() + ", compression=" + config.compression() + ")");
        try {
            WorldConversionService.Report rep = new WorldConversionService()
                    .convertWorld(worldDir, dir, config);
            LOGGER.info(() -> "Folesium: " + rep);
            return true;
        } catch (Exception e) {
            LOGGER.severe("Folesium: conversion failed: " + e);
            e.printStackTrace();
            System.exit(1);
            return true; // unreachable
        }
    }

    private static Path resolveWorldDir(OptionSet options) {
        if (options.has("folesiumWorldDir")) {
            // Same File-typed guard as -W/--world-dir below: both options are declared
            // File-typed by CraftBukkit/Folia/Canvas today, but if upstream ever
            // re-declares this one as String, a ClassCastException here would escape the
            // caller's try/catch and abort startup with a confusing stack trace instead
            // of a clean conversion error.
            File folesium = fileOrNull(options.valueOf("folesiumWorldDir"));
            if (folesium == null) {
                LOGGER.severe("Folesium: --folesiumWorldDir did not resolve to a File value");
                System.exit(2);
            }
            return folesium.toPath();
        }
        // Mirror what DedicatedServer uses: <world-container>/<world-name>
        // We replicate the resolution here instead of pulling in MC internals so
        // this hook stays callable before Bootstrap.bootStrap().
        //
        // Without -W/--world-dir the server's default world container is the working
        // directory (DedicatedServerOptions defaults world-dir to "."), so a bare
        // --folesiumConvert* run converts exactly the world a plain server start
        // would load -- the same behaviour verify-server.sh exercises. The File-typed
        // guard only rejects an explicitly supplied value that cannot be a File
        // (if upstream ever re-declares this option as String, a ClassCastException
        // here would escape the caller's try/catch and abort startup with a
        // confusing stack trace instead of a clean conversion error).
        File container;
        if (options.has("world-dir")) {
            container = fileOrNull(options.valueOf("world-dir"));
            if (container == null) {
                LOGGER.severe("Folesium: -W/--world-dir did not resolve to a File value");
                System.exit(2);
            }
        } else {
            container = new File(".");
        }
        Object worldValue = options.has("world") ? options.valueOf("world") : null;
        String levelName = worldValue == null ? readLevelName()
                : worldValue instanceof File f ? f.getName() : String.valueOf(worldValue);
        return new File(container, levelName).toPath();
    }

    private static File fileOrNull(Object value) {
        return value instanceof File f ? f : null;
    }

    /**
     * Reads {@code level-name} from the server working directory's
     * {@code server.properties}, defaulting to {@code world}. The conversion runs
     * before Bootstrap, so the level name is not yet available on any parsed options
     * object when {@code -w/--level-name} was not passed explicitly.
     */
    private static String readLevelName() {
        Path props = Path.of("server.properties");
        if (Files.isRegularFile(props)) {
            try (var in = Files.newInputStream(props)) {
                Properties p = new Properties();
                p.load(in);
                String name = p.getProperty("level-name");
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (IOException ignored) {
                // fall through to default
            }
        }
        return "world";
    }
}
