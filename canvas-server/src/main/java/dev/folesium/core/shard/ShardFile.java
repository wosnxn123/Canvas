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

package dev.folesium.core.shard;

import dev.folesium.core.FolesiumConfig;
import dev.folesium.core.FolesiumConfig.Compression;
import dev.folesium.core.FolesiumException;
import dev.folesium.core.index.PageIndex;
import dev.folesium.core.index.RegionPage;
import dev.folesium.core.util.Bytes;
import dev.folesium.core.util.Compressors;
import dev.folesium.core.util.LongKeys;
import dev.folesium.core.util.ZstdNative;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.zip.CRC32C;

/**
 * One append-only log shard of a keyspace.
 *
 * <p>On-disk format (all integers big-endian):</p>
 * <pre>
 * file header (16 bytes): "FLSM" | u16 version=1 | u16 reserved | u32 shardIndex | u32 shardCount
 * record:
 *   u8  magic 0xF5
 *   u8  flags        bits 0-3: compression id, bit 4: tombstone
 *   u16 keyLen
 *   u32 rawValLen    (uncompressed value length, 0 for tombstone)
 *   u32 storedValLen (stored value bytes,        0 for tombstone)
 *   key[keyLen]
 *   value[storedValLen]
 *   u32 crc32c       (over all previous bytes of the record)
 * </pre>
 *
 * <p>Recovery: on open the shard is scanned sequentially; the first record that
 * fails magic/bounds/CRC validation marks the end of the valid prefix and the
 * file is truncated there (torn-write recovery). A hint file
 * ({@code *.fidx}) written on clean close allows skipping the scan. When a page
 * index exists, the region pages are then rebuilt by replaying the log from the
 * shard's compaction watermark ({@code <shardName>.cwmk}) to EOF, so every live
 * chunk record has a page slot and tombstones clear theirs; a record that fails
 * validation truncates the shard there, and page slots are trimmed lazily by
 * treating any offset at or beyond the log EOF as a miss.</p>
 *
 * <p>Thread model: many concurrent readers OR one writer per shard
 * ({@link ReentrantReadWriteLock}). Different shards are fully independent, so
 * Folia region threads writing to different shards never contend.</p>
 */
