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
import dev.folesium.core.util.LongKeys;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * A fixed-size index page for a single region (32×32 slots) of a region-keyed keyspace
 * ({@code chunks}, {@code entities}, {@code poi}). A pure in-memory data object: it is
 * not thread-safe and does no locking of its own.
 *
 * <p>On-disk format, 4128 bytes total, all integers big-endian:</p>
 * <pre>
 * page header (16 bytes): magic u16 0x4650 ("FP") | version u8 1 | regionX i32 | regionZ i32
 *                         | flags u8 0 | watermark u32
 * 1024 × u32 slots @16    absolute log offsets; 0 = the slot has no record
 * tail (16 bytes):        CRC32C u32 (over header + slots) | slot count u32 | reserved 8 bytes
 * </pre>
 *
 * <p>A slot value is the absolute byte offset of the owning record in the shard log; since
 * the shard log starts at offset 16, {@code 0} unambiguously marks an absent slot (never
 * written or tombstoned). The watermark is a replay-skip hint (u32); Phase 1 keeps it at 0.</p>
 */
public final class RegionPage {
    public static final int PAGE_SIZE = 4128;
    public static final int SLOT_COUNT = 1024;

    // Header layout (offsets): magic u16 @0 | version u8 @2 | regionX i32 @3 | regionZ i32 @7
    // | flags u8 @11 | watermark u32 @12 | 1024×u32 slots @16 | CRC32C u32 @4112 | count u32 @4116 | reserved 8B @4120
    public static final int HEADER_LEN = 16;
    public static final int SLOTS_OFF = 16;
    public static final int TAIL_CRC_OFF = 4112;
    public static final int TAIL_COUNT_OFF = 4116;

    public static final int MAGIC = 0x4650; // "FP"
    private static final int FORMAT_VERSION = 1;

    private final int regionX;
    private final int regionZ;
    private final int[] slots;
    private int watermark;

