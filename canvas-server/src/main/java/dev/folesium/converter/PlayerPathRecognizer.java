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

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Pure-Java (no {@code net.minecraft} dependency) classification of a path into
 * "is this a per-player advancement/stats JSON of a world whose player store is
 * active?".
 *
 * <p>The on-server {@code FolesiumPlayerFiles} class calls this with the
 * active-world root to decide whether to redirect the
 * {@link java.nio.file.Files#isRegularFile} /
 * {@link java.nio.file.Files#newBufferedReader} /
 * {@link java.nio.file.Files#newBufferedWriter} calls inside
 * {@code PlayerAdvancements} and {@code ServerStatsCounter}. Pulling the rule into
 * a vanilla-free helper lets the converter test suite cover it directly, which is
 * the only way to make sure the path-pattern matching doesn't regress when Mojang
 * changes filename conventions.</p>
 */
public final class PlayerPathRecognizer {

    /** Vanilla directory for {@code <uuid>.json} advancement progress. */
    public static final String DIR_ADVANCEMENTS = "advancements";
    /** Vanilla directory for {@code <uuid>.json} statistics. */
    public static final String DIR_STATS = "stats";
    /** Vanilla 26.x container grouping the per-player directories under the world root. */
    public static final String DIR_PLAYERS = "players";

    private final Path worldRoot;
    private final String advancementsDir;
    private final String statsDir;

    public PlayerPathRecognizer(Path worldRoot) {
        this.worldRoot = worldRoot == null ? null : worldRoot.toAbsolutePath().normalize();
        this.advancementsDir = DIR_ADVANCEMENTS;
        this.statsDir = DIR_STATS;
    }

    /** Which player data type this path refers to, or {@code null} if it isn't one. */
    public Kind classify(Path path) {
        if (worldRoot == null || path == null) {
            return null;
        }
        Path file = path.getFileName();
        Path dir = path.getParent();
        if (file == null || dir == null) {
            return null;
        }
        String name = file.toString();
        if (!name.endsWith(".json")) {
            return null;
        }
        Path dirName = dir.getFileName();
        if (dirName == null) {
            return null;
        }
        String directory = dirName.toString().toLowerCase(Locale.ROOT);
        Kind kind;
        if (directory.equals(advancementsDir)) {
            kind = new Kind(advancementsDir, parseUuid(name.substring(0, name.length() - ".json".length())));
        } else if (directory.equals(statsDir)) {
            kind = new Kind(statsDir, parseUuid(name.substring(0, name.length() - ".json".length())));
        } else {
            return null;
        }
        if (kind.player == null) {
            return null;
        }
        if (!isPerPlayerDir(dir)) {
            return null;
        }
        return kind;
    }

    /**
     * True when {@code dir} -- the {@code advancements}/ {@code stats} directory
     * holding the file -- is a vanilla per-player directory of this world. Two
     * layouts match: directly under the world root (pre-26.x:
     * {@code <world>/advancements}), and under the 26.x {@code <world>/players}
     * container ({@code <world>/players/advancements}).
     */
    private boolean isPerPlayerDir(Path dir) {
        Path parent = dir.getParent();
        if (parent == null) {
            return false;
        }
        Path parentNormalized = parent.toAbsolutePath().normalize();
        if (worldRoot.equals(parentNormalized)) {
            return true;
        }
        // 26.x layout: the per-player directories are grouped under <world>/players.
        Path playersParent = parent.getParent();
        return playersParent != null
                && DIR_PLAYERS.equalsIgnoreCase(parent.getFileName().toString())
                && worldRoot.equals(playersParent.toAbsolutePath().normalize());
    }

    /** Outcome of {@link #classify(Path)} when the path IS a player data file. */
    public record Kind(String directory, UUID player) {}

    private static UUID parseUuid(String s) {
        if (s.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
