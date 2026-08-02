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

import dev.folesium.converter.PlayerPathRecognizer;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Drop-in replacements for the three {@code java.nio.file.Files} calls that
 * {@code PlayerAdvancements} and {@code ServerStatsCounter} use to read and write
 * {@code <world>/players/advancements/<uuid>.json} and
 * {@code <world>/players/stats/<uuid>.json}.
 *
 * <p>Both classes follow the same shape -- {@code Files.isRegularFile(path)} to decide
 * whether there is anything to load, then {@code Files.newBufferedReader} /
 * {@code Files.newBufferedWriter} for the JSON itself. Routing those three calls
 * through here redirects the data into the Folesium player store without touching any
 * of the surrounding parsing, codec or data-fixer logic, which keeps the server patch
 * to one changed line per call site.</p>
 *
 * <p>When Folesium is disabled, or the path is not a per-player JSON file of the active
 * world, every method falls straight through to {@code Files}, so behaviour is exactly
 * vanilla.</p>
 *
 * <p>The path classification itself lives in
 * {@link PlayerPathRecognizer} (vendored with the engine), so the rule can be
 * unit-tested without pulling in the {@code net.minecraft} packages -- see
 * {@code PlayerPathRecognizerTest}.</p>
 */
public final class FolesiumPlayerFiles {

    private static final System.Logger LOGGER = System.getLogger("Folesium");

    private FolesiumPlayerFiles() {
    }

    /** Which keyspace a per-player JSON path belongs to, or {@code null} if it is not one. */
    private record Target(FolesiumPlayerStorage storage, String directory, UUID player) {
    }

    /**
     * The recognizer for the currently active storage. Building one normalises the world
     * root, and these methods sit on the advancement/statistics save path of every player
     * on every autosave, so the instance is cached and only rebuilt when the active storage
     * actually changes (world unload/reload). Both fields are only ever written together
     * under {@link #RECOGNIZER_LOCK}.
     */
    private static final Object RECOGNIZER_LOCK = new Object();
    private static volatile FolesiumPlayerStorage recognizerOwner;
    private static volatile PlayerPathRecognizer recognizer;

    private static PlayerPathRecognizer recognizerFor(FolesiumPlayerStorage storage) {
        PlayerPathRecognizer cached = recognizer;
        if (cached != null && recognizerOwner == storage) {
            return cached;
        }
        synchronized (RECOGNIZER_LOCK) {
            if (recognizer == null || recognizerOwner != storage) {
                recognizer = new PlayerPathRecognizer(storage.worldRootForClassify());
                recognizerOwner = storage;
            }
            return recognizer;
        }
    }

    private static Target target(Path path) {
        FolesiumPlayerStorage storage = FolesiumPlayerStorage.active();
        if (storage == null || path == null) {
            return null;
        }
        PlayerPathRecognizer.Kind kind = recognizerFor(storage).classify(path);
        if (kind == null) {
            return null;
        }
        return new Target(storage, kind.directory(), kind.player());
    }

    private static String load(Target t) throws IOException {
        return t.directory().equals(PlayerPathRecognizer.DIR_ADVANCEMENTS)
                ? t.storage().loadAdvancements(t.player())
                : t.storage().loadStats(t.player());
    }

    private static void store(Target t, String json) {
        if (t.directory().equals(PlayerPathRecognizer.DIR_ADVANCEMENTS)) {
            t.storage().saveAdvancements(t.player(), json);
        } else {
            t.storage().saveStats(t.player(), json);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Files.* replacements                                                */
    /* ------------------------------------------------------------------ */

    /** Replacement for {@code Files.isRegularFile(path)}. */
    public static boolean isRegularFile(Path path) {
        Target t = target(path);
        if (t == null) {
            return Files.isRegularFile(path);
        }
        final boolean present;
        try {
            // `true` means "there is data to read" in the store; there is no
            // vanilla-file fallback (no lazy migration).
            present = load(t) != null;
        } catch (IOException e) {
            // load() only declares IOException (the store path never throws it today);
            // still, never turn a read failure into "no data".
            throw new java.io.UncheckedIOException(
                    "failed to read stored player JSON for " + t.player(), e);
        } catch (RuntimeException e) {
            // The engine reports store failures as FolesiumException (a RuntimeException).
            // Returning false here would make the vanilla call site treat the player as
            // new, and the next autosave would overwrite the stored progress JSON with
            // the fresh state -- permanent data loss. Surface the failure instead: the
            // vanilla call sites (PlayerAdvancements.load / ServerStatsCounter ctor)
            // cannot take a checked IOException at this position (their try-with-resources
            // starts after the isRegularFile call and neither method declares throws), so
            // the engine exception propagates unchecked and aborts the load, leaving the
            // stored record untouched. WARN so the failing store is visible.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: failed to read stored player JSON for " + t.player(), e);
            throw e;
        }
        return present;
    }

    /** Replacement for {@code Files.newBufferedReader(path, UTF_8)}. */
    public static Reader newBufferedReader(Path path) throws IOException {
        Target t = target(path);
        if (t == null) {
            return Files.newBufferedReader(path, StandardCharsets.UTF_8);
        }
        final String json;
        try {
            json = load(t);
        } catch (RuntimeException e) {
            // The engine reports store failures as FolesiumException (a RuntimeException),
            // which the vanilla call site does not catch. Surface it as an IOException --
            // this replacement's declared contract -- so the caller's existing IOException
            // handling applies.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: failed to load stored player JSON for " + t.player(), e);
            throw new IOException("failed to load stored player JSON for " + t.player(), e);
        }
        if (json == null) {
            throw new java.io.FileNotFoundException("no stored player JSON for " + t.player());
        }
        return new StringReader(json);
    }

    /**
     * Replacement for {@code Files.newBufferedWriter(path, UTF_8)}. The returned writer
     * buffers in memory and commits the record to the store on {@code close()}, which is
     * what makes the write atomic: a partially written record can never be observed,
     * unlike the vanilla truncate-then-write on the real file.
     */
    public static Writer newBufferedWriter(Path path) throws IOException {
        Target t = target(path);
        if (t == null) {
            return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }
        return new StoreWriter(t);
    }

    private static final class StoreWriter extends StringWriter {
        private final Target target;
        private boolean committed;

        StoreWriter(Target target) {
            super(8 * 1024);
            this.target = target;
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (!committed) {
                committed = true;
                try {
                    store(target, getBuffer().toString());
                } catch (RuntimeException e) {
                    // The engine signals store failures as FolesiumException (a
                    // RuntimeException), which vanilla's IOException handling around the
                    // autosave call site would not catch. Log and continue (vanilla's
                    // warn-and-continue semantics) so one player's failure never aborts
                    // the autosave for everyone.
                    LOGGER.log(System.Logger.Level.WARNING, "Folesium: failed to store player JSON for " + target.player(), e);
                }
            }
        }
    }
}