    private RegionPage(int regionX, int regionZ, int[] slots, int watermark) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.slots = slots;
        this.watermark = watermark;
    }

    /** Floor semantics: {@code chunkX >> 5} (correct for negative chunk coordinates). */
    public static int regionXFromChunk(long chunkKey) {
        return LongKeys.chunkX(chunkKey) >> 5;
    }

    /** Floor semantics: {@code chunkZ >> 5} (correct for negative chunk coordinates). */
    public static int regionZFromChunk(long chunkKey) {
        return LongKeys.chunkZ(chunkKey) >> 5;
    }

    /** Slot = {@code (chunkX & 31) | ((chunkZ & 31) << 5)}; result is in 0..1023. */
    public static int slotIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) | ((chunkZ & 31) << 5);
    }

    /** Creates a new empty page in memory (not yet written to disk). */
    public static RegionPage create(int regionX, int regionZ) {
        return new RegionPage(regionX, regionZ, new int[SLOT_COUNT], 0);
    }

    /**
     * Reads a page from disk and validates it (size, magic, version, CRC, slot count). A
     * damaged or inconsistent page throws {@link FolesiumException} naming the file and
     * the region coordinates parsed from its header.
     *
     * @throws IOException if the file cannot be read
     */
    public static RegionPage read(Path file) throws IOException {
        // Pre-check the exact size before loading the bytes: a stray or truncated page file
        // is damaged, and a huge stray file would otherwise be read into memory whole before
        // the length check could reject it - an OOM for what is a corrupted page anyway.
        // Wrong-size files take the damaged-page path (callers repair in place / rebuild
        // from the log), exactly as before.
        long size = Files.size(file);
        if (size != PAGE_SIZE) {
            throw new FolesiumException("Bad region page size for " + file + ": " + size
                    + " bytes, expected " + PAGE_SIZE + " (region " + regionFromBytes(readPageHeader(file, size)) + ")");
        }
        // Second size check immediately before the read: narrows the TOCTOU window in which a
        // concurrently growing file could slip past the pre-check and be read (and allocated)
        // whole. The post-read length check below still guards the remaining gap.
        long size2 = Files.size(file);
        if (size2 != PAGE_SIZE) {
            throw new FolesiumException("Bad region page size for " + file + ": " + size2
                    + " bytes, expected " + PAGE_SIZE + " (region " + regionFromBytes(readPageHeader(file, size2)) + ")");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != PAGE_SIZE) {
            // TOCTOU: the file changed between the size pre-check and the read - treat it
            // as damaged exactly like any other wrong-size page.
            throw new FolesiumException("Bad region page size for " + file + ": " + bytes.length
                    + " bytes, expected " + PAGE_SIZE + " (region " + regionFromBytes(bytes) + ")");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int magic = b.getShort() & 0xFFFF;
        int version = b.get() & 0xFF;
        int regionX = b.getInt();
        int regionZ = b.getInt();
        int flags = b.get() & 0xFF;
        int watermark = b.getInt();
        if (magic != MAGIC) {
            throw new FolesiumException("Bad region page magic in " + file + " (region (" + regionX + ", " + regionZ + "))");
        }
        if (version != FORMAT_VERSION) {
            throw new FolesiumException("Unsupported region page version " + version + " in " + file
                    + " (region (" + regionX + ", " + regionZ + "))");
        }
        if (flags != 0) {
            throw new FolesiumException("Bad region page flags " + flags + " in " + file
                    + " (region (" + regionX + ", " + regionZ + "))");
        }
        int[] slots = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = b.getInt();
        }
        int storedCrc = b.getInt();
        int storedCount = b.getInt();
        long reservedTail = b.getLong();
        if (reservedTail != 0L) {
            throw new FolesiumException("Reserved tail bytes of region page " + file + " are not zero"
                    + " (region (" + regionX + ", " + regionZ + "))");
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, TAIL_CRC_OFF);
        if ((int) crc.getValue() != storedCrc) {
            throw new FolesiumException("CRC mismatch reading region page " + file
                    + " (region (" + regionX + ", " + regionZ + "))");
        }
        RegionPage page = new RegionPage(regionX, regionZ, slots, watermark);
        if (page.count() != storedCount) {
            throw new FolesiumException("Region page slot count mismatch in " + file
                    + " (region (" + regionX + ", " + regionZ + "))");
        }
        return page;
    }

    /**
     * Atomically writes this page: serializes to a unique {@code .tmp-<uuid>} sibling,
     * forces it to disk (including metadata), and moves it over the target, preferring
     * {@link StandardCopyOption#ATOMIC_MOVE} and falling back to a plain replace move. The
     * tail carries the CRC (over header + slots) and the live slot count; the 8 reserved
     * tail bytes stay zero. The unique temporary name makes concurrent writers to the same
     * file safe: no two calls share a staging path. After the rename the parent directory
     * is fsynced (best-effort, mirroring {@code DictionaryStore.train}) so the new page
     * file name survives a crash.
     */
    public void write(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(PAGE_SIZE);
        b.putShort((short) MAGIC).put((byte) FORMAT_VERSION)
                .putInt(regionX).putInt(regionZ).put((byte) 0).putInt(watermark);
        for (int slot : slots) {
            b.putInt(slot);
        }
        CRC32C crc = new CRC32C();
        crc.update(b.array(), 0, TAIL_CRC_OFF);
        b.putInt((int) crc.getValue());
        b.putInt(count());
        // The 8 reserved tail bytes: the buffer is written position-limited (flip below),
        // so they must be explicitly zeroed or the file comes out 8 bytes short.
        b.putLong(0L);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        Throwable primary = null;
        try {
            b.flip();
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (b.hasRemaining()) {
                    channel.write(b);
                }
                channel.force(true);
            }
            moveReplacing(tmp, file);
            // The rename is the commit: fsync the parent directory so the new page file
            // name survives a crash (mirrors DictionaryStore.train; silently skipped on
            // filesystems without directory fsync, e.g. some Windows filesystems).
            fsyncDirectory(parent);
        } catch (IOException | RuntimeException | Error e) {
            // Any other failure propagating out of the write is remembered so the cleanup
            // below attaches to it instead of masking it.
            primary = e;
            throw e;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e2) {
                // A failed cleanup must not mask the exception the try block is already
                // propagating: attach the delete failure to it as suppressed. When the try
                // block itself succeeded (the file was moved away), the delete failure is
                // the only error to report.
                if (primary != null) {
                    primary.addSuppressed(e2);
                } else {
                    throw e2;
                }
            }
        }
    }

    /** Absolute log offset of the given slot; 0 = the slot has no record. */
    public int get(int slot) {
        checkSlot(slot);
        return slots[slot];
    }

    /** Sets the absolute log offset of a slot; 0 marks the slot as absent. */
    public void set(int slot, int offset) {
        checkSlot(slot);
        slots[slot] = offset;
    }

    /**
     * The replay-skip hint (u32) carried in the page header.
     *
     * @deprecated Deprecated placeholder: the field is kept only for on-disk format
     *             compatibility and is always {@code 0} in Phase 1. It has no effect on
     *             reads or writes; do not rely on it.
     */
    public int watermark() {
        return watermark;
    }

    /**
     * @deprecated Deprecated placeholder, see {@link #watermark()}. The value is
     *             preserved through write/read for format compatibility but is never
     *             consulted by the engine.
     */
    public void setWatermark(int watermark) {
        this.watermark = watermark;
    }

    /** Number of nonzero (live) slots. */
    public int count() {
        int n = 0;
        for (int slot : slots) {
            if (slot != 0) {
                n++;
            }
        }
        return n;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("Slot out of range [0, " + SLOT_COUNT + "): " + slot);
        }
    }

    private static String regionFromBytes(byte[] bytes) {
        if (bytes.length >= 11) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            b.getShort();
            b.get();
            return "(" + b.getInt() + ", " + b.getInt() + ")";
        }
        return "(unknown)";
    }

    /**
     * Best-effort read of the 11 header bytes of a page file, enough for the region
     * coordinates in a damaged-page error message. Never fails the caller: an unreadable
     * header just yields "(unknown)".
     */
    private static byte[] readPageHeader(Path file, long size) {
        byte[] header = new byte[(int) Math.min(11, size)];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer bb = ByteBuffer.wrap(header);
            while (bb.hasRemaining()) {
                if (ch.read(bb) < 0) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Best-effort only: the error message may omit the region coordinates.
        }
        return header;
    }

    /**
     * Best-effort directory fsync so a completed rename survives a crash. Mirrors the
     * directory-fsync pattern of {@code DictionaryStore.train} (opening the directory with
     * {@code READ} and forcing flushes the rename on filesystems that support directory
     * fsync); filesystems that reject it (some Windows filesystems) are skipped silently.
     */
    private static void fsyncDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is unavailable on some Windows filesystems.
        }
    }

    /**
     * Moves {@code source} over {@code target}, preferring an atomic replace but falling
     * back to a plain replace move on filesystems that do not support atomic moves
     * (reported as {@link AtomicMoveNotSupportedException}, or as a generic filesystem
     * error while the target already exists). Any other filesystem error is rethrown
     * unchanged - it is not about atomic-replace support and must not be masked by a
     * second move attempt; a fallback that itself fails keeps the original error
     * attached as suppressed so its diagnostics survive.
     */
    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException e) {
            // Some platforms report missing atomic-replace support as a generic filesystem error
            // only when the target already exists; keep that fallback, but rethrow anything else
            // (permission denied, vanished source, ...) as the original exception. If the fallback
            // itself fails, attach the original error as suppressed so its diagnostics are kept
            // (mirrors DictionaryStore.moveIntoPlace).
            if (Files.exists(target)) {
                try {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    e2.addSuppressed(e);
                    throw e2;
                }
            } else {
                throw e;
            }
        }
    }
}
