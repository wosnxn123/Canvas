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

import dev.folesium.core.shard.ShardFile;
import dev.folesium.core.util.Bytes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rewrites a store with a different shard count.
 *
 * <p>{@code shardCount} is the one setting that cannot simply be swapped at runtime: it
 * selects the shard a key lives in ({@code mix64(key) & (shardCount - 1)}) and is stamped
 * into every shard file header. Changing it therefore means physically rewriting every
 * record. Folesium does this automatically the next time the store is opened, so that
 * editing {@code shards} in {@code folesium.properties} actually takes effect instead of
 * being silently ignored.</p>
 *
 * <h2>Crash safety</h2>
 * <p>The rewrite is a three-phase commit. The original shard files are not touched until
 * the complete new set exists on disk and has been fsynced:</p>
 * <ol>
 *   <li><b>Stage</b> - build the full new shard set under {@code .folesium-reshard/}.
 *       The live store is untouched; a crash here loses nothing.</li>
 *   <li><b>Commit</b> - fsync the staging directory, write the {@code COMMIT} marker, then
 *       update {@code store.shardCount} in the metadata. From this point the new layout is
 *       authoritative and the swap will be finished (never rolled back).</li>
 *   <li><b>Swap</b> - move the old shard files aside into {@code .folesium-reshard-old/}
 *       (recording completion with a {@code MOVED} marker), move the staged files in, then
 *       delete both scratch directories.</li>
 * </ol>
 *
 * <p>{@link #recover(Path)} runs before every store open and makes any interrupted reshard
 * converge: staging without {@code COMMIT} is discarded, staging with {@code COMMIT} is
 * driven to completion. The {@code MOVED} marker is what makes phase 3 re-runnable - without
 * it, a retry could mistake already-swapped-in new files for leftovers of the old set.</p>
 *
 * <p>Three ordering invariants make recovery safe. The shard-count metadata is updated
 * before the swap evidence is destroyed, so a crash can never leave the metadata and the
 * shard files disagreeing. The rebuildable page index is invalidated before the swap
 * evidence is destroyed, so a crash can never pair old-layout region pages with the
 * new-layout logs on the next open. And the old shard files are only deleted once every
 * new shard is confirmed present in the store directory, so a crash in the middle of
 * phase 3 can never destroy the only surviving copy of the records.</p>
 *
 * <p>Not thread-safe and not multi-process safe: it is only ever called from the
 * {@link FolesiumDatabase} constructor, before any keyspace exists and before the store is
 * published to other threads.</p>
 */
final class StoreResharder {

    private static final System.Logger LOGGER = System.getLogger("Folesium");

    static final String STAGING_DIR = ".folesium-reshard";
    static final String BACKUP_DIR = ".folesium-reshard-old";
    private static final String COMMIT_MARKER = "COMMIT";
    private static final String MOVED_MARKER = "MOVED";

    /** {@code <keyspace>-<NNNN>.flog}, matching {@link Keyspace}'s naming. */
    private static final Pattern SHARD_FILE = Pattern.compile("^(.+)-(\\d{4})\\.flog$");

    /**
     * Trained dictionary file inside each keyspace's {@code idx/<name>/} directory. Unlike
     * the page-index products around it (page files, hint, watermarks), it is not rebuildable:
     * codec-3 (ZSTD_DICT) records decode against it, so {@link #invalidatePageIndex} must
     * preserve it.
     */
    private static final String DICT_FILE = "dict.bin";

    private StoreResharder() {
    }

    // --------------------------------------------------------------- recovery

    /**
     * Completes or discards an interrupted reshard. Cheap no-op when no reshard is in
     * flight, so it is safe to call on every open; it also repairs metadata that a crash
     * left disagreeing with the shard files (see {@link #reconcileStaleMetadata}).
     *
     * <p><b>Side effects:</b> whenever an interrupted reshard is detected this method
     * performs layout-changing writes: it moves shard files between the store directory
     * and the backup tree, deletes both scratch trees, rewrites {@code store.shardCount}
     * (and {@code store.previousShardCount}/{@code store.reshardedAt}) and drops the
     * rebuildable page index. It must therefore only be called on a read-write open
     * ({@code applyLayoutChanges == true}); a read-only open ({@code applyLayoutChanges
     * == false}) must skip the call entirely rather than run these writes. Skipping is
     * safe: a read-only open never writes, so the worst case is a torn read of a store
     * whose reshard was interrupted mid-swap, and the next read-write open repairs the
     * layout before any keyspace is used. The read-only guard belongs at the single call
     * site in the {@link FolesiumDatabase} constructor, not in this signature.</p>
     */
    static void recover(Path dir) {
        Path staging = dir.resolve(STAGING_DIR);
        Path backup = dir.resolve(BACKUP_DIR);
        boolean hasStaging = Files.isDirectory(staging);
        boolean hasBackup = Files.isDirectory(backup);
        Path movedMarker = backup.resolve(MOVED_MARKER);
        if (!hasStaging && !hasBackup) {
            // No reshard is in flight. The only possible damage is metadata that still
            // names the old shard count while the files already use the new layout - a
            // crash that destroyed the swap evidence before the metadata was updated.
            reconcileStaleMetadata(dir);
            return;
        }
        if (hasStaging && Files.isRegularFile(staging.resolve(COMMIT_MARKER))) {
            Integer newCount = committedShardCountIfValid(staging);
            boolean moved = Files.isRegularFile(movedMarker);
            // MOVED distinguishes the two ways the staged set can be incomplete: before it
            // exists everything in dir is the old set and the staging itself must hold the
            // whole new set; after it exists some staged files may already sit in dir, so
            // the new layout is judged across dir and staging together. Either way the swap
            // is only finished once every new shard is present in dir.
            boolean swappable = newCount != null && (moved
                    ? completeNewLayout(dir, staging, backup, newCount)
                    : validStagedLayout(dir, staging, newCount));
            if (swappable) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: resuming an interrupted reshard of {0}", dir);
                // The rewritten shards assign new offsets to every record, so any region
                // pages (and their hint/watermark anchors) from the pre-reshard layout are
                // stale. Invalidate the page index BEFORE finishSwap deletes the staging and
                // backup trees: whatever point a crash interrupts the swap, the next open
                // never pairs old-layout pages with the new-layout logs. (The previous
                // order - invalidate after the evidence was deleted - left a window where a
                // crash between the two reopened the store against stale pages with no
                // recovery evidence left.)
                invalidatePageIndex(dir);
                // Make the metadata durable BEFORE finishSwap destroys the COMMIT evidence:
                // a crash after this point leaves files and metadata agreeing even if the
                // swap itself still has to be re-run.
                applyShardCountMetadata(dir, newCount);
                finishSwap(dir, staging, backup);
                return;
            }
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: ignoring malformed or mismatched reshard COMMIT marker in {0}", dir);
            if (moved) {
                // MOVED proves the old set was displaced into backup before the swap
                // started. With the new set incomplete (completeNewLayout failed), the
                // partial new files in dir cannot be trusted: the records of the shards
                // the swap never reached live only in staging or backup. Restore the
                // complete old layout from backup (removing the partial new files) and
                // drop the staging tree, so the store reopens as the consistent
                // pre-reshard layout instead of silently losing records.
                try {
                    restoreOldLayout(dir, backup, staging);
                } catch (IOException restoreFailure) {
                    throw new FolesiumException(
                            "Cannot restore the old layout of " + dir + " after an incomplete reshard",
                            restoreFailure);
                }
                // The reshard switched store.shardCount to the new value before the swap
                // started (phase 2 of reshard()), so restoring the old files leaves the
                // metadata naming a layout that no longer exists: the store would fail to
                // open with a shard topology mismatch (or route keys under the wrong
                // mask). Rewrite the metadata to the old layout's count, inferred from
                // the restored shard files - the same realignment the other recover
                // branches perform via reconcileStaleMetadata.
                int restoredCount = restoredLayoutShardCount(dir);
                if (restoredCount > 0) {
                    Integer metaCount = metadataShardCount(dir);
                    if (metaCount == null || metaCount != restoredCount) {
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Folesium: reshard of {0} was rolled back to the {1}-shard layout "
                                        + "but store.shardCount still said {2}; repairing the metadata "
                                        + "to match the restored files",
                                dir, restoredCount,
                                metaCount == null ? "nothing" : Integer.toString(metaCount));
                        applyShardCountMetadata(dir, restoredCount);
                    }
                } else {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Folesium: cannot determine the shard count of the restored layout of {0}; "
                                    + "store.shardCount was left unchanged",
                            dir);
                }
                return;
            }
        }
        if (!hasStaging && Files.isRegularFile(movedMarker)) {
            // MOVED alone (staging gone): staging is only deleted by finishSwap after every
            // staged shard was moved into dir and the directory fsynced, so the swap is
            // already finished and the on-disk files are the complete new set. Header-only
            // (legitimately empty) shard files are the finished swap's own output - a growth
            // reshard leaves the empty shards of the new layout header-only - so they count
            // as present here, and presence of all the shards the metadata names is enough
            // to delete the backup. (Eager header-only recreation on open cannot mask an
            // unfinished swap: recovery resolves the layout before any keyspace opens, so a
            // state with staging gone is always a finished swap, never a recreated empty.)
            // The layout the store now holds is whatever the interrupted swap left behind,
            // so any region pages are of uncertain provenance: drop them (rebuildable cache)
            // BEFORE the backup is deleted - a crash after the deletion but before the
            // invalidation would reopen the store against old-layout pages with the
            // new-layout logs and no recovery evidence left.
            invalidatePageIndex(dir);
            Integer metaCount = metadataShardCount(dir);
            int fileCount = consistentOnDiskShardCount(dir);
            if (movedAloneBackupDeletable(dir, backup, metaCount, fileCount)) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Folesium: removing the backup of a completed reshard of {0}", dir);
                deleteRecursively(backup);
                // This branch performs no other fsync fallback (reconcileStaleMetadata
                // below only rewrites the metadata when it is stale), so persist the
                // backup-tree deletion explicitly: the unlink of the backup directory
                // itself lives in the store directory's entry, and without an fsync a
                // power cut could resurrect the MOVED marker and the swapped-out old
                // shards, which recovery would then mistake for a layout in flight.
                fsyncDirectory(dir);
            } else if (metaCount != null && fileCount == metaCount) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: keeping the backup {0} of {1}: the shard files span the {2} "
                                + "indices the metadata names, but at least one file header records "
                                + "a different shard count - the set is mixed with leftovers of "
                                + "another layout, not the finished new one",
                        backup, dir, metaCount);
            } else {
                // The on-disk set matches no layout (name-derived count disagrees with
                // the metadata): the swap was partial. reconcileStaleMetadata would
                // rewrite the shard count from the partial file names - a false "repair"
                // that then serves only the swapped-in fraction of records (or fails
                // the header topology check on the next writable open). Refuse to open
                // and let the operator converge the layout. NOTE: while the MOVED
                // marker is present, neither "restore the old files" nor "delete the
                // partial set" converges (both re-trigger this refusal); the decisive
                // step is removing the marker first.
                LOGGER.log(System.Logger.Level.ERROR,
                        "Folesium: refusing to open {0}: the shard files hold {1} shards but"
                                + " the metadata says {2}, and the backup {3} was kept - remove the"
                                + " MOVED marker {4} first, then either restore the backup by hand"
                                + " (or align store.shardCount), or deliberately delete the backup"
                                + " together with the partial set",
                        dir, fileCount < 0 ? "no consistent layout" : Integer.toString(fileCount),
                        metaCount == null ? "nothing" : Integer.toString(metaCount), backup,
                        movedMarker);
                throw new FolesiumException("Store " + dir + " holds a partial resharded layout"
                        + " (backup kept at " + backup + "); refusing to open - remove the MOVED"
                        + " marker first, then restore the backup by hand, align store.shardCount,"
                        + " or deliberately delete the backup together with the partial set");
            }
            // The swap evidence is gone, so bring the metadata in line with the files
            // before the store is opened.
            reconcileStaleMetadata(dir);
            return;
        }
        // No valid COMMIT marker: the new set was never authoritative. Restore any old files
        // moved aside before destroying scratch state, rather than destroying live shards.
        boolean moved = Files.isRegularFile(movedMarker);
        // The refusal path below (COMMIT lost + MOVED + incomplete new set) neither
        // discards anything nor leaves the store unchanged, so this message only belongs
        // to the branches that actually discard.
        boolean willRefuse = hasBackup && moved && !movedAloneBackupDeletable(dir, backup,
                metadataShardCount(dir), consistentOnDiskShardCount(dir));
        if (!willRefuse) {
            if (moved) {
                // MOVED + complete new set: the discard KEEPS the new layout and removes
                // the backup - the store is not unchanged, so say what actually happened.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: removing the backup of an incomplete reshard of {0}"
                                + " (the complete new layout in place is kept)", dir);
            } else if (hasBackup) {
                // Old files are moved back from the backup tree; the final state equals
                // the pre-reshard layout, but the operation itself is a restore.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: discarding an incomplete reshard of {0} and restoring the"
                                + " previous layout from the backup", dir);
            } else {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: discarding an incomplete reshard of {0} (store is unchanged)", dir);
            }
        }
        if (hasBackup && !moved) {
            restoreFromBackup(dir, backup);
        }
        // The staging/backup trees are about to be destroyed: drop the rebuildable page
        // index (a disposable cache, rebuilt from the logs) BEFORE they are deleted, so a
        // crash between the two can never reopen the store against pages of a layout whose
        // recovery evidence is gone.
        invalidatePageIndex(dir);
        deleteRecursively(staging);
        if (!hasBackup) {
            // Nothing to preserve.
        } else if (!moved || movedAloneBackupDeletable(dir, backup,
                metadataShardCount(dir), consistentOnDiskShardCount(dir))) {
            // No MOVED marker (the old set was restored above, so dir holds the complete
            // old layout), or the MOVED marker plus a complete new set in dir - in both
            // cases the backup is a true duplicate and can go.
            deleteRecursively(backup);
        } else {
            // MOVED exists but dir does not hold the complete new set (verified the same
            // way the MOVED-alone branch does): the swap was partially applied, the
            // COMMIT marker is missing, and the backup is the only remaining copy of the
            // old records. Deleting it would destroy those records while dir holds only
            // a partial new set - and opening would serve just the swapped-in fraction
            // (reconcileStaleMetadata would even rewrite the shard count from the
            // partial file names). Refuse to open instead. (A normal crash sequence
            // cannot produce this state; it needs a lost/corrupted COMMIT marker, e.g.
            // bitrot or antivirus quarantine.)
            LOGGER.log(System.Logger.Level.ERROR,
                    "Folesium: refusing to open {0}: an interrupted swap left the new set"
                            + " incomplete and the COMMIT marker is missing - the backup {1} is"
                            + " the only remaining copy of the old shards; the staging tree has"
                            + " already been discarded, so restore the backup by hand (or align"
                            + " store.shardCount), or deliberately delete the backup together with"
                            + " the partial shard set that remains in the store directory", dir, backup);
            throw new FolesiumException("Store " + dir + " holds a partial resharded layout"
                    + " (COMMIT marker lost, backup kept at " + backup + "); refusing to open -"
                    + " restore the backup by hand or delete the partial shard set and re-run");
        }
        // Persist the scratch-tree deletions like the MOVED-alone branch above: the unlink
        // of the staging/backup directories lives in the store directory's entry, and
        // without an fsync a power cut could resurrect the discard evidence recovery
        // would then mistake for a layout in flight.
        fsyncDirectory(dir);
        // The COMMIT evidence is gone, but the shard-count metadata may still have been
        // updated before the crash (or the restored files may disagree with it). Realign the
        // metadata with the files before any keyspace opens, so a valid shard is never
        // opened under a mismatched shard count.
        reconcileStaleMetadata(dir);
    }

    /**
     * Moves every shard file the backup still holds and that {@code dir} is missing back into
     * place. Only reachable when no {@code MOVED} marker exists, i.e. no staged file was ever
     * swapped in, so anything already in {@code dir} is part of the same old set and wins.
     */
    private static void restoreFromBackup(Path dir, Path backup) {
        try {
            for (Path old : listShardFiles(backup)) {
                Path target = dir.resolve(old.getFileName().toString());
                if (!Files.exists(target)) {
                    moveReplacing(old, target);
                }
            }
            fsyncDirectory(dir);
        } catch (IOException e) {
            throw new FolesiumException("Cannot restore the original shard files of " + dir, e);
        }
    }

    // ---------------------------------------------------------------- reshard

    /**
     * Rewrites every keyspace found in {@code dir} to use {@code newShardCount} shards and
     * updates {@code store.shardCount} in the metadata.
     *
     * @param oldConfig the configuration matching the <em>current</em> on-disk layout
     *                  (its {@code shardCount()} must be the on-disk value)
     */
    static void reshard(Path dir, Path metadataFile, FolesiumConfig oldConfig, int newShardCount) {
        int oldShardCount = oldConfig.shardCount();
        if (oldShardCount == newShardCount) {
            return;
        }
        List<String> keyspaces = discoverKeyspaces(dir);
        long startedAt = System.nanoTime();
        LOGGER.log(System.Logger.Level.INFO,
                "Folesium: resharding {0} from {1} to {2} shards ({3} keyspace(s)); "
                        + "this rewrites every record and may take a while",
                dir, oldShardCount, newShardCount, keyspaces.size());

        Path staging = dir.resolve(STAGING_DIR);
        Path backup = dir.resolve(BACKUP_DIR);
        // Reuse recover()'s MOVED-alone determination before the cleanup below destroys
        // the scratch trees: recover() only leaves a MOVED-marked backup behind when it
        // could NOT prove the store holds the complete new layout (its MOVED-alone
        // branch deletes the backup otherwise). In that retained state the backup may be
        // the only surviving copy of the old records - deleting it here would destroy
        // the only copy - and even when the directory happens to hold a full file set,
        // the kept state means the headers disagree with the layout (a mixed set), so a
        // new reshard over it could propagate the mismatch. Refuse the reshard instead,
        // so the operator resolves the leftover layout first.
        if (Files.isDirectory(backup) && Files.isRegularFile(backup.resolve(MOVED_MARKER))) {
            Integer metaCount = metadataShardCount(dir);
            if (!movedAloneBackupDeletable(dir, backup, metaCount, consistentOnDiskShardCount(dir))) {
                throw new FolesiumException("Cannot reshard " + dir + ": the backup directory " + backup
                        + " was kept by recovery because the store's layout could not be verified as"
                        + " complete (an interrupted reshard never finished its swap, or the shard"
                        + " headers disagree with the layout). Resolve the leftover layout before"
                        + " starting a new reshard.");
            }
        }
        // The rewritten shards assign new offsets to every record, so any region pages left
        // from the pre-reshard layout are stale: drop the whole page index (a disposable
        // cache, rebuilt from the logs) BEFORE the leftover scratch trees are removed, so a
        // crash in this cleanup can never reopen the store with old-layout pages and no
        // recovery evidence left.
        invalidatePageIndex(dir);
        deleteRecursively(staging);
        deleteRecursively(backup);
        // Persist the scratch-tree deletions before staging begins: without an fsync a
        // power cut could resurrect a stale COMMIT/MOVED marker that recovery would
        // mistake for a reshard in flight. Same reasoning as the MOVED-alone branch.
        fsyncDirectory(dir);

        long records = 0;
        try {
            Files.createDirectories(staging);

            // Phase 1 - stage. Nothing outside the staging directory is touched, so a crash
            // anywhere in here leaves the live store fully intact.
            //
            // The staged shards are written with EXPLICIT durability: a single fsync per
            // shard at the end is enough, and it must happen before the COMMIT marker.
            FolesiumConfig writeConfig = oldConfig
                    .withShardCount(newShardCount)
                    .withDurability(FolesiumConfig.DurabilityMode.EXPLICIT);
            for (String name : keyspaces) {
                records += copyKeyspace(dir, staging, name, oldConfig, writeConfig, newShardCount);
            }
            fsyncDirectory(staging);

            // Phase 2 - commit. After the marker is durable the new layout wins, and any
            // later crash resumes the swap rather than rolling back.
            FolesiumDatabase.writeAtomically(staging.resolve(COMMIT_MARKER), Integer.toString(newShardCount));
            fsyncDirectory(staging);
            updateShardCountMetadata(metadataFile, oldShardCount, newShardCount);

            // Phase 3 - swap.
            finishSwap(dir, staging, backup);
        } catch (IOException e) {
            throw new FolesiumException("Reshard of " + dir + " failed", e);
        }

        LOGGER.log(System.Logger.Level.INFO,
                "Folesium: reshard of {0} complete - {1} record(s) rewritten into {2} shards in {3} ms",
                dir, records, newShardCount, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * Streams every live record of one keyspace into a freshly created shard set under
     * {@code staging}, re-routing each key with the new shard mask.
     *
     * @return number of records copied
     */
    private static long copyKeyspace(Path dir, Path staging, String name,
                                     FolesiumConfig oldConfig, FolesiumConfig writeConfig,
                                     int newShardCount) {
        long[] copied = {0};
        int mask = newShardCount - 1;
        ShardFile[] out = new ShardFile[newShardCount];
        try (Keyspace source = new Keyspace(dir, name, oldConfig, true)) {
            // The staged shards carry the source keyspace's dictionary so codec-3 (ZSTD_DICT)
            // records stay decodable after the swap: every copied value is re-encoded under the
            // same dictionary the source used, instead of silently degrading to plain
            // compression. Passing it is safe even when dictionary compression is disabled -
            // ShardFile only uses the dictionary for new writes when the config flag is on, and
            // the dictionary is always needed to decode existing codec-3 records.
            byte[] dict = source.keyspaceDict();
            for (int i = 0; i < newShardCount; i++) {
                out[i] = new ShardFile(staging.resolve(String.format("%s-%04d.flog", name, i)), i,
                        writeConfig, null, null, false, dict, false);
            }
            source.forEach((key, value) -> {
                out[(int) (Bytes.mix64(key) & mask)].put(new Bytes(key), value);
                copied[0]++;
            });
            for (ShardFile s : out) {
                s.flushIfDirty();
            }
        } finally {
            // Always release the file handles, even if the copy blew up half way: the
            // caller deletes the staging directory and a stale handle would keep the
            // files alive on Windows.
            closeQuietly(out);
        }
        return copied[0];
    }

    /**
     * Phase 3, written so that it can be re-entered after a crash at any point.
     *
     * <p>The {@code MOVED} marker is the pivot: before it exists, everything matching a shard
     * file name in {@code dir} belongs to the <em>old</em> set and must be moved aside; after
     * it exists, such files are freshly swapped-in <em>new</em> ones and must be left alone.</p>
     */
    private static void finishSwap(Path dir, Path staging, Path backup) {
        try {
            Path movedMarker = backup.resolve(MOVED_MARKER);
            if (!Files.isRegularFile(movedMarker)) {
                Files.createDirectories(backup);
                for (Path old : listShardFiles(dir)) {
                    moveReplacing(old, backup.resolve(old.getFileName().toString()));
                }
                FolesiumDatabase.writeAtomically(movedMarker, "ok");
                fsyncDirectory(backup);
                fsyncDirectory(dir);
            }
            if (Files.isDirectory(staging)) {
                try (Stream<Path> files = Files.list(staging)) {
                    List<Path> staged = files.filter(Files::isRegularFile)
                            .filter(p -> !COMMIT_MARKER.equals(p.getFileName().toString()))
                            // Never move a crashed hint-write's staging residue into the store
                            // root: ShardFile.writeHint stages to '<shard>.flog.fidx.tmp' before
                            // its atomic rename, so a .fidx.tmp left in staging is inert crash
                            // debris (nothing reads it, and in the store root it would only
                            // accumulate); it is deleted with the staging tree below.
                            .filter(p -> !p.getFileName().toString().endsWith(".fidx.tmp"))
                            // Same for writeAtomically's marker temp ('.COMMIT.<rand>.tmp'):
                            // it is only deleted on a live call's finally, so a crash between
                            // temp creation and rename leaves residue that must not be moved
                            // into the store root either. Matched tightly (prefix AND .tmp
                            // suffix): a keyspace legitimately named '.COMMIT.foo' would
                            // otherwise have its staged shards silently skipped.
                            .filter(p -> !(p.getFileName().toString().startsWith("." + COMMIT_MARKER + ".")
                                    && p.getFileName().toString().endsWith(".tmp")))
                            .toList();
                    for (Path p : staged) {
                        moveReplacing(p, dir.resolve(p.getFileName().toString()));
                    }
                }
                fsyncDirectory(dir);
            }
            deleteRecursively(staging);
            deleteRecursively(backup);
            fsyncDirectory(dir);
        } catch (IOException e) {
            throw new FolesiumException("Failed to finish the reshard swap in " + dir, e);
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Distinct keyspace names that have at least one shard file in {@code dir}. */
    static List<String> discoverKeyspaces(Path dir) {
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                Matcher m = SHARD_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    names.add(m.group(1));
                }
            });
        } catch (IOException e) {
            throw new FolesiumException("Cannot list store directory " + dir, e);
        }
        return new ArrayList<>(names);
    }

    /** Shard logs and their index hints, directly inside {@code dir} (scratch dirs excluded). */
    private static List<Path> listShardFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".flog") || n.endsWith(".flog.fidx");
                    })
                    .toList();
        }
    }

    private static Integer committedShardCountIfValid(Path staging) {
        Path marker = staging.resolve(COMMIT_MARKER);
        try {
            if (Files.size(marker) > 64) {
                return null;
            }
            String raw = Files.readString(marker, StandardCharsets.UTF_8).trim();
            int count = Integer.parseInt(raw);
            return Integer.bitCount(count) == 1 && count >= 1 && count <= 1024 ? count : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * Ensures the staged files represent exactly the count named by COMMIT, for every keyspace
     * the store has. Consulted on the pre-MOVED recovery path, where staging is the only copy
     * of the new layout and {@code dir} still holds the old set: a staged set judged complete
     * here leads to {@link #finishSwap} moving the old files aside and deleting them, so a
     * staged set that silently dropped a whole keyspace must be judged invalid - otherwise
     * that keyspace's records would have no surviving copy.
     */
    private static boolean validStagedLayout(Path dir, Path staging, int count) {
        try {
            // An empty staging directory is never a valid staged layout: the checks below
            // all pass vacuously when it holds no shard files, and recover() would then
            // treat the staged set as complete - finishSwap would move the old set aside
            // and delete it, destroying the only surviving copy of the records. At least
            // one staged .flog must be present for the layout to be real.
            try (Stream<Path> files = Files.list(staging)) {
                if (files.noneMatch(p -> p.getFileName().toString().endsWith(".flog"))) {
                    return false;
                }
            }
            // The staged set must cover every keyspace the store has - the coverage check
            // mirroring completeNewLayout's union of discoverKeyspaces(dir) and
            // discoverKeyspaces(staging). The per-keyspace completeness checks below only
            // look at keyspaces that ARE staged, so they cannot catch one that is missing
            // entirely: a staged set that dropped a whole keyspace must be judged invalid
            // so recover() rolls the swap back / discards it instead of finishSwap deleting
            // the only surviving copy of that keyspace's records.
            TreeSet<String> stagedNames = new TreeSet<>(discoverKeyspaces(staging));
            if (!stagedNames.containsAll(discoverKeyspaces(dir))) {
                return false;
            }
            for (String name : stagedNames) {
                for (int i = 0; i < count; i++) {
                    Path shard = staging.resolve(String.format("%s-%04d.flog", name, i));
                    // Per-index completeness mirrors completeNewLayout's header rule: every
                    // staged shard must exist AND carry the committed count in its header - a
                    // file stamped with any other shard count is a leftover of a different
                    // layout, so the staged set is mixed and finishSwap must not move it into
                    // the store (the swap would strand or delete records).
                    if (!Files.isRegularFile(shard) || recordedShardCount(shard) != count) {
                        return false;
                    }
                }
                try (Stream<Path> files = Files.list(staging)) {
                    long actual = files.filter(Files::isRegularFile)
                            .filter(p -> isShardFileOf(p, name))
                            .count();
                    if (actual != count) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * True when every shard of the new layout is present in {@code dir} or still in
     * {@code staging}, i.e. the staged-to-dir move was interrupted rather than lost and
     * {@link #finishSwap} can drive it to completion. Only consulted once the {@code MOVED}
     * marker exists, so the shard files in {@code dir} are freshly swapped-in new files
     * rather than the old set.
     *
     * <p>Two completion rules apply. While staging still holds shard files of a keyspace
     * the swap is provably unfinished there, and a shard only counts as swapped when
     * {@code dir} actually holds its records (see {@link #isPopulatedShard}): a header-only
     * file in {@code dir} may be an eagerly recreated empty shard after a crash mid-swap,
     * which must not make a partial new layout look complete (that would let the backup -
     * the only surviving copy of the records the missing shards should have held - be
     * deleted). Once staging holds no shard file of a keyspace, every staged file has
     * already been moved into {@code dir} - {@link #finishSwap} only empties staging after
     * the last move and the directory fsync - so the swap is finished for that keyspace
     * and mere presence of all {@code count} shards in {@code dir}, even header-only (the
     * legitimate output of a finished growth reshard), proves it. Judging that state
     * complete is what stops a crash in the cleanup window (between emptying and deleting
     * staging) from rolling a finished swap back.
     *
     * <p>A third rule applies to both branches: every file that counts toward completeness
     * must carry the committed {@code count} in its header (see {@link #recordedShardCount}) -
     * a file stamped with any other shard count is a leftover of a different layout, so the
     * set is mixed (e.g. a crash after the {@code MOVED} marker but before every old file was
     * moved aside leaves old-layout shards beside new-layout ones). Finishing the swap over
     * a mixed set would strand or delete records, so a header mismatch judges the layout
     * incomplete and {@link #recover} rolls it back via {@link #restoreOldLayout}, which
     * converges on the consistent old layout.</p>
     */
    private static boolean completeNewLayout(Path dir, Path staging, Path backup, int count) {
        try {
            TreeSet<String> names = new TreeSet<>();
            names.addAll(discoverKeyspaces(dir));
            names.addAll(discoverKeyspaces(staging));
            // A keyspace whose shard files survive only in the backup tree (its staged
            // files were lost - staging partially destroyed or externally removed) must
            // not pass the per-keyspace checks vacuously: finishSwap would then delete
            // the backup - the sole surviving copy of that keyspace's records. Same
            // guard backupKeyspacesCovered applies to the MOVED-alone branch.
            if (backup != null && !names.containsAll(discoverKeyspaces(backup))) {
                return false;
            }
            for (String name : names) {
                if (countStagedShardFiles(staging, name) == 0) {
                    // Staging holds no shard file of this keyspace: all of them were moved
                    // into dir before staging was emptied, so the swap reached every shard.
                    // Header-only files count here - they are the moved-in output of the
                    // finished swap, not eagerly recreated empties (see the method javadoc).
                    for (int i = 0; i < count; i++) {
                        Path shard = dir.resolve(String.format("%s-%04d.flog", name, i));
                        // Every file that counts toward completeness must name the new
                        // layout in its header: a file stamped with any other shard count
                        // is a leftover of a different layout, so the set is mixed and the
                        // swap must not be finished over it (recover() rolls the layout
                        // back via restoreOldLayout instead).
                        if (!Files.isRegularFile(shard) || recordedShardCount(shard) != count) {
                            return false;
                        }
                    }
                    continue;
                }
                for (int i = 0; i < count; i++) {
                    Path dirShard = dir.resolve(String.format("%s-%04d.flog", name, i));
                    Path stagingShard = staging.resolve(String.format("%s-%04d.flog", name, i));
                    // A staging file - even header-only - is copyKeyspace's output for that
                    // shard: a shard of the new layout legitimately holds no records when the
                    // keyspace has fewer records than shards (e.g. a growth reshard), so its
                    // mere presence proves the swap reached it. A dir file only counts when
                    // populated: a header-only file there may be an eagerly recreated empty
                    // shard after a crash mid-swap, which must not make a partial new layout
                    // look complete (that would let the backup - the only surviving copy of
                    // the records the missing shards should have held - be deleted).
                    if (isPopulatedShard(dirShard)) {
                        // The dir file counts: it must name the new layout in its header,
                        // or the set is mixed with leftovers of another layout and the swap
                        // is not finishable over it.
                        if (recordedShardCount(dirShard) != count) {
                            return false;
                        }
                    } else if (Files.isRegularFile(stagingShard)) {
                        // The staging file counts: it is copyKeyspace's output, so its
                        // header must name the committed count - a mismatch means the
                        // committed count does not describe these files (finishing the swap
                        // would strand or delete records), so the layout is incomplete.
                        if (recordedShardCount(stagingShard) != count) {
                            return false;
                        }
                    } else {
                        return false;
                    }
                }
                if (countShardFiles(dir, name) + countStagedShardFiles(staging, name) != count) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * True when a MOVED-alone backup may be deleted: the metadata names a shard count, the
     * on-disk shard files span exactly that many shards, and every shard the layout names
     * carries the count in its header (see {@link #completeNewLayoutInDir}). This is exactly
     * the condition under which recover()'s MOVED-alone branch deletes the backup; in every
     * other state the backup is the only surviving copy of the old records and must be kept.
     * Shared by recover() and reshard() so the two never disagree about when the backup is
     * deletable.
     */
    private static boolean movedAloneBackupDeletable(Path dir, Path backup, Integer metaCount, int fileCount) {
        return metaCount != null && fileCount == metaCount
                && backupKeyspacesCovered(dir, backup)
                && completeNewLayoutInDir(dir, metaCount);
    }

    /**
     * Every keyspace present in the backup tree must also be present in {@code dir}:
     * {@link #completeNewLayoutInDir} only iterates the keyspaces discoverable in dir, so a
     * keyspace whose shard files are entirely absent from dir (still in staging when the
     * staging tree was destroyed, or externally removed) would pass the completeness check
     * vacuously - and the backup, the only surviving copy of that keyspace's records,
     * would be deleted. Unreadable trees count as not covered (never delete the backup on
     * uncertain evidence).
     */
    private static boolean backupKeyspacesCovered(Path dir, Path backup) {
        try {
            return discoverKeyspaces(dir).containsAll(discoverKeyspaces(backup));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True when {@code dir} holds the complete layout named by {@code count}, judged like
     * the staging-empty branch of {@link #completeNewLayout} (Branch A): every shard index
     * {@code 0..count-1} of every keyspace must exist as a regular file whose header
     * records {@code count}. A file stamped with any other shard count is a leftover of a
     * different layout, so the set is mixed and the layout is not the finished new one.
     * Header-only files count - they are the legitimate output of a finished growth
     * reshard, already moved in from staging. Consulted by the MOVED-alone branch of
     * {@link #recover}, where staging is gone and this is the only way to confirm the
     * files really are the new set before the backup - the sole surviving copy of the
     * old records - is deleted.
     */
    private static boolean completeNewLayoutInDir(Path dir, int count) {
        try {
            for (String name : discoverKeyspaces(dir)) {
                for (int i = 0; i < count; i++) {
                    Path shard = dir.resolve(String.format("%s-%04d.flog", name, i));
                    // Every file the layout names must carry the layout's count in its
                    // header: a file stamped with any other shard count (or whose header
                    // shardIndex disagrees with its name) is a leftover of a different
                    // layout, so the set is mixed and the swap must not be considered
                    // finished over it.
                    if (!Files.isRegularFile(shard) || recordedShardCount(shard) != count) {
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True for a shard file that demonstrably holds records: strictly larger than the
     * file header. {@link Keyspace} eagerly creates header-only shard files on open, so
     * their mere presence does not prove a reshard swap was completed - after a crash
     * mid-swap a recreated empty shard could make a partial new layout look complete and
     * get the backup (the only surviving copy of the records it should have held) deleted.
     */
    private static boolean isPopulatedShard(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        try {
            return Files.size(p) > ShardFile.FILE_HEADER_LEN;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Restores the complete pre-reshard layout of {@code dir} from the backup tree after
     * an aborted swap: every old shard file is moved back, the partial new files are
     * removed, and both the staging and backup trees are dropped. Only called on the MOVED
     * path, where the backup is the sole surviving copy of the records the swap never
     * reached - deleting the staging tree without restoring it would silently lose data.
     *
     * <p>The old files are moved back first ({@code REPLACE_EXISTING}, so an old file
     * always wins over a same-named partial new file); only then are the new-layout
     * leftovers removed. Leftovers are identified in the <em>old layout's namespace</em>
     * rather than by a captured name set: the old layout's shard count is derived from
     * the file headers (any backup shard file - the backup only ever holds old-layout
     * files - else, once the backup is empty, the surviving shard files in {@code dir},
     * which are restored old files sharing the same count), and every shard file in
     * {@code dir} whose index is beyond that count (>= oldCount) cannot belong to the
     * old layout, so it is an aborted new-layout leftover and is removed. The name set this replaces shrank across re-entries - a previous
     * invocation had moved some old files back, so their names were no longer in the
     * backup to be captured - which made a re-entry misidentify already-restored old
     * files as leftovers and delete the only surviving copies of their records. The
     * header-derived count is stable across re-entries. Index hints ({@code .fidx}) are
     * handled by wholesale replacement rather than a per-hint criterion: every hint in
     * {@code dir} is deleted BEFORE the backup is moved back (hints are a rebuildable
     * cache, so deleting them is always safe), and the backup's old-layout hints - which
     * always match the restored logs - land on the emptied slots. This closes the orphan
     * window a selective criterion leaves open: a new-layout hint at an index below the
     * old count would otherwise survive, paired with the restored old shard log it does
     * not describe, and misdirect reads until it is regenerated.</p>
     *
     * <p>Idempotent across crashes: a previous invocation may already have moved every
     * old shard back into {@code dir} and then crashed before the cleanup. The backup
     * then holds no shard file (only the MOVED marker) while {@code dir}'s shard files
     * are the complete old set - the only surviving copy of the records - plus any
     * new-layout leftovers the crashed cleanup never removed; those are still removed
     * here (their index is beyond the count the surviving old files' headers record).
     * When no readable header survives anywhere the old count cannot be established and
     * the leftover cleanup is skipped (conservative: never delete a file that might be
     * the only surviving copy of a record); the backup files are still moved back, since
     * an old file always wins over a same-named partial new file. A crash in the middle
     * of the restore itself is also safe to re-enter: the files already moved back stay
     * in {@code dir} (their indices are below the old count), and the remaining backup
     * files are moved back on the retry, overwriting any same-named leftovers.</p>
     */
    private static void restoreOldLayout(Path dir, Path backup, Path staging) throws IOException {
        List<Path> backupShards = Files.isDirectory(backup) ? listShardFiles(backup) : List.of();
        // The old layout's shard count, derived from file headers BEFORE the move-back
        // invalidates the backup paths. -1 when no readable header exists anywhere.
        int oldCount = oldLayoutShardCount(dir, backupShards);
        // 0. Drop EVERY index hint ({@code *.fidx}) in dir before anything is moved back.
        //    Hints are a rebuildable cache (regenerated from the logs), so deleting them
        //    is always safe - and it is what closes the orphan window a selective
        //    criterion cannot: a new-layout hint at an index BELOW the old count (one the
        //    aborted swap wrote beside a new shard whose index the old layout also uses)
        //    would otherwise survive the leftover cleanup below, paired with the restored
        //    old shard log it does not describe, and misdirect reads until it is
        //    regenerated. With every hint gone up front, the only hints that can remain
        //    are the old-layout ones moved back from the backup below, which always match
        //    the restored logs.
        for (Path p : listShardFiles(dir)) {
            if (p.getFileName().toString().endsWith(".fidx")) {
                Files.deleteIfExists(p);
            }
        }
        // 1. Move every old shard file back from the backup FIRST, with REPLACE_EXISTING:
        //    an old file always wins over a same-named partial new file. On a re-entry
        //    (a previous invocation crashed mid-restore) this moves back whatever is
        //    still left there; the files already restored stay in place. The MOVED marker
        //    stays behind - it is not a shard file and must not be moved into the store
        //    root; the whole backup tree is dropped below, taking the marker with it.
        //    Old-layout hints come back with their shard files (the backup holds each pair
        //    as one set), landing on the slots step 0 just emptied.
        for (Path p : backupShards) {
            moveReplacing(p, dir.resolve(p.getFileName().toString()));
        }
        // 2. Remove the new-layout leftovers from dir: every shard file whose index is
        //    beyond the old layout's count. The old layout has no shard with such an
        //    index, so any such file belongs to the aborted new layout; files already
        //    restored (by this or a previous invocation) all sit below the count and are
        //    kept, so a mid-restore crash followed by a retry never loses the records
        //    already moved back. (No hint cleanup is needed here: step 0 already removed
        //    every hint, and the backup - which only ever holds old-layout files - has no
        //    hint at an index beyond the old count.) When the old count cannot be
        //    established (no readable header anywhere), be conservative and delete
        //    nothing.
        if (oldCount > 0) {
            for (Path p : listShardFiles(dir)) {
                String name = p.getFileName().toString();
                Matcher m = SHARD_FILE.matcher(name);
                if (m.matches() && Integer.parseInt(m.group(2)) >= oldCount) {
                    Files.deleteIfExists(p);
                }
            }
        } else {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: cannot determine the pre-reshard shard count of {0} from the "
                            + "surviving file headers; keeping every shard file in the store "
                            + "directory (conservative)",
                    dir);
        }
        // 3. Drop the staging and backup trees.
        deleteRecursively(staging);
        deleteRecursively(backup);
        fsyncDirectory(dir);
    }

    /**
     * The pre-reshard shard count of an interrupted reshard, derived from shard file
     * headers: the header count of any backup shard file (the backup only ever holds
     * old-layout files, so the first readable header is the old layout's count), falling
     * back - when the backup holds no shard file at all (a previous invocation already
     * moved the complete old set back) - to the first readable header of the surviving
     * shard files in {@code dir}, which are restored old files sharing the layout's
     * count. Index hints ({@code .fidx}) are ignored: they carry no shard header and are
     * rebuildable, so a backup holding only hints is treated as empty. -1 when the old
     * count cannot be established: the backup holds shard files but none has a readable
     * header, or the dir holds no readable shard header.
     */
    private static int oldLayoutShardCount(Path dir, List<Path> backupShards) {
        // Index hints (".fidx") carry no shard header and are rebuildable, so they are
        // ignored when deriving the count: a backup reduced to only hints (every old
        // shard already moved back by a previous invocation) must fall through to the
        // dir fallback below instead of looking like a non-empty backup whose header
        // cannot be read.
        List<Path> shards = backupShards.stream()
                .filter(p -> !p.getFileName().toString().endsWith(".fidx"))
                .toList();
        for (Path p : shards) {
            int count = recordedShardCount(p);
            if (count > 0) {
                return count;
            }
        }
        if (!shards.isEmpty()) {
            // A non-empty backup with no readable header: the old count is genuinely
            // unknown, and the dir may still hold partial new files whose headers name
            // the new layout - reading those would corrupt the leftover boundary. Be
            // conservative instead of guessing.
            return -1;
        }
        // The backup is empty: every old shard was already moved back, so dir holds the
        // complete old set (its shard files all share the old layout's count) possibly
        // alongside new-layout leftovers at higher indices.
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.sorted().toList()) {
                if (SHARD_FILE.matcher(p.getFileName().toString()).matches()) {
                    int count = recordedShardCount(p);
                    if (count > 0) {
                        return count;
                    }
                }
            }
        } catch (IOException e) {
            // Fall through to -1: listing failure must not cause deletions either.
        }
        return -1;
    }

    /**
     * The shard count of the layout {@link #restoreOldLayout} just restored: the header
     * shard count of the first shard file in {@code dir} (every restored file was written
     * with the same old topology), falling back to the index-derived
     * {@link #consistentOnDiskShardCount} when no shard header is readable. -1 when no
     * shard file survives, i.e. the old layout's count cannot be established.
     */
    private static int restoredLayoutShardCount(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.sorted().toList()) {
                if (SHARD_FILE.matcher(p.getFileName().toString()).matches()) {
                    int count = recordedShardCount(p);
                    if (count > 0) {
                        return count;
                    }
                }
            }
        } catch (IOException e) {
            // Fall through to the index-derived count.
        }
        return consistentOnDiskShardCount(dir);
    }

    /**
     * The shard count stamped in a shard file's header - the physical topology the file
     * was written with - or -1 when the file is absent, shorter than the fixed header, or
     * its header is invalid. Mirrors {@link ShardFile}'s header layout ({@code "FLSM" |
     * u16 version=1 | u16 reserved | u32 shardIndex | u32 shardCount}); the magic and
     * version are checked locally because {@link ShardFile}'s constants are
     * package-private to {@code dev.folesium.core.shard}. Like
     * {@code ShardFile#validateFileHeader}, the header's {@code shardIndex} must match the
     * index in the file name: a file whose header names a different shard than its name
     * claims is not the shard it pretends to be (a stale copy of another layout), so it is
     * treated as unreadable. Names that do not match the shard naming pattern carry no
     * expected index and cannot be checked.
     */
    private static int recordedShardCount(Path shardFile) {
        if (!Files.isRegularFile(shardFile)) {
            return -1;
        }
        ByteBuffer header = ByteBuffer.allocate(ShardFile.FILE_HEADER_LEN);
        try (var channel = java.nio.channels.FileChannel.open(shardFile,
                java.nio.file.StandardOpenOption.READ)) {
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    return -1; // shorter than the header
                }
            }
        } catch (IOException e) {
            return -1;
        }
        header.flip();
        byte[] magic = new byte[4];
        header.get(magic);
        if (!Arrays.equals(magic, new byte[]{'F', 'L', 'S', 'M'}) || header.getShort() != 1) {
            return -1;
        }
        header.getShort(); // reserved
        int shardIndex = header.getInt();
        int count = header.getInt();
        if (Integer.bitCount(count) != 1 || count < 1 || count > 1024) {
            return -1;
        }
        // A header whose shardIndex disagrees with the file name's index is not the shard
        // the name claims - a stale copy of another layout - so treat it as unreadable.
        Matcher m = SHARD_FILE.matcher(shardFile.getFileName().toString());
        if (m.matches() && shardIndex != Integer.parseInt(m.group(2))) {
            return -1;
        }
        return count;
    }

    /**
     * Whether {@code p} is a shard log of exactly {@code keyspace}. Matching on the
     * {@link #SHARD_FILE} name's captured group - never on a {@code keyspace + "-"} prefix -
     * keeps keyspaces like {@code a} and {@code a-b} from miscounting each other's files:
     * {@code 'a'} must not count {@code a-b-0000.flog}.
     */
    private static boolean isShardFileOf(Path p, String keyspace) {
        Matcher m = SHARD_FILE.matcher(p.getFileName().toString());
        return m.matches() && m.group(1).equals(keyspace);
    }

    /** Number of populated shard logs of exactly one keyspace, directly inside {@code dir}. */
    private static long countShardFiles(Path dir, String keyspace) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(StoreResharder::isPopulatedShard)
                    .filter(p -> isShardFileOf(p, keyspace))
                    .count();
        }
    }

    /**
     * Number of shard logs of exactly one keyspace inside the staging tree by mere presence:
     * copyKeyspace writes even header-only files for the empty shards of the new layout,
     * so they are legitimate evidence the swap reached them (see {@link #completeNewLayout}).
     */
    private static long countStagedShardFiles(Path dir, String keyspace) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> isShardFileOf(p, keyspace))
                    .count();
        }
    }

    /**
     * Idempotently records {@code store.shardCount = newCount} (see {@link #recover}),
     * preserving the existing {@code store.previousShardCount} when the metadata already
     * names the new count - a previous recovery pass made it durable before its swap was
     * interrupted, and rewriting it would clobber the record of the pre-reshard count
     * with the same value.
     */
    private static void applyShardCountMetadata(Path dir, int newCount) {
        Path meta = dir.resolve(FolesiumDatabase.METADATA_FILE);
        Integer oldCount = null;
        if (Files.isRegularFile(meta)) {
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new FolesiumException("Cannot read " + meta, e);
            }
            String raw = p.getProperty("store.shardCount");
            if (raw != null) {
                try {
                    oldCount = Integer.parseInt(raw.trim());
                } catch (RuntimeException ignore) {
                    // treat as missing; the rewrite below repairs the metadata either way.
                }
            }
        }
        if (oldCount != null && oldCount == newCount) {
            // store.shardCount already names the new layout; leave the metadata untouched
            // so store.previousShardCount keeps the true pre-reshard count.
            return;
        }
        try {
            updateShardCountMetadata(meta, oldCount == null ? newCount : oldCount, newCount);
        } catch (IOException e) {
            throw new FolesiumException("Cannot update shard count metadata in " + dir, e);
        }
    }

    /**
     * Repairs metadata that was left naming the old shard count after the swap evidence
     * was destroyed. Heals exactly one damage pattern: every keyspace on disk is uniformly
     * laid out with a valid power-of-two shard count that differs from
     * {@code store.shardCount} - i.e. the swap finished but the metadata update did not
     * survive. The metadata is brought in line with the files and the store proceeds to
     * open; a store whose files already match the metadata is left untouched.
     */
    private static void reconcileStaleMetadata(Path dir) {
        Path meta = dir.resolve(FolesiumDatabase.METADATA_FILE);
        if (!Files.isRegularFile(meta)) {
            return;
        }
        int fileCount = consistentOnDiskShardCount(dir);
        if (fileCount < 0) {
            return;
        }
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new FolesiumException("Cannot read " + meta, e);
        }
        String raw = p.getProperty("store.shardCount");
        if (raw == null) {
            return;
        }
        int metaCount;
        try {
            metaCount = Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return;
        }
        if (metaCount == fileCount) {
            return;
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Folesium: shard files of {0} hold {1} shards but the metadata says {2}; "
                        + "repairing the metadata to match the files",
                dir, fileCount, metaCount);
        try {
            updateShardCountMetadata(meta, metaCount, fileCount);
        } catch (IOException e) {
            throw new FolesiumException("Cannot repair shard count metadata in " + dir, e);
        }
    }

    /**
     * {@code store.shardCount} recorded in the metadata, or {@code null} when the metadata
     * is absent, unreadable, or does not carry the property.
     */
    private static Integer metadataShardCount(Path dir) {
        Path meta = dir.resolve(FolesiumDatabase.METADATA_FILE);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(reader);
            String raw = p.getProperty("store.shardCount");
            return raw == null ? null : Integer.parseInt(raw.trim());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The shard count every keyspace's on-disk files agree on, or -1 when the layout is
     * not uniform: keyspaces disagree with each other, a keyspace is missing shard
     * indices, or there are no shard files at all. Every existing shard file counts -
     * including header-only files eagerly recreated by {@link Keyspace} on open - because
     * this is the physical layout the metadata must describe.
     */
    private static int consistentOnDiskShardCount(Path dir) {
        return consistentShardCount(dir, Files::isRegularFile);
    }

    private static int consistentShardCount(Path dir, java.util.function.Predicate<Path> include) {
        try (Stream<Path> files = Files.list(dir)) {
            HashMap<String, TreeSet<Integer>> indices = new HashMap<>();
            files.filter(include).forEach(p -> {
                Matcher m = SHARD_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    indices.computeIfAbsent(m.group(1), k -> new TreeSet<>())
                            .add(Integer.parseInt(m.group(2)));
                }
            });
            if (indices.isEmpty()) {
                return -1;
            }
            int count = -1;
            for (TreeSet<Integer> set : indices.values()) {
                int size = set.size();
                if (set.first() != 0 || set.last() != size - 1) {
                    return -1;
                }
                if (count == -1) {
                    count = size;
                } else if (count != size) {
                    return -1;
                }
            }
            if (Integer.bitCount(count) != 1 || count < 1 || count > 1024) {
                return -1;
            }
            return count;
        } catch (IOException e) {
            return -1;
        }
    }

    private static void updateShardCountMetadata(Path meta, int oldCount, int newCount) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(meta)) {
            try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }
        p.setProperty("store.shardCount", Integer.toString(newCount));
        p.setProperty("store.previousShardCount", Integer.toString(oldCount));
        p.setProperty("store.reshardedAt", Long.toString(System.currentTimeMillis()));
        FolesiumDatabase.writeMetadataAtomically(meta, p);
    }

    /**
     * Moves {@code source} over {@code target}, preferring an atomic replace but falling back
     * to a plain replace move on filesystems that do not support atomic moves (reported either
     * as {@link AtomicMoveNotSupportedException} or as a generic filesystem error). Mirrors
     * {@code ShardFile.moveReplacing}: without the fallback, a reshard (or its recovery) would
     * fail permanently - and every later open would retry the same failing recovery - on such
     * filesystems.
     */
    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException e) {
            // Some platforms report missing atomic-replace support as a generic filesystem error.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeQuietly(ShardFile[] shards) {
        for (ShardFile s : shards) {
            if (s == null) {
                continue;
            }
            try {
                s.close();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Folesium: error closing a staged shard", e);
            }
        }
    }

    /**
     * Drops every rebuildable region-page artifact under {@code idx/}, preserving the
     * non-rebuildable per-keyspace trained dictionaries ({@code dict.bin}) that codec-3
     * (ZSTD_DICT) records decode against. Called whenever a reshard completes or is resumed:
     * the rewritten shards assign new offsets to every record, so pages from the old layout
     * would point at the wrong records. Page files ({@code *.idx}), the hint manifest,
     * watermarks ({@code *.wmk}/{@code *.cwmk}) and any other file are a disposable cache
     * (rebuildable from the logs), so removing them is always safe - the dictionary is not.
     * Keyspace subdirectories are pruned only when they end up empty (i.e. held no
     * {@code dict.bin}).
     */
    private static void invalidatePageIndex(Path dir) {
        Path idx = dir.resolve("idx");
        if (!Files.isDirectory(idx)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(idx)) {
            // Children sort after their parent, so the reversed walk reaches each directory
            // only after all of its entries were handled: files not named dict.bin are
            // deleted, and a directory is pruned iff it no longer contains anything (i.e.
            // it held no dict.bin). Directories still holding their dict.bin survive intact.
            List<Path> survivingDirs = new ArrayList<>();
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(p)) {
                    try (Stream<Path> children = Files.list(p)) {
                        if (children.findAny().isEmpty()) {
                            Files.deleteIfExists(p);
                        } else {
                            survivingDirs.add(p);
                        }
                    }
                } else if (!DICT_FILE.equals(p.getFileName().toString())) {
                    Files.deleteIfExists(p);
                }
            }
            // Best-effort directory fsync so the deletions survive a power cut: page files
            // are unlinked, not overwritten, so without it a crash could resurrect stale
            // pages pointing at the wrong records in the new layout. The idx root covers
            // the unlinks of pruned subdirectories (and of files directly under it); each
            // surviving subdirectory covers the deletions inside it. When the whole tree
            // was pruned (no dict.bin survived) the idx root itself is gone, so its unlink
            // must be covered by fsyncing the store directory instead - fsyncing idx is
            // impossible (it no longer exists) and would not cover its removal anyway.
            // fsyncDirectory swallows failures - Windows cannot open directories for
            // reading - matching its use everywhere else.
            if (Files.isDirectory(idx)) {
                fsyncDirectory(idx);
                for (Path d : survivingDirs) {
                    if (!idx.equals(d)) {
                        fsyncDirectory(d);
                    }
                }
            } else {
                fsyncDirectory(dir);
            }
        } catch (IOException e) {
            throw new FolesiumException("Cannot remove the page index of " + dir, e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new FolesiumException("Cannot remove " + root, e);
        }
    }

    /**
     * Best-effort directory fsync so that renames survive a power cut. Opening a directory
     * for reading is not portable (it fails on Windows), and a failure here only widens the
     * crash window rather than corrupting anything, so it is deliberately swallowed.
     */
    private static void fsyncDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (var ch = java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Not supported on this platform/filesystem.
        }
    }
}