public final class ShardFile implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("Folesium");

    static final byte[] FILE_MAGIC = {'F', 'L', 'S', 'M'};
    /** Size of the fixed file header; a shard file larger than this actually holds records. */
    public static final int FILE_HEADER_LEN = 16;
    static final short FORMAT_VERSION = 1;

    static final byte RECORD_MAGIC = (byte) 0xF5;
    static final int RECORD_HEADER_LEN = 12;
    static final int FLAG_TOMBSTONE = 0x10;
    static final int MAX_KEY_LEN = 0xFFFF;
    static final int MAX_VALUE_LEN = 256 * 1024 * 1024;

    private record Loc(long recordOffset, int recordLength, int keyLen, int rawValLen, int storedValLen, byte flags) {
        long valueOffset() {
            return recordOffset + RECORD_HEADER_LEN + keyLen;
        }
    }
    private record IterationEntry(byte[] key, byte[] value) {}

    private final Path path;
    private final Path hintPath;
    private final int shardIndex;
    /**
     * Shard topology this file was opened with. Snapshotted separately from
     * {@link #config} because it is stamped into the file header: the header must keep
     * describing the physical layout even if the runtime configuration is hot-reloaded
     * with a different shard count (which only takes effect after a reshard).
     */
    private final int shardCount;
    /**
     * Live configuration. Replaced wholesale by {@link #applyRuntimeConfig}; every read
     * below goes through this field so a swap is picked up by the next operation.
     * {@link FolesiumConfig} is an immutable record, so readers always observe a
     * self-consistent snapshot - there is no torn-config window.
     */
    private volatile FolesiumConfig config;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private FileChannel channel;
    private Map<Bytes, Loc> index = new HashMap<>();
    /**
     * Whether {@link #index} was populated from the hint file (see {@link #tryLoadHint})
     * rather than by a full log scan. A build-time truncation (torn tail) invalidates
     * hint-loaded entries at or past the new EOF, so {@link #truncateAt} triggers a full
     * rescan to rebuild the index and drop those ghost entries. Only meaningful during the
     * constructor's recovery phase.
     */
    private boolean hintLoaded;
    private long writePos;
    private long deadBytes;
    private volatile boolean dirty;
    /**
     * Number of records appended to this shard since it was opened (puts,
     * putIfAbsent appends and delete tombstones; no-ops - a putIfAbsent that found
     * the key or a delete of an absent key - are not counted because no record was
     * written). Drives the workload-compaction priority: a shard with heavy write
     * churn is compacted ahead of an equally dead shard with little traffic. The
     * counter is not reset by compaction: priority is an all-time write-frequency
     * signal and the log1p scaling keeps old counters from dominating the score.
     */
    private final AtomicLong writeCount = new AtomicLong();
    /**
     * Optional per-keyspace region-page index. When non-null, 8-byte chunk keys are
     * mirrored into region pages on every write and probed first on reads. In AUTO
     * mode the pages are a pure acceleration cache whose correctness is guaranteed by
     * {@link #index}; in PAGE mode ({@link #pageAuthoritative}) they are the only
     * index for chunk keys, rebuilt at open from the compaction watermark so every
     * live chunk has a slot. Null for non-region-keyed keyspaces, when the page index
     * is disabled ({@code indexCacheBytes == 0}), or for standalone shards. Owned and
     * closed by the {@code Keyspace}, never by this shard.
     */
    private final PageIndex pageIndex;
    /**
     * Name of this shard used to key its watermark files in the page-index directory
     * ({@code <shardName>.wmk} for the checkpoint watermark, {@code <shardName>.cwmk}
     * for the compaction anchor). Equals the shard file name without extension (e.g.
     * {@code chunks-0000}), supplied by the {@code Keyspace}. {@code null} for shards
     * without a page index, where no watermark files exist.
     */
    private final String shardName;
    /**
     * True when {@code indexMode == PAGE}: for 8-byte chunk keys the region page is the
     * only index, so a slot of 0 means the key is truly absent and reads never fall back
     * to the HashMap. AUTO keeps the Phase 1 fallback semantics. Only consulted while a
     * page index exists and is not invalidated.
     */
    private final boolean pageAuthoritative;
    /**
     * Immutable per-keyspace dictionary used for codec-3 (ZSTD_DICT) records, or
     * {@code null} when dictionary compression is disabled, no dictionary exists for this
     * keyspace, or zstd dictionary support is unavailable. Snapshotted at construction: every
     * record this shard writes uses this same dictionary, so codec-3 records stay decodable for
     * as long as the keyspace is open. Owned by the {@code Keyspace}; this shard never mutates
     * the bytes.
     */
    private final byte[] keyspaceDict;
    /**
     * Read-only mode: the shard may be read but never written. The channel is opened
     * without CREATE/WRITE, a fresh or torn-header shard is treated as empty (nothing is
     * written, nothing truncated), torn tails are left in place, {@link #compact()} is a
     * no-op, and {@link #close()} skips the fsync and the hint file.
     */
    private final boolean readOnly;
    /**
     * Whether the open-time region-page rebuild left unresolved damage: a region whose
     * corrupt page file could not be removed (the page stayed corrupt, so PAGE-mode
     * reads must keep falling back to the HashMap and the next open retries the
     * repair), or the build was skipped entirely because the shard header was
     * torn/invalid in a read-only open (nothing was replayed, so this shard's chunks
     * are absent from every rebuilt region page). Set by the constructor; consumed by
     * the {@code Keyspace}, which aggregates the flags of all shards before deciding
     * whether the keyspace-global {@link PageIndex} damage markers may be cleared: a
     * single shard's clean rebuild cannot prove every shared region page complete (a
     * region's chunks hash across shards), so markers are kept unless <em>every</em>
     * shard rebuilt without unresolved regions.
     */
    private boolean hasUnresolvedRegions;

    public ShardFile(Path path, int shardIndex, FolesiumConfig config, PageIndex pageIndex,
                     String shardName, boolean pageAuthoritative, byte[] keyspaceDict, boolean readOnly) {
        this.path = path;
        this.hintPath = path.resolveSibling(path.getFileName() + ".fidx");
        this.shardIndex = shardIndex;
        this.shardCount = config.shardCount();
        this.config = config;
        this.pageIndex = pageIndex;
        this.shardName = shardName;
        this.pageAuthoritative = pageAuthoritative;
        this.keyspaceDict = keyspaceDict;
        this.readOnly = readOnly;
        try {
            boolean fresh = !Files.exists(path) || Files.size(path) == 0;
            this.channel = FileChannel.open(path, readOnly
                    ? new StandardOpenOption[]{StandardOpenOption.READ}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE});
            boolean tornInReadOnly = false;
            if (fresh) {
                if (readOnly) {
                    // Nothing may be written in read-only mode: an empty shard stays empty
                    // (no header is written), with the write position at the header offset.
                    this.writePos = FILE_HEADER_LEN;
                } else {
                    writeFileHeader();
                    this.writePos = FILE_HEADER_LEN;
                }
            } else {
                try {
                    validateFileHeader();
                } catch (EOFException tornHeader) {
                    // The file is non-empty but too short to hold a header - a crash tore the
                    // shard before a valid header was ever written. There is no valid header to
                    // anchor the record-level scan below, so treat the whole shard as torn:
                    // discard it and start fresh. Shards with a valid header are unaffected -
                    // scanAndRecover() keeps handling torn tails.
                    if (readOnly) {
                        // Read-only mode must not truncate or rewrite the file: treat the torn
                        // header as an empty shard and warn instead. The next read-write open
                        // performs the real torn-header recovery.
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Folesium recovery: shard {0} has a torn/corrupt header ({1}); "
                                        + "read-only open treats it as empty",
                                path, tornHeader.toString());
                        this.writePos = FILE_HEADER_LEN;
                        tornInReadOnly = true;
                    } else {
                        discardTornShard(tornHeader.toString());
                    }
                } catch (FolesiumException invalidHeader) {
                    // A header failing magic/version/topology validation is *valid data* in a
                    // read-write open (a mismatched shard must fail loudly, not be discarded),
                    // so it propagates there unchanged. A read-only open, however, must still
                    // bring up the rest of the store: treat the shard as empty exactly like the
                    // torn-header case, mirroring the discovery layer
                    // (Keyspace.readRecordedShardCount), which skips unreadable headers and
                    // falls back to the names-derived shard count instead of failing the whole
                    // keyspace - e.g. a reshard interrupted between the file swap and the
                    // metadata rewrite can leave shard files that disagree with the recorded
                    // topology, and the read-only open must not refuse the whole store for one
                    // such shard. The next read-write open re-validates and repairs it.
                    if (readOnly) {
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Folesium recovery: shard {0} has an invalid header ({1}); "
                                        + "read-only open treats it as empty",
                                path, invalidHeader.toString());
                        this.writePos = FILE_HEADER_LEN;
                        tornInReadOnly = true;
                    } else {
                        throw invalidHeader;
                    }
                }
                if (!tornInReadOnly && !tryLoadHint()) {
                    scanAndRecover();
                }
            }
            // Phase 2: mirror the log into the region pages. The HashMap above (hint or
            // scan) is authoritative for AUTO mode; the incremental build below makes the
            // pages complete for PAGE mode, where they are the only index at open. A shard
            // whose header was torn/invalid in a read-only open is treated as empty (nothing
            // was loaded into the HashMap), so the pages must agree and stay empty too -
            // replaying a header-less/garbage log through the compaction anchor would parse
            // misaligned records into page slots the HashMap does not know about.
            if (!tornInReadOnly && pageIndex != null) {
                Set<Long> unresolvedRegions = buildPagesFromCompactionAnchor();
                // The damage-marker set lives on the keyspace-global PageIndex shared by
                // every shard, while the replay above only rebuilt this shard's own log
                // range into the shared region pages - and a region's chunks hash across
                // shards. No single shard can therefore decide that every marker is
                // stale: the previous per-shard clearAllDamage let one shard's clean
                // build wipe the unresolved-region markers another shard had just set,
                // silently losing PAGE-mode data for those regions. Record whether this
                // build left any region whose corrupt page file could not be removed
                // (the page stayed corrupt, so PAGE-mode reads must keep falling back
                // to the HashMap and the next open retries the repair); the Keyspace
                // aggregates the flags of every shard and clears the markers only when
                // all of them rebuilt cleanly. See PageIndex.clearAllDamage.
                hasUnresolvedRegions = !unresolvedRegions.isEmpty();
            } else if (tornInReadOnly) {
                // A torn/invalid header in a read-only open skips the build above -
                // nothing was replayed, so this shard's chunks are absent from every
                // rebuilt region page and no marker may be cleared on its account.
                // Treat the shard as having unresolved regions (conservative).
                hasUnresolvedRegions = true;
            }
        } catch (Throwable e) {
            closeAfterOpenFailure(e);
            if (e instanceof IOException io) {
                throw new FolesiumException("Failed to open shard " + path, io);
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new AssertionError(e);
        }
    }
    private void closeAfterOpenFailure(Throwable failure) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * Atomically swaps in a new runtime configuration. Every subsequent read/write/compact
     * observes the new values; nothing on disk is touched.
     *
     * @throws IllegalArgumentException if {@code next} carries a different shard count - the
     *                                  topology is baked into the file header and can only be
     *                                  changed by rewriting the store.
     */
    public void applyRuntimeConfig(FolesiumConfig next) {
        if (next.shardCount() != shardCount) {
            throw new IllegalArgumentException("Cannot change shardCount on an open shard "
                    + path + " (" + shardCount + " -> " + next.shardCount() + ")");
        }
        this.config = next;
    }

    /** Physical shard topology recorded in this file's header. */
    public int shardCount() {
        return shardCount;
    }

    /**
     * Returns whether this shard's open-time page rebuild left unresolved region damage
     * markers: a corrupt page file that could not be removed during the build, or a
     * build skipped because the shard header was torn/invalid in a read-only open. The
     * {@code Keyspace} aggregates this flag across all shards: it clears the
     * keyspace-global {@link PageIndex} damage markers only when every shard reports
     * {@code false}, and keeps every marker when any shard reports {@code true}
     * (conservative - a region's chunks hash across shards, so a partial rebuild
     * leaves the whole region untrustworthy in PAGE mode until the next open).
     */
    public boolean hasUnresolvedRegions() {
        return hasUnresolvedRegions;
    }

    /**
     * The log offset at which the last compaction finished - the anchor the next open
     * uses to rebuild the region pages (incremental scan of {@code [anchor, EOF)}).
     * Returns 0 when there is no page index or the shard has no name. Contract §3.
     */
    public long compactionAnchor() {
        if (pageIndex == null || shardName == null) {
            return 0;
        }
        return pageIndex.compactionWatermark(shardName);
    }

    // ------------------------------------------------------------------ open

    private void writeFileHeader() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(FILE_HEADER_LEN);
        b.put(FILE_MAGIC).putShort(FORMAT_VERSION).putShort((short) 0)
                .putInt(shardIndex).putInt(shardCount);
        b.flip();
        writeFully(b, 0);
        channel.force(false);
    }

    private void validateFileHeader() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(FILE_HEADER_LEN);
        readFully(b, 0);
        byte[] magic = new byte[4];
        b.get(magic);
        if (!java.util.Arrays.equals(magic, FILE_MAGIC)) {
            throw new FolesiumException("Bad shard magic in " + path);
        }
        short version = b.getShort();
        if (version != FORMAT_VERSION) {
            throw new FolesiumException("Unsupported shard format version " + version + " in " + path);
        }
        b.getShort();
        int idx = b.getInt();
        int count = b.getInt();
        if (idx != shardIndex || count != shardCount) {
            throw new FolesiumException("Shard topology mismatch in " + path
                    + " (file: " + idx + "/" + count + ", expected: " + shardIndex + "/" + shardCount + ")");
        }
    }

    /**
     * Discards a shard whose header could not be validated: truncates the file to zero,
     * writes a fresh header and rewinds the write position, so a header torn by a crash
     * (or a garbage file) no longer prevents the store from opening.
     */
    private void discardTornShard(String detail) throws IOException {
        LOGGER.log(System.Logger.Level.WARNING,
                "Folesium recovery: shard {0} has a torn/corrupt header ({1}); truncating it and starting fresh",
                path, detail);
        try {
            Files.deleteIfExists(hintPath); // stale hint describes the discarded log
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium recovery: could not delete stale hint {0}: {1}", hintPath, e.toString());
        }
        channel.truncate(0);
        writeFileHeader();
        this.writePos = FILE_HEADER_LEN;
    }

    /** Full sequential scan; truncates at the first torn/corrupt record (read-only: leaves the tail). */
    private void scanAndRecover() throws IOException {
        long fileSize = channel.size();
        ScanOutcome outcome = scanRange(FILE_HEADER_LEN, fileSize, this::applyScanRecord);
        if (outcome.firstInvalidOffset < fileSize) {
            if (readOnly) {
                // Read-only mode never truncates: the torn tail stays on disk, but the
                // index (and writePos, the logical end of valid data) stop at the first
                // invalid record. The tail is repaired by the next read-write open.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium recovery: shard {0} has a torn tail at {1} ({2}); "
                                + "read-only open leaves it in place",
                        path, outcome.firstInvalidOffset, outcome.reason);
            } else {
                truncateAt(outcome.firstInvalidOffset, outcome.reason);
            }
        }
        writePos = outcome.firstInvalidOffset;
    }

    /**
     * Applies one validated record to the HashMap index: puts/removes the key and
     * tracks dead bytes, exactly as the pre-refactor {@code scanAndRecover} loop did.
     */
    private void applyScanRecord(byte[] key, byte flags, long recordOffset, int recordLength,
                                 int rawValLen, int storedValLen) {
        Bytes k = new Bytes(key);
        Loc old;
        if ((flags & FLAG_TOMBSTONE) != 0) {
            old = index.remove(k);
            deadBytes += recordLength; // tombstone itself is dead weight
        } else {
            old = index.put(k, new Loc(recordOffset, recordLength, key.length, rawValLen, storedValLen, flags));
        }
        if (old != null) {
            deadBytes += old.recordLength;
        }
    }

    /** Callback for every record validated by {@link #scanRange}. */
    @FunctionalInterface
    private interface RecordHandler {
        void accept(byte[] key, byte flags, long recordOffset, int recordLength, int rawValLen, int storedValLen);
    }

    /** Outcome of a range scan: the first offset that failed validation, or {@code eof} when clean. */
    private record ScanOutcome(long firstInvalidOffset, String reason) {
    }

    /**
     * Parses the records of {@code [start, eof)}, validating magic, bounds and CRC
     * exactly like the full recovery scan, and feeds every valid record to
     * {@code handler}. Stops at the first record that fails validation and returns
     * its offset so the caller can truncate there; returns {@code eof} when the whole
     * range is clean. Shared by {@link #scanAndRecover} (HashMap rebuild) and
     * {@link #buildPagesFromCompactionAnchor} (page incremental build).
     */
    private ScanOutcome scanRange(long start, long eof, RecordHandler handler) throws IOException {
        long pos = start;
        ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_LEN);
        CRC32C crc = new CRC32C();
        while (pos < eof) {
            long recordStart = pos;
            header.clear();
            try {
                readFully(header, pos);
            } catch (EOFException e) {
                return new ScanOutcome(recordStart, "torn record header");
            }
            byte magic = header.get();
            byte flags = header.get();
            int keyLen = header.getShort() & 0xFFFF;
            int rawValLen = header.getInt();
            int storedValLen = header.getInt();

            if (magic != RECORD_MAGIC || keyLen == 0
                    || rawValLen < 0 || rawValLen > MAX_VALUE_LEN
                    || storedValLen < 0 || storedValLen > MAX_VALUE_LEN
                    || recordStart + RECORD_HEADER_LEN + keyLen + storedValLen + 4L > eof) {
                return new ScanOutcome(recordStart, "invalid record header");
            }

            int bodyLen = keyLen + storedValLen;
            ByteBuffer body = ByteBuffer.allocate(bodyLen + 4);
            try {
                readFully(body, recordStart + RECORD_HEADER_LEN);
            } catch (EOFException e) {
                return new ScanOutcome(recordStart, "torn record body");
            }

            crc.reset();
            header.rewind();
            crc.update(header);
            crc.update(body.slice(0, bodyLen));
            int expected = body.getInt(bodyLen);
            if ((int) crc.getValue() != expected) {
                return new ScanOutcome(recordStart, "CRC mismatch");
            }

            byte[] key = new byte[keyLen];
            body.position(0);
            body.get(key);
            int recordLength = RECORD_HEADER_LEN + bodyLen + 4;
            handler.accept(key, flags, recordStart, recordLength, rawValLen, storedValLen);
            pos = recordStart + recordLength;
        }
        return new ScanOutcome(pos, null);
    }

    /**
     * Rebuilds this shard's region pages by replaying every record in
     * {@code [compactionWatermark, EOF)}: the compaction watermark is the log offset at
     * which the last compaction finished, so everything at or above it is an append made
     * after the pages were last known-good. Live 8-byte chunk keys update their slot to
     * the record offset; tombstones clear the slot (0 = absent). Records are validated
     * with the same magic/bounds/CRC rules as {@link #scanAndRecover} - a record that
     * fails validation truncates the shard there (torn tail), so no page slot built here
     * can point past the new EOF (slots loaded from older page files are trimmed lazily
     * on reads by {@link #pageIndexLoc}).
     *
     * <p>Runs in the constructor whenever a page index exists, in AUTO as well as PAGE
     * mode (in AUTO the pages are an acceleration cache, in PAGE they are the only index
     * at open). The built pages end up dirty and are persisted by the next checkpoint or
     * close. A damaged page file is repaired in place (the corrupt file is deleted so the
     * replay continues into a fresh page) rather than failing the open; only a page whose
     * file cannot be removed is skipped for the rest of the replay.
     *
     * @return the regions whose corrupt page file could not be removed this open
     *         (read-only mode or an I/O failure) and were skipped for the rest of the
     *         replay. The caller must keep their damage markers in place so PAGE-mode
     *         reads fall back to the HashMap and the next open retries the repair; every
     *         other marker is stale once the replay has made the pages complete again and
     *         may be cleared (see {@link PageIndex#clearAllDamage()}). Runtime single-slot
     *         writes never clear markers.
     */
    private Set<Long> buildPagesFromCompactionAnchor() throws IOException {
        if (shardName == null) {
            return Set.of(); // defensive: without a shard name there is no watermark file to anchor on
        }
        // A completed compaction (a .cwmk file exists) means page files may hold
        // pre-compaction log offsets - the deletion after the swap is a separate step,
        // so a crash between the two leaves stale pages on disk. Discard them so the
        // replay below rebuilds every page from the (post-compaction) log instead of
        // merging into stale slots. Without a .cwmk the pages on disk were written by
        // ordinary checkpoints and stay valid. Read-only opens skip the deletion (their
        // page files must never be touched): the replay below still rebuilds every page
        // in memory, and the stale files keep their place until a read-write open
        // discards them.
        if (!readOnly && pageIndex.compactionWatermarkExists(shardName)) {
            pageIndex.deleteAllPageFiles();
        }
        long eof = channel.size();
        long anchor = Math.max(FILE_HEADER_LEN, Math.min(pageIndex.compactionWatermark(shardName), eof));
        Set<Long> corruptRegions = new HashSet<>();
        ScanOutcome outcome = scanRange(anchor, eof, (key, flags, recordOffset, recordLength, rawValLen, storedValLen) -> {
            if (key.length != 8) {
                return; // only chunk keys have page slots
            }
            long chunkKey = LongKeys.decode(key);
            int regionX = RegionPage.regionXFromChunk(chunkKey);
            int regionZ = RegionPage.regionZFromChunk(chunkKey);
            int slot = RegionPage.slotIndex(LongKeys.chunkX(chunkKey), LongKeys.chunkZ(chunkKey));
            long region = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
            if (corruptRegions.contains(region)) {
                return;
            }
            try {
                pageIndex.updateSlot(regionX, regionZ, slot, (flags & FLAG_TOMBSTONE) != 0 ? 0 : pageSlotOffset(recordOffset));
            } catch (RuntimeException e) {
                // A damaged or unreadable page file must not fail the open. Repair it in
                // place: drop any cached entry and delete the corrupt file, so the next
                // updateSlot for this region loads a fresh page and keeps replaying the
                // log into it - in PAGE mode the page is the only index, so this replay
                // is what restores the region's data. The record that hit the damage is
                // retried against the fresh page: the replay is single-pass, so without
                // the retry that record's slot would stay empty. If the file cannot be
                // removed (read-only mode, permissions), skip the region for the rest of
                // this replay rather than failing once per record.
                if (!pageIndex.rebuildPageFrom(regionX, regionZ)) {
                    corruptRegions.add(region);
                } else {
                    pageIndex.updateSlot(regionX, regionZ, slot,
                            (flags & FLAG_TOMBSTONE) != 0 ? 0 : pageSlotOffset(recordOffset));
                }
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: page build for {0} repaired damaged page of region ({1}, {2}): {3}",
                        path, regionX, regionZ, e.toString());
            }
        });
        if (outcome.firstInvalidOffset < eof) {
            if (readOnly) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium recovery: shard {0} has a torn tail at {1} ({2}); "
                                + "read-only open leaves it in place",
                        path, outcome.firstInvalidOffset, outcome.reason);
            } else {
                truncateAt(outcome.firstInvalidOffset, outcome.reason);
            }
        }
        return corruptRegions;
    }

    private void truncateAt(long pos, String reason) throws IOException {
        LOGGER.log(System.Logger.Level.WARNING,
                "Folesium recovery: truncating {0} at {1} ({2}, file size {3})",
                path, pos, reason, channel.size());
        channel.truncate(pos);
        channel.force(false);
        writePos = pos;
        // The hint file describes the pre-truncation log; drop it now. Its logLength check
        // would normally self-invalidate it on the next open (the file is shorter), but that
        // check is defeated once the log later regrows to the same length: the stale hint
        // would then pass and load entries pointing past the truncation point. Deleting it
        // outright forces a full scan on the next open - mirrors discardTornShard().
        try {
            Files.deleteIfExists(hintPath);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium recovery: could not delete stale hint {0}: {1}", hintPath, e.toString());
        }
        if (hintLoaded) {
            // The index was loaded from the hint, which described the pre-truncation log:
            // every entry at or past the truncation point is now a ghost pointing past EOF.
            // A later write reuses those offsets, so reading such a key would silently return
            // another record's bytes (or fail) instead of the key's real value. Rebuild the
            // index from the surviving prefix with a full rescan - the prefix precedes the
            // first invalid record, so this scan cannot truncate again (worst case it finds
            // another torn record below pos and truncates further, which is equally correct).
            // (The hint itself is already deleted above, so the next open rescans regardless.)
            hintLoaded = false;
            index = new HashMap<>();
            deadBytes = 0;
            scanAndRecover();
        }
    }

    // ------------------------------------------------------------------ hint

    private static final byte[] HINT_MAGIC = {'F', 'I', 'D', 'X'};

    private boolean tryLoadHint() {
        if (!Files.exists(hintPath)) {
            return false;
        }
        try {
            byte[] all = Files.readAllBytes(hintPath);
            if (all.length < 4 + 2 + 8 + 8 + 4 + 4) {
                return false;
            }
            ByteBuffer b = ByteBuffer.wrap(all);
            byte[] magic = new byte[4];
            b.get(magic);
            if (!java.util.Arrays.equals(magic, HINT_MAGIC) || b.getShort() != FORMAT_VERSION) {
                return false;
            }
            CRC32C crc = new CRC32C();
            crc.update(all, 0, all.length - 4);
            if ((int) crc.getValue() != ByteBuffer.wrap(all, all.length - 4, 4).getInt()) {
                return false;
            }
            long logLength = b.getLong();
            if (logLength != channel.size()) {
                return false; // log changed since hint was written (crash) -> full scan
            }
            long dead = b.getLong();
            int count = b.getInt();
            // Smallest conceivable entry is 2+1+8+4+4+1 = 20 bytes (keyLen is at least 1,
            // see the keyLen == 0 check below), so a count that cannot fit 19-byte entries
            // is certainly nonsense - refuse it instead of pre-sizing a huge map.
            if (count < 0 || (long) count * 19L > b.remaining()) {
                return false;
            }
            Map<Bytes, Loc> loaded = new HashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                int keyLen = b.getShort() & 0xFFFF;
                // scanRange() rejects zero-length keys; a hint entry describing one is corrupt.
                if (keyLen == 0) {
                    return false;
                }
                byte[] key = new byte[keyLen];
                b.get(key);
                long off = b.getLong();
                int rawValLen = b.getInt();
                int storedValLen = b.getInt();
                byte flags = b.get();
                // Mirror the record-header validation from scanRange(): an out-of-range
                // length is a corrupt entry, so discard the whole hint and fall back to
                // the full scan instead of indexing a bogus record size.
                if (rawValLen < 0 || rawValLen > MAX_VALUE_LEN
                        || storedValLen < 0 || storedValLen > MAX_VALUE_LEN) {
                    return false;
                }
                // A record offset must point inside the log exactly like every record
                // scanRange() would validate: below the file header or at/past EOF is a
                // stale or forged entry, and a record that would extend past the log is
                // equally invalid - discard the hint and fall back to the full scan.
                if (off < FILE_HEADER_LEN || off >= logLength
                        || off + RECORD_HEADER_LEN + keyLen + storedValLen + 4L > logLength) {
                    return false;
                }
                loaded.put(new Bytes(key),
                        new Loc(off, RECORD_HEADER_LEN + keyLen + storedValLen + 4, keyLen, rawValLen, storedValLen, flags));
            }
            this.index = loaded;
            this.writePos = logLength;
            this.deadBytes = dead;
            this.hintLoaded = true;
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: ignoring bad hint file {0}: {1}", hintPath, e.toString());
            return false;
        }
    }

    private void writeHint() {
        Path tmp = hintPath.resolveSibling(hintPath.getFileName() + ".tmp");
        try {
            int size = 4 + 2 + 8 + 8 + 4;
            for (Bytes k : index.keySet()) {
                size += 2 + k.length() + 8 + 4 + 4 + 1;
            }
            size += 4;
            ByteBuffer b = ByteBuffer.allocate(size);
            b.put(HINT_MAGIC).putShort(FORMAT_VERSION).putLong(writePos).putLong(deadBytes).putInt(index.size());
            for (Map.Entry<Bytes, Loc> e : index.entrySet()) {
                Loc loc = e.getValue();
                b.putShort((short) e.getKey().length()).put(e.getKey().array())
                        .putLong(loc.recordOffset).putInt(loc.rawValLen).putInt(loc.storedValLen).put(loc.flags);
            }
            CRC32C crc = new CRC32C();
            crc.update(b.array(), 0, b.position());
            b.putInt((int) crc.getValue());
            Files.write(tmp, b.array());
            moveReplacing(tmp, hintPath);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: failed to write hint {0}: {1}", hintPath, e.toString());
        } finally {
            // A failed write or move must not leave a stale .tmp behind: remove it
            // best-effort so the next hint write starts clean. (After a successful move
            // the .tmp no longer exists and this is a no-op.) Mirrors PageIndex.writeHint.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e2) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: failed to delete stale hint tmp {0}: {1}", tmp, e2.toString());
            }
        }
    }

    // ------------------------------------------------------------- read/write

    public byte[] get(Bytes key) {
        lock.readLock().lock();
        try {
            Loc pageLoc;
            try {
                pageLoc = pageIndexLoc(key);
            } catch (IOException e) {
                pageLoc = null; // stale page entry (e.g. its record was truncated); AUTO falls back to the HashMap
            }
            if (pageLoc != null) {
                byte[] stored = readStoredValue(pageLoc, config.verifyChecksums());
                Compression c = Compression.byId((byte) (pageLoc.flags & 0x0F));
                return decompressValue(c, stored, pageLoc);
            }
            if (pageOnly(key)) {
                return null; // PAGE mode: the page is the only index - a miss means the key is absent
            }
            Loc loc = index.get(key);
            if (loc == null) {
                return null;
            }
            byte[] stored = readStoredValue(loc, config.verifyChecksums());
            Compression c = Compression.byId((byte) (loc.flags & 0x0F));
            return decompressValue(c, stored, loc);
        } catch (IOException e) {
            throw new FolesiumException("Read failed in " + path, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    private byte[] readStoredValue(Loc loc, boolean verifyChecksums) throws IOException {
        if (verifyChecksums) {
            byte[] whole = readWholeRecord(loc, true);
            return java.util.Arrays.copyOfRange(whole, RECORD_HEADER_LEN + loc.keyLen,
                    RECORD_HEADER_LEN + loc.keyLen + loc.storedValLen);
        }
        ByteBuffer value = ByteBuffer.allocate(loc.storedValLen);
        readFully(value, loc.valueOffset());
        return value.array();
    }

    /**
     * Decompresses a stored value according to the record's codec. Codec 3 (ZSTD_DICT)
     * requires the keyspace dictionary this shard was opened with; when it is missing the
     * record cannot be decoded - that is a data problem (dictionary absent/corrupt), not a
     * silent miss, so it fails loudly.
     */
    private byte[] decompressValue(Compression c, byte[] stored, Loc loc) {
        if (c == Compression.ZSTD_DICT) {
            if (keyspaceDict == null) {
                throw new FolesiumException("codec 3 record but no dictionary loaded");
            }
            return Compressors.decompressWithDict(stored, keyspaceDict, loc.rawValLen);
        }
        return Compressors.decompress(c, stored, loc.rawValLen);
    }

    /** Reads a complete indexed record and optionally validates its CRC. */
    private byte[] readWholeRecord(Loc loc, boolean verifyChecksums) throws IOException {
        ByteBuffer whole = ByteBuffer.allocate(loc.recordLength);
        readFully(whole, loc.recordOffset);
        byte[] bytes = whole.array();
        if (verifyChecksums) {
            CRC32C crc = new CRC32C();
            crc.update(bytes, 0, loc.recordLength - 4);
            int expected = ByteBuffer.wrap(bytes, loc.recordLength - 4, 4).getInt();
            if ((int) crc.getValue() != expected) {
                throw new FolesiumException("CRC mismatch reading " + path + " @" + loc.recordOffset);
            }
        }
        return bytes;
    }

    public boolean contains(Bytes key) {
        lock.readLock().lock();
        try {
            Loc pageLoc;
            try {
                pageLoc = pageIndexLoc(key);
            } catch (IOException e) {
                pageLoc = null; // stale page entry; AUTO mode falls back to the HashMap
            }
            if (pageLoc != null) {
                return true;
            }
            if (pageOnly(key)) {
                return false; // PAGE mode: the page is the only index
            }
            return index.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Guards the write path. A read-only shard must never be written: its channel is
     * opened READ-only, so an unguarded write would only fail deep inside
     * {@code writeFully} with a confusing {@code NonWritableChannelException} wrapped
     * in "Write failed" (put/putIfAbsent already skipped the compression for a
     * key the read-lock pre-check found, but a genuine append still hits this guard).
     * A shard whose channel was released by a failed compaction restore must fail
     * loudly too: appending through the orphaned inode the field used to point at
     * would be silent data loss (every record lands in a file the next open never
     * sees). Caller holds the write lock.
     */
    private void ensureWritable() {
        if (readOnly) {
            throw new FolesiumException("store is read-only");
        }
        if (channel == null) {
            throw new FolesiumException("Shard " + path
                    + " is unusable: its file channel was closed after a failed compaction restore");
        }
    }

    public void put(Bytes key, byte[] rawValue) {
        if (key.length() == 0 || key.length() > MAX_KEY_LEN) {
            throw new IllegalArgumentException("Bad key length " + key.length());
        }
        if (rawValue.length > MAX_VALUE_LEN) {
            throw new IllegalArgumentException("Value too large: " + rawValue.length);
        }
        Compression c = config.compression();
        byte[] stored;
        if (config.dictionaryCompression() && keyspaceDict != null && ZstdNative.dictAvailable()) {
            c = Compression.ZSTD_DICT;
            stored = Compressors.compressWithDict(rawValue, keyspaceDict, config.compressionLevel());
        } else {
            // ZSTD_DICT config with the dictionary gate above closed: the per-keyspace
            // dictionary is missing (keyspaceDict == null - the keyspace is not region-keyed,
            // or no conversion ever ran) or the zstd-jni dictionary API is unavailable. The
            // FolesiumConfig invariant guarantees the flag is on whenever the codec is
            // ZSTD_DICT, so this branch means dictionary writes are impossible for this record;
            // degrade to plain ZSTD instead of failing record by record. Existing codec-3
            // records keep decoding against their own dictionary (codec comes from record flags).
            c = c == Compression.ZSTD_DICT ? Compression.ZSTD : c;
            stored = Compressors.compress(c, config.compressionLevel(), rawValue);
        }
        if (stored.length >= rawValue.length && c != Compression.NONE) {
            c = Compression.NONE; // incompressible value: store raw
            stored = rawValue;
        }
        byte flags = c.id;
        byte[] record = encodeRecord(flags, key.array(), rawValue.length, stored);

        lock.writeLock().lock();
        try {
            ensureWritable();
            long off = writePos;
            writeFully(ByteBuffer.wrap(record), off);
            Loc old = index.put(key, new Loc(off, record.length, key.length(), rawValue.length, stored.length, flags));
            if (old != null) {
                deadBytes += old.recordLength;
            }
            writePos = off + record.length;
            updatePageIndex(key, off, false);
            advanceShardWatermark(writePos);
            writeCount.incrementAndGet();
            dirty = true;
            if (config.durability() == FolesiumConfig.DurabilityMode.ALWAYS) {
                channel.force(false);
                dirty = false;
            }
        } catch (IOException e) {
            throw new FolesiumException("Write failed in " + path, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Like {@link #put} but only writes the value when the key is absent, returning
     * {@code true} if the record was actually appended. Used by merge-mode conversion
     * so chunks already migrated into the store (e.g. by a running server) are not
     * overwritten by the Anvil source.
     *
     * <p>Checks the HashMap for the key (under the read lock) <em>before</em> compressing
     * the value: the common no-op case - the key already exists - then returns without
     * paying for a compression whose result would be discarded. After compressing, the
     * key is checked again under the write lock, because another writer may have appended
     * it between the pre-check and the lock acquisition.</p>
     */
    public boolean putIfAbsent(Bytes key, byte[] rawValue) {
        if (key.length() == 0 || key.length() > MAX_KEY_LEN) {
            throw new IllegalArgumentException("Bad key length " + key.length());
        }
        if (rawValue.length > MAX_VALUE_LEN) {
            throw new IllegalArgumentException("Value too large: " + rawValue.length);
        }
        // Fast path: a no-op putIfAbsent appends nothing, so skip the value compression
        // below when the key already exists (merge-mode conversion re-encounters migrated
        // chunks constantly, making this the common case). The index is consulted under
        // the read lock; the authoritative re-check happens under the write lock after
        // the compression, so a race cannot slip a duplicate past this pre-check.
        lock.readLock().lock();
        try {
            if (index.containsKey(key)) {
                return false;
            }
        } finally {
            lock.readLock().unlock();
        }
        Compression c = config.compression();
        byte[] stored;
        if (config.dictionaryCompression() && keyspaceDict != null && ZstdNative.dictAvailable()) {
            c = Compression.ZSTD_DICT;
            stored = Compressors.compressWithDict(rawValue, keyspaceDict, config.compressionLevel());
        } else {
            // ZSTD_DICT config with the dictionary gate above closed: the per-keyspace
            // dictionary is missing (keyspaceDict == null - the keyspace is not region-keyed,
            // or no conversion ever ran) or the zstd-jni dictionary API is unavailable. The
            // FolesiumConfig invariant guarantees the flag is on whenever the codec is
            // ZSTD_DICT, so this branch means dictionary writes are impossible for this record;
            // degrade to plain ZSTD instead of failing record by record. Existing codec-3
            // records keep decoding against their own dictionary (codec comes from record flags).
            c = c == Compression.ZSTD_DICT ? Compression.ZSTD : c;
            stored = Compressors.compress(c, config.compressionLevel(), rawValue);
        }
        if (stored.length >= rawValue.length && c != Compression.NONE) {
            c = Compression.NONE; // incompressible value: store raw
            stored = rawValue;
        }
        byte flags = c.id;
        byte[] record = encodeRecord(flags, key.array(), rawValue.length, stored);

        lock.writeLock().lock();
        try {
            ensureWritable();
            // Re-check under the write lock: another writer may have appended the key
            // between the read-lock pre-check above and this point.
            if (index.containsKey(key)) {
                return false;
            }
            long off = writePos;
            writeFully(ByteBuffer.wrap(record), off);
            Loc old = index.put(key, new Loc(off, record.length, key.length(), rawValue.length, stored.length, flags));
            if (old != null) {
                deadBytes += old.recordLength;
            }
            writePos = off + record.length;
            updatePageIndex(key, off, false);
            advanceShardWatermark(writePos);
            writeCount.incrementAndGet();
            dirty = true;
            if (config.durability() == FolesiumConfig.DurabilityMode.ALWAYS) {
                channel.force(false);
                dirty = false;
            }
            return true;
        } catch (IOException e) {
            throw new FolesiumException("Write failed in " + path, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(Bytes key) {
        lock.writeLock().lock();
        try {
            ensureWritable();
            Loc old = index.get(key);
            if (old == null) {
                return; // nothing to shadow
            }
            byte[] record = encodeRecord((byte) FLAG_TOMBSTONE, key.array(), 0, new byte[0]);
            long off = writePos;
            writeFully(ByteBuffer.wrap(record), off);
            writePos = off + record.length;
            updatePageIndex(key, off, true);
            advanceShardWatermark(writePos);
            dirty = true;
            if (config.durability() == FolesiumConfig.DurabilityMode.ALWAYS) {
                channel.force(false);
                dirty = false;
            }
            index.remove(key);
            deadBytes += old.recordLength + record.length;
            writeCount.incrementAndGet();
        } catch (IOException e) {
            throw new FolesiumException("Delete failed in " + path, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static byte[] encodeRecord(byte flags, byte[] key, int rawValLen, byte[] stored) {
        int len = RECORD_HEADER_LEN + key.length + stored.length + 4;
        ByteBuffer b = ByteBuffer.allocate(len);
        b.put(RECORD_MAGIC).put(flags).putShort((short) key.length).putInt(rawValLen).putInt(stored.length);
        b.put(key).put(stored);
        CRC32C crc = new CRC32C();
        crc.update(b.array(), 0, b.position());
        b.putInt((int) crc.getValue());
        return b.array();
    }

    // -------------------------------------------------------------- page index

    /**
     * Mirrors a write into the region-page index. Only 8-byte chunk keys have pages;
     * {@code tombstone} clears the slot (offset 0), otherwise the new record offset is
     * stored. No-op when the page index is disabled. Caller holds the write lock.
     */
    /**
     * Page slots hold u32 offsets. An offset at or above 2^32 cannot be represented:
     * writing the truncated int could make a read land on a different record's bytes,
     * so the slot is cleared (absent) instead - reads then fall back to the HashMap
     * (AUTO) or report absent (PAGE), never garbage.
     */
    private static int pageSlotOffset(long off) {
        return off >= (1L << 32) ? 0 : (int) off;
    }

    private void updatePageIndex(Bytes key, long off, boolean tombstone) {
        if (pageIndex == null || key.length() != 8) {
            return;
        }
        int regionX = 0;
        int regionZ = 0;
        int slot = 0;
        try {
            long chunkKey = LongKeys.decode(key.array());
            regionX = RegionPage.regionXFromChunk(chunkKey);
            regionZ = RegionPage.regionZFromChunk(chunkKey);
            slot = RegionPage.slotIndex(LongKeys.chunkX(chunkKey), LongKeys.chunkZ(chunkKey));
            pageIndex.updateSlot(regionX, regionZ, slot, tombstone ? 0 : pageSlotOffset(off));
        } catch (RuntimeException e) {
            // The page index is a disposable cache: a slot update failure (e.g. a corrupt
            // page file) must never fail a write that is already durable in the log and
            // the HashMap index. Repair the damage in place - drop the cached entry and
            // delete the corrupt file (exactly like the open-time build) - and retry the
            // slot update once against the fresh page; if that still fails, log and move
            // on (reads fall back to the HashMap; the damage is retried on the next
            // write). When the file cannot be removed (read-only mode, permissions),
            // rebuildPageFrom reports the page as still damaged and the retry is skipped.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: page index update failed for {0} (key {1}): {2}", path, key, e.toString());
            if (pageIndex.rebuildPageFrom(regionX, regionZ)) {
                try {
                    pageIndex.updateSlot(regionX, regionZ, slot, tombstone ? 0 : pageSlotOffset(off));
                } catch (RuntimeException retryFailure) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Folesium: page index update retry failed for {0} (key {1}): {2}",
                            path, key, retryFailure.toString());
                }
            }
        }
    }

    /**
     * Advances this shard's checkpoint watermark to {@code newWritePos} (the offset just
     * past the appended record) so the next {@code flushWatermarks()} can persist it.
     * Called together with {@link #updatePageIndex} under the write lock. No-op without
     * a page index or shard name.
     */
    private void advanceShardWatermark(long newWritePos) {
        if (pageIndex != null && shardName != null) {
            pageIndex.advanceShardWatermark(shardName, newWritePos);
        }
    }

    /**
     * Whether the region page is this shard's only index for {@code key}: true in PAGE
     * mode for 8-byte chunk keys while the page index exists and is not invalidated
     * (after compaction the pages are dormant, so the HashMap serves reads until the
     * pages are rebuilt on the next open). Non-chunk keys have no page representation
     * and always use the HashMap. A region whose page file was deleted after damage
     * ({@link PageIndex#isRegionDamaged}) also falls back to the HashMap while it is
     * marked: its fresh, empty page would otherwise read every live chunk of the
     * region as absent.
     */
    private boolean pageOnly(Bytes key) {
        if (!pageAuthoritative || pageIndex == null || pageIndex.isInvalidated() || key.length() != 8) {
            return false;
        }
        // A region marked damaged has lost its backing page file (or, read-only, keeps
        // a corrupt one): the next pageFor would build a fresh, empty page that knows
        // nothing about records written before this session, so trusting it would read
        // every other live chunk of the region as absent. The HashMap is always
        // maintained on the write path (index.put runs unconditionally), so while the
        // marker is present the region reads through the HashMap; the marker is cleared
        // only once the open-time rebuild completes without unresolved regions
        // (clearAllDamage), never by a runtime single-slot write.
        long chunkKey = LongKeys.decode(key.array());
        return !pageIndex.isRegionDamaged(RegionPage.regionXFromChunk(chunkKey),
                RegionPage.regionZFromChunk(chunkKey));
    }

    /**
     * Probes the page index for an 8-byte chunk key. Returns the record location the
     * page points at, or {@code null} when the page index is disabled or invalidated, the
     * key is not a chunk key, the slot is empty, the slot offset is outside the current
     * log (page trimming: torn-tail recovery may have truncated the log below a slot
     * written earlier; a garbage slot read from a damaged page is treated the same), or
     * the record header at the slot offset does not validate (stale page / tombstone).
     * AUTO mode callers fall back to the HashMap index on {@code null}; PAGE mode
     * callers treat {@code null} as "key absent". Caller holds the read lock.
     */
    private Loc pageIndexLoc(Bytes key) throws IOException {
        if (pageIndex == null || pageIndex.isInvalidated() || key.length() != 8) {
            return null;
        }
        long chunkKey = LongKeys.decode(key.array());
        int regionX = RegionPage.regionXFromChunk(chunkKey);
        int regionZ = RegionPage.regionZFromChunk(chunkKey);
        int slot = RegionPage.slotIndex(LongKeys.chunkX(chunkKey), LongKeys.chunkZ(chunkKey));
        int off;
        try {
            off = pageIndex.pageFor(regionX, regionZ).get(slot);
        } catch (RuntimeException e) {
            // A damaged or unreadable page file must not silently lose data - in PAGE
            // mode the page is the only index, so a corrupt file would read as "absent"
            // forever. Repair it in place, exactly like the open-time build does: drop the
            // cached entry and delete the corrupt file so the next access rebuilds a fresh
            // page (writes replay the log into it; the next open replays from the
            // compaction watermark), then report a miss. If the file cannot be removed
            // (read-only mode, permissions), the page stays damaged - and the region is
            // marked, so PAGE-mode reads fall back to the HashMap - and the repair is
            // retried on the next access.
            boolean repaired = pageIndex.rebuildPageFrom(regionX, regionZ);
            LOGGER.log(System.Logger.Level.WARNING,
                    "Folesium: damaged page of region ({0}, {1}) in {2} ({3}); repaired={4}",
                    regionX, regionZ, path, e.toString(), repaired);
            return null;
        }
        if (off == 0) {
            return null; // empty slot: no record for this chunk
        }
        // The slot stores the record offset as an unsigned 32-bit value (the write side
        // casts the long log offset to int, preserving the bit pattern), so read it back
        // with u32 semantics: offsets in [2^31, 2^32) come back negative as ints but are
        // valid log positions - the old off < 0 miss branch dropped them silently. The
        // only misses are an empty slot (0) and a slot at or past the current log EOF
        // (page trimming: torn-tail recovery may have truncated the log below a slot
        // written earlier; a garbage slot read from a damaged page is treated the same).
        // A u32 can never reach 2^32, so no slot value is a miss on its face.
        long slotOffset = Integer.toUnsignedLong(off);
        // Trim against the logical EOF: in read-only mode a torn tail is deliberately
        // left on disk (writePos < channel.size()), and a stale slot pointing into it
        // must read as absent rather than as a damaged record. In read-write mode
        // writePos == channel.size(), so the behavior is unchanged.
        if (slotOffset >= writePos) {
            return null;
        }
        // The slot stores only the offset; keyLen/rawValLen/storedValLen/flags live in the
        // 12-byte record header, so read it back to build a Loc for the shared read paths.
        ByteBuffer h = ByteBuffer.allocate(RECORD_HEADER_LEN);
        readFully(h, slotOffset);
        byte magic = h.get();
        byte flags = h.get();
        int keyLen = h.getShort() & 0xFFFF;
        int rawValLen = h.getInt();
        int storedValLen = h.getInt();
        if (magic != RECORD_MAGIC || (flags & FLAG_TOMBSTONE) != 0 || keyLen != 8
                || rawValLen < 0 || rawValLen > MAX_VALUE_LEN
                || storedValLen < 0 || storedValLen > MAX_VALUE_LEN
                // The whole record must fit inside the valid log, the same bound scanRange()
                // enforces: a stale slot pointing at a record that straddles the EOF (e.g. a
                // torn tail left in place by a read-only open) must read as a miss, not as a
                // damaged record.
                || slotOffset + RECORD_HEADER_LEN + keyLen + storedValLen + 4L > writePos) {
            return null;
        }
        // A validated header is not enough: a stale page slot can point at a perfectly legal
        // record that belongs to a *different* key. This happens when a read-only open keeps
        // pre-compaction page files (a .cwmk exists and read-only mode never deletes page
        // files): compaction reassigned every record to a new offset, so a stale slot can
        // land on a valid record of another key in the post-compaction log, and header
        // validation alone would silently serve that record as the queried key's value.
        // Verify the record's key matches the query and treat a mismatch as a miss - AUTO
        // callers fall back to the HashMap, PAGE callers report the key absent. Never return
        // another key's value.
        byte[] recordKey = new byte[keyLen];
        readFully(ByteBuffer.wrap(recordKey), slotOffset + RECORD_HEADER_LEN);
        if (!java.util.Arrays.equals(recordKey, key.array())) {
            return null;
        }
        return new Loc(slotOffset, RECORD_HEADER_LEN + keyLen + storedValLen + 4, keyLen, rawValLen, storedValLen, flags);
    }

    // ------------------------------------------------------------- maintenance

    public void flushIfDirty() {
        if (!dirty) {
            return;
        }
        lock.writeLock().lock();
        try {
            if (dirty) {
                if (channel == null || !channel.isOpen()) {
                    // Channel released (failed compaction restore) or closed concurrently
                    // (e.g. by a racing closeAll()): close() already forced this shard, so
                    // there is nothing left to fsync.
                    return;
                }
                channel.force(false);
                dirty = false;
            }
        } catch (IOException e) {
            throw new FolesiumException("fsync failed for " + path, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean needsCompaction() {
        lock.readLock().lock();
        try {
            long size = writePos;
            return size > config.compactMinBytes() && deadBytes > (long) (config.compactRatio() * size);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Workload-aware compaction priority used to order shards when
     * {@code workloadCompaction} is enabled: dead-space ratio scaled by write
     * activity, {@code deadRatio * (1 + log1p(writeCount))}. A shard that is both
     * mostly dead and frequently written scores highest and is compacted first.
     * An empty shard ({@code sizeBytes() == 0}) scores 0 because there is nothing
     * to reclaim. Purely a scheduling hint - {@link #needsCompaction()} still
     * gates whether a shard is eligible at all.
     */
    public double compactionPriority() {
        lock.readLock().lock();
        try {
            long size = writePos;
            if (size == 0) {
                return 0;
            }
            double deadRatio = (double) deadBytes / size;
            return deadRatio * (1.0 + Math.log1p(writeCount.get()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Rewrites the shard keeping only live records. Tombstones are dropped.
     *
     * <p>The replacement is built beside the live file and only swapped in once it is
     * complete and fsynced, so a crash at any point leaves the original log intact (the
     * leftover {@code .compact} file is ignored by everything and removed on the next
     * successful compaction). The swap is only declared complete after the compacted file
     * has been reopened; if that reopen fails, the pre-compaction data is copied back over
     * the compacted file and the shard keeps serving its old state. A failure before the
     * the swap leaves the original channel untouched, so a shard that could not be compacted
     * stays fully usable.</p>
     *
     * <p>Once the swap is complete, the region pages are invalidated and deleted (see
     * {@link PageIndex#invalidateAll()} and {@link PageIndex#deleteAllPageFiles()}): every
     * entry is stale because the compacted log assigns new offsets to all live records.
     * While the index is invalidated, {@link #pageOnly} reports false, so reads - AUTO and
     * PAGE modes alike - fall back to the HashMap, which the write path maintains
     * unconditionally; PAGE-mode reads never treat keys as absent during the invalidation.
     * The pages stay empty until the next open rebuilds them from the compaction-anchored
     * log replay.</p>
     */
    public void compact() {
        lock.writeLock().lock();
        Path tmp = path.resolveSibling(path.getFileName() + ".compact");
        boolean swapped = false;
        try {
            if (readOnly) {
                // A read-only shard must never be rewritten: no-op with a warning. The
                // compaction scheduler is expected to skip read-only keyspaces, but this
                // guard keeps a misconfiguration from ever touching the file.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: compaction of read-only shard {0} skipped", path);
                return;
            }
            // A shard whose channel was released by a failed compaction restore must fail
            // loudly here instead of a bare NullPointerException deep inside the rewrite
            // (readWholeRecord) or the swap (channel.close()) below.
            ensureWritable();
            Map<Bytes, Loc> newIndex = new HashMap<>(Math.max(16, index.size() * 2));
            long pos = FILE_HEADER_LEN;
            try (FileChannel out = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer hdr = ByteBuffer.allocate(FILE_HEADER_LEN);
                hdr.put(FILE_MAGIC).putShort(FORMAT_VERSION).putShort((short) 0)
                        .putInt(shardIndex).putInt(shardCount);
                hdr.flip();
                while (hdr.hasRemaining()) {
                    out.write(hdr, FILE_HEADER_LEN - hdr.remaining());
                }
                for (Map.Entry<Bytes, Loc> e : index.entrySet()) {
                    Loc loc = e.getValue();
                    byte[] record = readWholeRecord(loc, config.verifyChecksums());
                    ByteBuffer whole = ByteBuffer.wrap(record);
                    long written = 0;
                    while (whole.hasRemaining()) {
                        written += out.write(whole, pos + written);
                    }
                    newIndex.put(e.getKey(), new Loc(pos, loc.recordLength, loc.keyLen, loc.rawValLen, loc.storedValLen, loc.flags));
                    pos += loc.recordLength;
                }
                out.force(false);
            }
            // The stale hint describes the pre-compaction log; drop it first so a crash in the
            // middle of the swap can never pair the new file with the old index.
            Files.deleteIfExists(hintPath);
            // Publish the compaction anchor BEFORE the swap so every crash-after-swap state
            // has a .cwmk on disk. The anchor is written as 0 (the file header), not as the
            // new log's EOF: this compaction invalidates and deletes every region page below
            // (see the pageIndex block after the swap), so the next open must replay the whole
            // compacted log to rebuild them; anchoring at the new EOF would replay nothing and
            // leave every PAGE-mode chunk read missing. The value 0 makes the open-time build
            // replay [header, EOF) regardless of which log won the race:
            //  * crash before this write: old log with the previous anchor (0, or no .cwmk on
            //    a first-ever compaction) - the open replays fully and, absent an anchor, keeps
            //    page files that are valid for the still-live old log;
            //  * crash after this write, before the swap: old log + anchor 0 - the open
            //    discards the stale page files and replays the intact old log from the header;
            //  * crash after the swap: new log + anchor 0 - the open discards the stale page
            //    files and replays the whole compacted log, which holds every live record.
            // A failure here aborts the compaction BEFORE the swap: the old log stays live and
            // the leftover .compact scratch file is removed by the finally block. PageIndex
            // guards setCompactionWatermark on its own readOnly flag, but compact() already
            // returned for read-only shards above, so that guard never swallows this write.
            if (pageIndex != null && shardName != null) {
                try {
                    pageIndex.setCompactionWatermark(shardName, 0);
                } catch (IOException e) {
                    throw new FolesiumException("Compaction of " + path
                            + " aborted: could not write the compaction watermark", e);
                }
            }
            // The old channel stays open across the swap: it still refers to the pre-compaction
            // data and is the fallback if the compacted file cannot be reopened.
            moveReplacing(tmp, path);
            FileChannel reopened;
            try {
                reopened = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            } catch (IOException reopenFailure) {
                // The swap must not be declared complete unless the compacted file is open again.
                restoreOldShardAfterFailedReopen(reopenFailure);
                return;
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: failed to close the pre-compaction channel of {0}: {1}",
                        path, closeFailure.toString());
            }
            channel = reopened;
            index = newIndex;
            writePos = pos;
            deadBytes = 0;
            dirty = false;
            swapped = true;
            if (pageIndex != null) {
                // The compacted log assigns new offsets to every live record, so every page
                // entry is stale. While the index is invalidated pageOnly() reports false,
                // so reads fall back to the HashMap in AUTO and PAGE modes alike - PAGE
                // never treats keys as absent here (the HashMap is always maintained on the
                // write path). The write path leaves the pages alone during the
                // invalidation (updateSlot is a no-op on an invalidated index), so the
                // pages stay empty until the next open rebuilds them from the
                // compaction-anchored log replay (buildPagesFromCompactionAnchor).
                pageIndex.invalidateAll();
                // Delete the stale page files immediately instead of waiting for close():
                // a crash between the swap above and close() used to leave page files
                // holding pre-compaction offsets, which the next open would load as live
                // slots - wrong values for records that moved, or PAGE-mode misses. The
                // anchor was already published to 0 before the swap, so a reopen after a
                // crash replays the whole compacted log and rebuilds every page from
                // scratch; with no page files left there is nothing stale to load.
                // Best-effort: failures are logged inside.
                pageIndex.deleteAllPageFiles();
            }
        } catch (IOException e) {
            FolesiumException failure = new FolesiumException("Compaction failed for " + path, e);
            if (!swapped && !channel.isOpen()) {
                // Nothing was replaced: put the shard back in working order.
                try {
                    channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                } catch (IOException reopen) {
                    failure.addSuppressed(reopen);
                }
            }
            throw failure;
        } finally {
            if (!swapped) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Scratch file only; the next compaction truncates it.
                }
            }
            lock.writeLock().unlock();
        }
    }

    /**
     * Best-effort rollback when the compacted file was moved into place but could not be
     * reopened: copies the pre-compaction data - still reachable through the old channel -
     * back over {@code path} so the shard keeps serving its old state. The field
     * {@code channel} is rebound to the restored file: the swap unlinked the pre-compaction
     * inode the field still referred to, so without a rebind every later write would land on
     * that orphaned inode and be invisible to the next open (silent data loss). Throws when
     * the restore itself fails, in which case the compaction failure propagates with both
     * errors attached and the shard is left unusable for writes: the orphaned channel is
     * closed and the field cleared, so subsequent writes throw an explicit error (see
     * {@link #ensureWritable}) instead of landing in the unlinked file.
     */
    private void restoreOldShardAfterFailedReopen(IOException reopenFailure) throws IOException {
        IOException restoreFailure = null;
        long oldSize = 0;
        try {
            oldSize = channel.size();
            try (FileChannel restore = FileChannel.open(path,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                long written = 0;
                while (written < oldSize) {
                    long n = channel.transferTo(written, oldSize - written, restore);
                    if (n <= 0) {
                        throw new EOFException("Could not copy the pre-compaction data back to " + path);
                    }
                    written += n;
                }
                restore.force(false);
            }
            // The field channel still refers to the pre-compaction inode, which
            // moveReplacing() unlinked when the compacted file was swapped in; the copy
            // above restored the old data into the new inode on `path`. Rebind the field
            // to the restored file so subsequent writes address the file the next open
            // will read. The index map was never swapped and writePos still describes the
            // pre-compaction state; the restored file is exactly that state (length
            // oldSize), so appends continue where the compaction was interrupted.
            FileChannel restored = FileChannel.open(path,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                channel.close();
            } catch (IOException closeFailure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Folesium: failed to close the pre-compaction channel of {0}: {1}",
                        path, closeFailure.toString());
            }
            channel = restored;
            writePos = oldSize;
        } catch (IOException e) {
            restoreFailure = e;
        }
        if (restoreFailure != null) {
            // The restore failed: `path` now holds the compacted file (or a partial copy),
            // while the field channel still refers to the pre-compaction inode that
            // moveReplacing() unlinked. Writing through that channel would append to an
            // orphaned file the next open never sees - silent data loss - so release it
            // and mark the shard unwritable: every later write fails loudly (see
            // ensureWritable) instead of vanishing.
            try {
                channel.close();
            } catch (IOException closeFailure) {
                restoreFailure.addSuppressed(closeFailure);
            }
            channel = null;
            restoreFailure.addSuppressed(reopenFailure);
            throw new FolesiumException("Compaction failed for " + path
                    + ": could not reopen the compacted shard, and restoring the previous shard failed too",
                    restoreFailure);
        }
        LOGGER.log(System.Logger.Level.ERROR,
                "Folesium: compaction of {0} could not reopen the compacted file ({1}); "
                        + "the previous shard was restored and remains in use",
                path, reopenFailure.toString());
    }
    /** Iterates all live entries (key, decompressed value). */
    public void forEach(BiConsumer<byte[], byte[]> consumer) {
        List<IterationEntry> snapshot;
        lock.readLock().lock();
        try {
            snapshot = new ArrayList<>(index.size());
            boolean verifyChecksums = config.verifyChecksums();
            for (Map.Entry<Bytes, Loc> e : index.entrySet()) {
                Loc loc = e.getValue();
                byte[] stored = readStoredValue(loc, verifyChecksums);
                Compression c = Compression.byId((byte) (loc.flags & 0x0F));
                byte[] value = decompressValue(c, stored, loc);
                snapshot.add(new IterationEntry(e.getKey().array(), value));
            }
        } catch (IOException e) {
            throw new FolesiumException("Iteration failed in " + path, e);
        } finally {
            lock.readLock().unlock();
        }
        for (IterationEntry entry : snapshot) {
            consumer.accept(entry.key(), entry.value());
        }
    }

    /**
     * Iterates all live keys without touching the log. Lets callers that only need the key
     * set (grouping, counting, export planning) skip reading and decompressing every value.
     */
    public void forEachKey(java.util.function.Consumer<byte[]> consumer) {
        List<byte[]> snapshot;
        lock.readLock().lock();
        try {
            snapshot = new ArrayList<>(index.size());
            for (Bytes k : index.keySet()) {
                snapshot.add(k.array());
            }
        } finally {
            lock.readLock().unlock();
        }
        for (byte[] key : snapshot) {
            consumer.accept(key);
        }
    }

    public int count() {
        lock.readLock().lock();
        try {
            return index.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long sizeBytes() {
        lock.readLock().lock();
        try {
            return writePos;
        } finally {
            lock.readLock().unlock();
        }
    }

    public long deadBytes() {
        lock.readLock().lock();
        try {
            return deadBytes;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (channel != null && channel.isOpen()) {
                if (!readOnly) {
                    // Read-only mode never fsyncs (nothing was written) and never writes
                    // the hint file (it would describe a log this open may not touch). A
                    // shard whose channel was released by a failed compaction restore
                    // skips both as well: its on-disk state is inconsistent, so no hint
                    // must be written against it (the next open does a full scan).
                    channel.force(false);
                    writeHint();
                }
                channel.close();
            }
        } catch (IOException e) {
            throw new FolesiumException("Close failed for " + path, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Moves {@code source} over {@code target}, preferring an atomic replace but falling back
     * to a plain replace move on filesystems that do not support atomic moves (reported either
     * as {@link AtomicMoveNotSupportedException} or as a generic filesystem error).
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

    private void readFully(ByteBuffer buf, long pos) throws IOException {
        if (channel == null) {
            // Channel released by a failed compaction restore: every read through the
            // orphaned channel would be a bare NullPointerException. Fail loudly with the
            // same message the write path uses (see ensureWritable) so the get / contains /
            // forEach read paths surface a clear error instead of an unexplained NPE.
            throw new FolesiumException("Shard " + path
                    + " is unusable: its file channel was closed after a failed compaction restore");
        }
        long p = pos;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, p);
            if (n < 0) {
                throw new EOFException("EOF at " + p + " in " + path);
            }
            p += n;
        }
        buf.flip();
    }

    private void writeFully(ByteBuffer buf, long pos) throws IOException {
        long p = pos;
        while (buf.hasRemaining()) {
            p += channel.write(buf, p);
        }
    }
}
