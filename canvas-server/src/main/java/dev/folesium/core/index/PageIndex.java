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

package dev.folesium.core.index;

import dev.folesium.core.FolesiumException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keyspace-level region-page index: an LRU page cache (segmented locks) plus dirty-page
 * tracking and persistence to one {@code <rx>.<rz>.idx} file per region.
 *
 * <p>Phase 1 semantics: pages and the shard HashMap are kept strongly consistent on the
 * write path (the shard updates both under its write lock). After
 * {@link #invalidateAll()} (compaction / reshard) pages are no longer consulted by
 * readers, which fall back to the HashMap (handled by the caller). Pages are a pure
 * bypath cache in Phase 1: nothing populates them from the HashMap at open, so the only
 * trustworthy pages are those read from an existing page file (see
 * {@link #isPagePersisted(int, int)}).</p>
 *
 * <p><b>Cache design.</b> The cache is partitioned into {@value #SEGMENT_COUNT}
 * segments, each guarded by its own {@link ReentrantLock}, so region threads on
 * different segments never contend on a global lock. Within a segment an access-order
 * {@link LinkedHashMap} provides strict LRU with O(1) eviction of the eldest entry
 * (rather than a ConcurrentHashMap with an approximate LRU). Eviction removes only
 * <em>clean</em> pages: a dirty page is pinned until the checkpoint thread flushes it,
 * so the eviction path never performs I/O and never loses unflushed updates. While
 * dirty pages are pinned the cache may transiently exceed its byte budget; the
 * checkpoint flush makes them evictable again.</p>
 *
 * <p><b>Thread model.</b> {@link #updateSlot} is called from the shard write path,
 * where the caller already holds the shard write lock. A region's chunks hash across
 * shards, so two shard writers holding <em>different</em> shard locks can still update
 * the same region page concurrently; the page's own monitor (the {@link Entry}) makes
 * slot mutation and the dirty-flag transition atomic regardless of which shard lock is
 * held, and also excludes a concurrent {@link #flush()} so a page is never written to
 * disk while being mutated. Nested locking always follows the order <em>segment lock,
 * then page monitor</em>: {@link #updateSlot} performs the cache lookup and the slot
 * update while holding the segment lock, so LRU eviction (which runs under the segment
 * lock via {@code removeEldestEntry}) can never drop the entry between lookup and
 * update; {@link #flush()} writes a page under its monitor alone, never taking a
 * segment lock while the monitor is held, so no code path acquires the two locks in
 * reverse order and the ordering cannot deadlock (the dirty counter is adjusted with an
 * atomic non-negative decrement instead of a segment-locked one). {@link #invalidateAll()}
 * publishes the volatile invalidated flag before clearing the segments;
 * {@link #updateSlot} re-checks it under the page monitor, so an in-flight update
 * either completes (its entry is then dropped) or aborts without touching the page.
 * {@link #pageFor} does not take the page monitor: a slot read racing a slot write may
 * observe an older offset, which is still a valid log position (the log is append-only)
 * and is CRC-validated by the reader.</p>
 *
 * <p>{@code cacheBytes = 0} disables the index entirely (pure v1 hash behaviour):
 * {@link #isEnabled()} is {@code false} and all operations become no-ops or return
 * fresh in-memory pages. In read-only mode pages are read or built in memory but never
 * persisted: no directory is created, {@link #flush()} is a no-op, and {@link #close()}
 * skips the hint file.</p>
 */
public final class PageIndex implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("Folesium");

    private static final int SEGMENT_COUNT = 16;
    private static final String PAGE_SUFFIX = ".idx";
    private static final String HINT_FILE = "hint";
    private static final String WATERMARK_SUFFIX = ".wmk";
    private static final String COMPACTION_WATERMARK_SUFFIX = ".cwmk";

    /** A cached page plus its bookkeeping state; also the page's monitor. */
    private static final class Entry {
        final RegionPage page;
        /** Set by updateSlot, cleared by flush; transitions happen under this Entry's monitor. */
        volatile boolean dirty;
        /** True once this page has a backing file on disk. */
        volatile boolean persisted;

        Entry(RegionPage page) {
            this.page = page;
        }
    }

    private static final class Segment {
        final ReentrantLock lock = new ReentrantLock();
        final LinkedHashMap<Long, Entry> map;
        final int maxPages;

        Segment(int maxPages) {
            this.maxPages = maxPages;
            this.map = new LinkedHashMap<>(Math.max(16, Math.min(maxPages, 4096)), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, PageIndex.Entry> eldest) {
                    // Evict only clean pages: dirty pages are pinned until the checkpoint
                    // flush, so eviction never does I/O and never drops unflushed updates.
                    return size() > maxPages && !eldest.getValue().dirty;
                }
            };
        }
    }

    private final Path idxDir;
    private final boolean readOnly;
    private final boolean enabled;
    private final Segment[] segments = new Segment[SEGMENT_COUNT];
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicInteger dirtyCount = new AtomicInteger();

    /** In-memory shard watermark cache: last known checkpoint-indexed log offset per shard. */
    private final ConcurrentHashMap<String, Long> shardWatermarks = new ConcurrentHashMap<>();
    /** Shard watermarks advanced in memory but not yet written to disk; drained by {@link #flushWatermarks()}. */
    private final ConcurrentHashMap<String, Long> pendingWatermarks = new ConcurrentHashMap<>();
    /** In-memory compaction-anchor (last completed compaction EOF) cache per shard. */
    private final ConcurrentHashMap<String, Long> compactionWatermarks = new ConcurrentHashMap<>();

    private volatile boolean invalidated;
    private volatile boolean closed;

    /**
     * @param idxDir     page-file directory ({@code <store>/idx/<keyspace>}); created
     *                   lazily on the first flush, never in read-only mode
     * @param cacheBytes page-cache byte budget, converted to pages via
     *                   {@link RegionPage#PAGE_SIZE} (at least one page); {@code 0}
     *                   disables the index entirely
     * @param readOnly   never write pages, never create the directory, never write the
     *                   hint file
     * @throws IOException declared for contract symmetry; the constructor itself does
     *                     no I/O
     */
    public PageIndex(Path idxDir, long cacheBytes, boolean readOnly) throws IOException {
        if (cacheBytes < 0) {
            throw new IllegalArgumentException("indexCacheBytes must be >= 0: " + cacheBytes);
        }
        this.idxDir = idxDir;
        this.readOnly = readOnly;
        this.enabled = cacheBytes > 0;
        long maxPages = Math.max(1, cacheBytes / RegionPage.PAGE_SIZE);
        long perSegment = Math.max(1, (maxPages + SEGMENT_COUNT - 1) / SEGMENT_COUNT);
        int perSegmentInt = (int) Math.min(Integer.MAX_VALUE, perSegment);
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segments[i] = new Segment(perSegmentInt);
        }
    }

    // ------------------------------------------------------------------ lookup

    private static long pack(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    private static int segmentIndex(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return (int) (h & (SEGMENT_COUNT - 1));
    }

    private Path pagePath(int regionX, int regionZ) {
        return idxDir.resolve(regionX + "." + regionZ + PAGE_SUFFIX);
    }

    /** Cache lookup or load-or-create under the owning segment's lock. */
    private Entry entryFor(int regionX, int regionZ) {
        long key = pack(regionX, regionZ);
        Segment seg = segments[segmentIndex(key)];
        seg.lock.lock();
        try {
            Entry e = seg.map.get(key);
            if (e != null) {
                cacheHits.incrementAndGet();
                return e;
            }
            cacheMisses.incrementAndGet();
            Path file = pagePath(regionX, regionZ);
            boolean exists = Files.exists(file);
            RegionPage page = exists ? readPage(file) : RegionPage.create(regionX, regionZ);
            e = new Entry(page);
            e.persisted = exists;
            seg.map.put(key, e);
            return e;
        } finally {
            seg.lock.unlock();
        }
    }

    private RegionPage readPage(Path file) {
        try {
            return RegionPage.read(file);
        } catch (IOException e) {
            throw new FolesiumException("Failed to read region page " + file, e);
        }
    }

    /**
     * Returns the page for a region: a cache hit, a page read from
     * {@code <idxDir>/<rx>.<rz>.idx}, or a fresh in-memory page when no page file
     * exists. In read-only mode a new page stays in memory and is never written.
     *
     * <p>A fresh page carries no information about records written before this instance
     * existed; callers that need slot {@code 0} to mean "truly absent" must check
     * {@link #isPagePersisted(int, int)} first. After {@link #invalidateAll()} this
     * returns a fresh page without touching disk: the index is dormant and callers must
     * fall back to the HashMap.</p>
     *
     * @throws FolesiumException when an existing page file is corrupt or unreadable
     */
    public RegionPage pageFor(int regionX, int regionZ) {
        if (closed) {
            throw new IllegalStateException("PageIndex is closed");
        }
        if (!enabled || invalidated) {
            return RegionPage.create(regionX, regionZ);
        }
        return entryFor(regionX, regionZ).page;
    }

    /**
     * Whether the page for a region is backed by a page file on disk (either loaded
     * from one or written by a previous {@link #flush()}). Only persisted pages can be
     * trusted for reads: a non-persisted (fresh) page predates the index and its zero
     * slots do not mean the records are absent.
     */
    public boolean isPagePersisted(int regionX, int regionZ) {
        if (!enabled) {
            return false;
        }
        long key = pack(regionX, regionZ);
        Segment seg = segments[segmentIndex(key)];
        seg.lock.lock();
        try {
            Entry e = seg.map.get(key);
            if (e != null) {
                return e.persisted;
            }
        } finally {
            seg.lock.unlock();
        }
        return Files.exists(pagePath(regionX, regionZ));
    }

    /**
     * Write-path hook: loads (or creates) the region page, sets the slot to the record's
     * absolute log offset ({@code 0} = absent / tombstoned) and marks the page dirty.
     *
     * <p>The caller holds the shard write lock (Phase 1 keeps pages and the HashMap
     * consistent under the same lock); in addition, slot mutation and the dirty flag are
     * synchronized on the page's monitor so that two shard writers of the same region
     * (which hold different shard locks) and a concurrent {@link #flush()} are serialized
     * on the page. The lookup and the mutation run while holding the owning segment's
     * lock (lock order: segment lock {@code ->} page monitor); LRU eviction runs under
     * the segment lock via {@code removeEldestEntry}, so the entry can never be evicted
     * between lookup and update and no dirty update can land on an orphaned entry. After
     * {@link #invalidateAll()} this is a no-op: the index is dormant and the HashMap is
     * authoritative.</p>
     */
    public void updateSlot(int regionX, int regionZ, int slot, int offset) {
        if (closed || !enabled || invalidated) {
            return;
        }
        long key = pack(regionX, regionZ);
        Segment seg = segments[segmentIndex(key)];
        seg.lock.lock();
        try {
            Entry e = seg.map.get(key);
            if (e == null) {
                cacheMisses.incrementAndGet();
                Path file = pagePath(regionX, regionZ);
                boolean exists = Files.exists(file);
                RegionPage page = exists ? readPage(file) : RegionPage.create(regionX, regionZ);
                e = new Entry(page);
                e.persisted = exists;
                seg.map.put(key, e);
            } else {
                cacheHits.incrementAndGet();
            }
            // The segment lock is still held here: eviction (removeEldestEntry) runs only
            // inside map.put under this same lock, so the entry cannot be evicted between
            // lookup and the slot update below.
            synchronized (e) {
                if (invalidated) {
                    return; // invalidated while we were loading: leave the page untouched
                }
                e.page.set(slot, offset);
                if (!e.dirty) {
                    e.dirty = true;
                    dirtyCount.incrementAndGet();
                }
            }
        } finally {
            seg.lock.unlock();
        }
    }

    /**
     * Drops the cached page for a region (if any) and removes its backing page file, so
     * the next {@link #pageFor}/{@link #updateSlot} creates a fresh in-memory page. Used
     * after a damaged page is detected: a corrupt file can never be loaded again, so it
     * must be deleted rather than overwritten - the caller re-scans the shard from the
     * compaction watermark and repopulates the page via {@link #updateSlot}.
     *
     * @return {@code true} when the page file is gone (deleted or never existed), so a
     *         reload is guaranteed to build a fresh page; {@code false} when the file
     *         could not be removed (read-only mode, I/O failure) and the caller should
     *         keep treating the page as damaged
     */
    public boolean rebuildPageFrom(int regionX, int regionZ) {
        if (closed || !enabled) {
            return true; // no page files exist in this state; a fresh page is created anyway
        }
        long key = pack(regionX, regionZ);
        Segment seg = segments[segmentIndex(key)];
        seg.lock.lock();
        try {
            Entry removed = seg.map.remove(key);
            if (removed != null && removed.dirty) {
                // Clamped like the flush decrement: a concurrent flush may already have
                // written this entry (and decremented) while it was still cached.
                dirtyCount.updateAndGet(v -> Math.max(0, v - 1));
            }
        } finally {
            seg.lock.unlock();
        }
        if (readOnly) {
            // Read-only mode never touches disk: the corrupt file stays in place, so a
            // fresh page cannot be loaded for it. Report the page as still damaged so
            // the caller skips the region instead of failing the open.
            return !Files.exists(pagePath(regionX, regionZ));
        }
        try {
            Files.deleteIfExists(pagePath(regionX, regionZ));
            return true;
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: failed to delete damaged region page {0}: {1}",
                    pagePath(regionX, regionZ), e.toString());
            return false;
        }
    }

    /**
     * Marks every cached page invalid and drops them (Phase 1: in-memory only - no page
     * file is deleted, which keeps read-only mode safe). Readers must consult
     * {@link #isInvalidated()} and fall back to the HashMap after compaction/reshard;
     * {@link #updateSlot()} and {@link #flush()} become no-ops. The page files written
     * before the invalidation hold pre-compaction offsets and are removed by
     * {@link #deleteAllPageFiles()} (called by the shard right after compaction, and
     * again on {@link #close()} as a safety net) so the next open cannot load stale
     * slots.
     */
    public void invalidateAll() {
        if (closed) {
            return;
        }
        invalidated = true;
        for (Segment seg : segments) {
            seg.lock.lock();
            try {
                seg.map.clear();
            } finally {
                seg.lock.unlock();
            }
        }
        dirtyCount.set(0);
    }

    // ------------------------------------------------------------- persistence

    /**
     * Writes all dirty pages to {@code <idxDir>/<rx>.<rz>.idx} (called by the background
     * checkpoint thread, which has already forced the log watermark). Each page is
     * written under its own monitor, excluding concurrent {@link #updateSlot} mutations,
     * and without taking any segment lock while the monitor is held (the lock order is
     * segment lock, then page monitor; the dirty count is adjusted with an atomic
     * non-negative decrement).
     * No-op when disabled, read-only, closed, or invalidated (after invalidation the
     * cached pages would hold pre-compaction offsets and must not be persisted).
     *
     * @throws FolesiumException when a page cannot be written
     */
    public void flush() {
        if (closed || !enabled || readOnly || invalidated) {
            return;
        }
        flushPages();
    }

    /** Writes every dirty page; the caller has already passed the enable/close guards. */
    private void flushPages() {
        List<Entry> dirty = new ArrayList<>();
        for (Segment seg : segments) {
            seg.lock.lock();
            try {
                for (Entry e : seg.map.values()) {
                    if (e.dirty) {
                        dirty.add(e);
                    }
                }
            } finally {
                seg.lock.unlock();
            }
        }
        if (dirty.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(idxDir);
        } catch (IOException e) {
            throw new FolesiumException("Failed to create page index directory " + idxDir, e);
        }
        for (Entry e : dirty) {
            long key = pack(e.page.regionX(), e.page.regionZ());
            Segment seg = segments[segmentIndex(key)];
            // Re-verify residency under the owning segment lock and write while still
            // holding it: a concurrent rebuildPageFrom() may have removed this entry (and
            // deleted its corrupt backing file) after the collection above, in which case
            // writing the cached page back would resurrect the damaged page file. Holding
            // the segment lock across the check and the write closes the race, and the
            // page monitor is taken inside in the documented order (segment lock -> page
            // monitor, exactly like updateSlot - no code path acquires a segment lock
            // while holding the page monitor). The dirty count is decremented atomically
            // with a non-negative clamp instead of a segment-locked one: a concurrent
            // rebuildPageFrom()/invalidateAll() may already have decremented or reset it
            // for this entry, and the counter only backs dirtyPages() reporting (flush
            // always re-scans the segment maps), so a spurious decrement is harmless as
            // long as it never drives the counter negative.
            seg.lock.lock();
            try {
                if (seg.map.get(key) != e) {
                    continue; // removed by rebuildPageFrom: the region starts fresh, nothing to flush
                }
                synchronized (e) {
                    if (!e.dirty || invalidated) {
                        continue;
                    }
                    try {
                        e.page.write(pagePath(e.page.regionX(), e.page.regionZ()));
                        e.dirty = false;
                        e.persisted = true;
                        dirtyCount.updateAndGet(v -> Math.max(0, v - 1));
                    } catch (IOException ex) {
                        throw new FolesiumException("Failed to write region page "
                                + pagePath(e.page.regionX(), e.page.regionZ()), ex);
                    }
                }
            } finally {
                seg.lock.unlock();
            }
        }
    }

    /**
     * Flushes dirty pages and writes the hint file: a page-file manifest, one
     * {@code rx.rz} per line, at {@code <idxDir>/hint} (Phase 1 simplification).
     * Skipped entirely in read-only mode; idempotent.
     *
     * <p>If the index was invalidated (compaction/reshard), the stale page files written
     * before the invalidation are deleted first so that the next open cannot load slots
     * pointing at pre-compaction log offsets; the hint then lists an empty page set.
     * In-memory pages were already dropped by {@link #invalidateAll()}, so nothing stale
     * is flushed.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (enabled && !readOnly) {
            // flush() guards on closed, so call the work directly here.
            flushPages();
            if (invalidated) {
                deleteAllPageFiles();
            }
            writeHint();
        }
    }

    /**
     * Deletes every region page file ({@code *.idx}) in the index directory, keeping the
     * dictionary, watermark and hint files. Called by the shard right after compaction -
     * the stale pages hold pre-compaction log offsets and must not survive to the next
     * open - and again on {@link #close()} as a safety net. Best-effort: failures are
     * logged, never thrown. Callers must have invalidated the index first (e.g. via
     * {@link #invalidateAll()}) so no in-memory page can be flushed back over the
     * deletion. No-op in read-only mode, like {@link #flushWatermarks()} and
     * {@link #setCompactionWatermark()}: a read-only open must never delete page files.
     */
    public void deleteAllPageFiles() {
        if (readOnly) {
            return;
        }
        if (!Files.isDirectory(idxDir)) {
            return;
        }
        try (var stream = Files.list(idxDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(PAGE_SUFFIX)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Folesium: failed to delete region page {0}: {1}", p, e.toString());
                }
            });
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: failed to list page files for cleanup in {0}: {1}", idxDir, e.toString());
        }
    }

    /** Best-effort hint write; failures are logged, never thrown (hint is regenerable). */
    private void writeHint() {
        List<int[]> pages = new ArrayList<>();
        if (Files.isDirectory(idxDir)) {
            try (var stream = Files.list(idxDir)) {
                stream.forEach(p -> addPageFile(pages, p.getFileName().toString()));
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: failed to list page files for hint {0}: {1}", idxDir, e.toString());
            }
        }
        if (pages.isEmpty() && !Files.isDirectory(idxDir)) {
            return; // nothing was ever persisted: no page set to describe
        }
        pages.sort(Comparator.comparingInt((int[] a) -> a[0]).thenComparingInt((int[] a) -> a[1]));
        StringBuilder sb = new StringBuilder();
        for (int[] p : pages) {
            sb.append(p[0]).append('.').append(p[1]).append('\n');
        }
        Path hint = idxDir.resolve(HINT_FILE);
        Path tmp = idxDir.resolve(HINT_FILE + ".tmp");
        try {
            Files.createDirectories(idxDir);
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            moveReplacing(tmp, hint);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: failed to write hint {0}: {1}", hint, e.toString());
        }
    }

    private static void addPageFile(List<int[]> pages, String name) {
        if (!name.endsWith(PAGE_SUFFIX)) {
            return;
        }
        String stem = name.substring(0, name.length() - PAGE_SUFFIX.length());
        int dot = stem.indexOf('.');
        if (dot <= 0 || dot == stem.length() - 1) {
            return;
        }
        try {
            pages.add(new int[]{Integer.parseInt(stem.substring(0, dot)), Integer.parseInt(stem.substring(dot + 1))});
        } catch (NumberFormatException ignored) {
            // Not one of our page files; skip it.
        }
    }

    /**
     * Moves {@code source} over {@code target}, preferring an atomic replace but falling
     * back to a plain replace move on filesystems that do not support atomic moves
     * (reported either as {@link AtomicMoveNotSupportedException} or as a generic
     * filesystem error).
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

    // ------------------------------------------------------------- watermarks

    /**
     * Returns the checkpointed log watermark for a shard: the offset up to which the
     * log has been indexed and persisted. Served from the in-memory cache; on a miss
     * the {@code <shardName>.wmk} file is read (missing or corrupt files yield
     * {@code 0}) and cached.
     */
    public long shardWatermark(String shardName) {
        Long cached = shardWatermarks.get(shardName);
        if (cached != null) {
            return cached;
        }
        long watermark = WatermarkFile.read(idxDir.resolve(shardName + WATERMARK_SUFFIX));
        shardWatermarks.put(shardName, watermark);
        return watermark;
    }

    /**
     * Records a new shard watermark in memory (write-path hook, called under the shard
     * write lock) and marks it pending for the next {@link #flushWatermarks()}. The
     * value is monotonic per shard: a concurrent lower update never regresses a higher
     * one, in memory or in the pending set.
     */
    public void advanceShardWatermark(String shardName, long watermark) {
        shardWatermarks.merge(shardName, watermark, Math::max);
        pendingWatermarks.merge(shardName, watermark, Math::max);
    }

    /**
     * Writes every pending shard watermark to {@code <idxDir>/<shardName>.wmk}
     * (checkpoint hook). No-op in read-only mode. A watermark advanced concurrently
     * with the flush is not dropped: only the entry whose value was actually written
     * is removed from the pending set.
     *
     * @throws IOException when a watermark file cannot be written
     */
    public void flushWatermarks() throws IOException {
        if (readOnly || pendingWatermarks.isEmpty()) {
            return;
        }
        Files.createDirectories(idxDir);
        for (Map.Entry<String, Long> pending : pendingWatermarks.entrySet()) {
            Path file = idxDir.resolve(pending.getKey() + WATERMARK_SUFFIX);
            WatermarkFile.write(file, pending.getValue());
            pendingWatermarks.remove(pending.getKey(), pending.getValue());
        }
    }

    /**
     * Whether a compaction anchor file exists for this shard. A present {@code .cwmk}
     * means a compaction completed at some point - and with it, page files that may
     * still hold pre-compaction log offsets (the deletion in {@link #deleteAllPageFiles()}
     * happens right after the swap, so a crash between the two leaves stale pages on
     * disk). The open-time page build uses this to decide whether on-disk pages must be
     * discarded and rebuilt from the log instead of being trusted.
     */
    public boolean compactionWatermarkExists(String shardName) {
        return Files.isRegularFile(idxDir.resolve(shardName + COMPACTION_WATERMARK_SUFFIX));
    }

    /**
     * Returns the compaction anchor for a shard: the log EOF at the last completed
     * compaction, used as the rebuild start for damaged region pages. Served from the
     * in-memory cache; on a miss the {@code <shardName>.cwmk} file is read (missing or
     * corrupt files yield {@code 0}) and cached.
     */
    public long compactionWatermark(String shardName) {
        Long cached = compactionWatermarks.get(shardName);
        if (cached != null) {
            return cached;
        }
        long watermark = WatermarkFile.read(idxDir.resolve(shardName + COMPACTION_WATERMARK_SUFFIX));
        compactionWatermarks.put(shardName, watermark);
        return watermark;
    }

    /**
     * Persists the compaction anchor for a shard to {@code <idxDir>/<shardName>.cwmk}
     * (called when compaction completes) and refreshes the in-memory cache. No-op in
     * read-only mode.
     *
     * @throws IOException when the anchor file cannot be written
     */
    public void setCompactionWatermark(String shardName, long watermark) throws IOException {
        if (readOnly) {
            return;
        }
        Files.createDirectories(idxDir);
        WatermarkFile.write(idxDir.resolve(shardName + COMPACTION_WATERMARK_SUFFIX), watermark);
        compactionWatermarks.put(shardName, watermark);
    }

    // ----------------------------------------------------------------- metrics

    /** Number of pageFor/updateSlot lookups satisfied from the cache. */
    public long cacheHits() {
        return cacheHits.get();
    }

    /** Number of pageFor/updateSlot lookups that had to load or create a page. */
    public long cacheMisses() {
        return cacheMisses.get();
    }

    /** Number of pages with unflushed updates. */
    public int dirtyPages() {
        return dirtyCount.get();
    }

    /** {@code false} when constructed with {@code cacheBytes = 0} (pure v1 behaviour). */
    public boolean isEnabled() {
        return enabled;
    }

    /** True after {@link #invalidateAll()} (compaction/reshard) until close. */
    public boolean isInvalidated() {
        return invalidated;
    }
}
