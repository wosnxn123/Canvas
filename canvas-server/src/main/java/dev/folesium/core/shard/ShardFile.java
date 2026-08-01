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
import dev.folesium.core.util.Bytes;
import dev.folesium.core.util.Compressors;

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
import java.util.List;
import java.util.Map;
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
 * ({@code *.fidx}) written on clean close allows skipping the scan.</p>
 *
 * <p>Thread model: many concurrent readers OR one writer per shard
 * ({@link ReentrantReadWriteLock}). Different shards are fully independent, so
 * Folia region threads writing to different shards never contend.</p>
 */
public final class ShardFile implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("Folesium");

    static final byte[] FILE_MAGIC = {'F', 'L', 'S', 'M'};
    static final int FILE_HEADER_LEN = 16;
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
    private long writePos;
    private long deadBytes;
    private volatile boolean dirty;

    public ShardFile(Path path, int shardIndex, FolesiumConfig config) {
        this.path = path;
        this.hintPath = path.resolveSibling(path.getFileName() + ".fidx");
        this.shardIndex = shardIndex;
        this.shardCount = config.shardCount();
        this.config = config;
        try {
            boolean fresh = !Files.exists(path) || Files.size(path) == 0;
            this.channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (fresh) {
                writeFileHeader();
                this.writePos = FILE_HEADER_LEN;
            } else {
                try {
                    validateFileHeader();
                } catch (EOFException tornHeader) {
                    // The file is non-empty but too short to hold a header - a crash tore the
                    // shard before a valid header was ever written. There is no valid header to
                    // anchor the record-level scan below, so treat the whole shard as torn:
                    // discard it and start fresh. Any FolesiumException from validateFileHeader()
                    // (bad magic, unsupported version, or a topology mismatch against the count
                    // the store opens with) propagates and fails the open with the file's data
                    // intact - a mismatched shard is valid data, not torn debris. Shards with a
                    // valid header are unaffected - scanAndRecover() keeps handling torn tails.
                    discardTornShard(tornHeader.toString());
                }
                if (!tryLoadHint()) {
                    scanAndRecover();
                }
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

    /** Full sequential scan; truncates at the first torn/corrupt record. */
    private void scanAndRecover() throws IOException {
        long fileSize = channel.size();
        long pos = FILE_HEADER_LEN;
        ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_LEN);
        CRC32C crc = new CRC32C();

        while (pos < fileSize) {
            long recordStart = pos;
            header.clear();
            try {
                readFully(header, pos);
            } catch (EOFException e) {
                truncateAt(recordStart, "torn record header");
                return;
            }
            byte magic = header.get();
            byte flags = header.get();
            int keyLen = header.getShort() & 0xFFFF;
            int rawValLen = header.getInt();
            int storedValLen = header.getInt();

            if (magic != RECORD_MAGIC || keyLen == 0
                    || rawValLen < 0 || rawValLen > MAX_VALUE_LEN
                    || storedValLen < 0 || storedValLen > MAX_VALUE_LEN
                    || recordStart + RECORD_HEADER_LEN + keyLen + storedValLen + 4L > fileSize) {
                truncateAt(recordStart, "invalid record header");
                return;
            }

            int bodyLen = keyLen + storedValLen;
            ByteBuffer body = ByteBuffer.allocate(bodyLen + 4);
            try {
                readFully(body, recordStart + RECORD_HEADER_LEN);
            } catch (EOFException e) {
                truncateAt(recordStart, "torn record body");
                return;
            }

            crc.reset();
            header.rewind();
            crc.update(header);
            crc.update(body.slice(0, bodyLen));
            int expected = body.getInt(bodyLen);
            if ((int) crc.getValue() != expected) {
                truncateAt(recordStart, "CRC mismatch");
                return;
            }

            byte[] key = new byte[keyLen];
            body.position(0);
            body.get(key);
            Bytes k = new Bytes(key);
            int recordLength = RECORD_HEADER_LEN + bodyLen + 4;

            Loc old;
            if ((flags & FLAG_TOMBSTONE) != 0) {
                old = index.remove(k);
                deadBytes += recordLength; // tombstone itself is dead weight
            } else {
                old = index.put(k, new Loc(recordStart, recordLength, keyLen, rawValLen, storedValLen, flags));
            }
            if (old != null) {
                deadBytes += old.recordLength;
            }
            pos = recordStart + recordLength;
        }
        writePos = pos;
    }

    private void truncateAt(long pos, String reason) throws IOException {
        LOGGER.log(System.Logger.Level.WARNING,
                "Folesium recovery: truncating {0} at {1} ({2}, file size {3})",
                path, pos, reason, channel.size());
        channel.truncate(pos);
        channel.force(false);
        writePos = pos;
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
            // Smallest conceivable entry is 2+0+8+4+4+1 = 19 bytes, so a count that cannot fit
            // in the remaining bytes is nonsense - refuse it instead of pre-sizing a huge map.
            if (count < 0 || (long) count * 19L > b.remaining()) {
                return false;
            }
            Map<Bytes, Loc> loaded = new HashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                int keyLen = b.getShort() & 0xFFFF;
                byte[] key = new byte[keyLen];
                b.get(key);
                long off = b.getLong();
                int rawValLen = b.getInt();
                int storedValLen = b.getInt();
                byte flags = b.get();
                loaded.put(new Bytes(key),
                        new Loc(off, RECORD_HEADER_LEN + keyLen + storedValLen + 4, keyLen, rawValLen, storedValLen, flags));
            }
            this.index = loaded;
            this.writePos = logLength;
            this.deadBytes = dead;
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: ignoring bad hint file {0}: {1}", hintPath, e.toString());
            return false;
        }
    }

    private void writeHint() {
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
            Path tmp = hintPath.resolveSibling(hintPath.getFileName() + ".tmp");
            Files.write(tmp, b.array());
            moveReplacing(tmp, hintPath);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Folesium: failed to write hint {0}: {1}", hintPath, e.toString());
        }
    }

    // ------------------------------------------------------------- read/write

    public byte[] get(Bytes key) {
        lock.readLock().lock();
        try {
            Loc loc = index.get(key);
            if (loc == null) {
                return null;
            }
            byte[] stored = readStoredValue(loc, config.verifyChecksums());
            Compression c = Compression.byId((byte) (loc.flags & 0x0F));
            return Compressors.decompress(c, stored, loc.rawValLen);
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
            return index.containsKey(key);
        } finally {
            lock.readLock().unlock();
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
        byte[] stored = Compressors.compress(c, config.compressionLevel(), rawValue);
        if (stored.length >= rawValue.length && c != Compression.NONE) {
            c = Compression.NONE; // incompressible value: store raw
            stored = rawValue;
        }
        byte flags = c.id;
        byte[] record = encodeRecord(flags, key.array(), rawValue.length, stored);

        lock.writeLock().lock();
        try {
            long off = writePos;
            writeFully(ByteBuffer.wrap(record), off);
            Loc old = index.put(key, new Loc(off, record.length, key.length(), rawValue.length, stored.length, flags));
            if (old != null) {
                deadBytes += old.recordLength;
            }
            writePos = off + record.length;
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
     */
    public boolean putIfAbsent(Bytes key, byte[] rawValue) {
        if (key.length() == 0 || key.length() > MAX_KEY_LEN) {
            throw new IllegalArgumentException("Bad key length " + key.length());
        }
        if (rawValue.length > MAX_VALUE_LEN) {
            throw new IllegalArgumentException("Value too large: " + rawValue.length);
        }
        Compression c = config.compression();
        byte[] stored = Compressors.compress(c, config.compressionLevel(), rawValue);
        if (stored.length >= rawValue.length && c != Compression.NONE) {
            c = Compression.NONE; // incompressible value: store raw
            stored = rawValue;
        }
        byte flags = c.id;
        byte[] record = encodeRecord(flags, key.array(), rawValue.length, stored);

        lock.writeLock().lock();
        try {
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
            Loc old = index.get(key);
            if (old == null) {
                return; // nothing to shadow
            }
            byte[] record = encodeRecord((byte) FLAG_TOMBSTONE, key.array(), 0, new byte[0]);
            long off = writePos;
            writeFully(ByteBuffer.wrap(record), off);
            writePos = off + record.length;
            dirty = true;
            if (config.durability() == FolesiumConfig.DurabilityMode.ALWAYS) {
                channel.force(false);
                dirty = false;
            }
            index.remove(key);
            deadBytes += old.recordLength + record.length;
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

    // ------------------------------------------------------------- maintenance

    public void flushIfDirty() {
        if (!dirty) {
            return;
        }
        lock.writeLock().lock();
        try {
            if (dirty) {
                if (!channel.isOpen()) {
                    // Closed concurrently (e.g. by a racing closeAll()): close() already
                    // forced this shard, so there is nothing left to fsync.
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
     * Rewrites the shard keeping only live records. Tombstones are dropped.
     *
     * <p>The replacement is built beside the live file and only swapped in once it is
     * complete and fsynced, so a crash at any point leaves the original log intact (the
     * leftover {@code .compact} file is ignored by everything and removed on the next
     * successful compaction). The swap is only declared complete after the compacted file
     * has been reopened; if that reopen fails, the pre-compaction data is copied back over
     * the compacted file and the shard keeps serving its old state. A failure before the
     * swap leaves the original channel untouched, so a shard that could not be compacted
     * stays fully usable.</p>
     */
    public void compact() {
        lock.writeLock().lock();
        Path tmp = path.resolveSibling(path.getFileName() + ".compact");
        boolean swapped = false;
        try {
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
     * back over {@code path} so the shard keeps serving its old state with the old channel.
     * Throws when the restore itself fails, in which case the compaction failure propagates
     * with both errors attached.
     */
    private void restoreOldShardAfterFailedReopen(IOException reopenFailure) throws IOException {
        IOException restoreFailure = null;
        try {
            long oldSize = channel.size();
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
        } catch (IOException e) {
            restoreFailure = e;
        }
        if (restoreFailure != null) {
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
                byte[] value = Compressors.decompress(c, stored, loc.rawValLen);
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
            if (channel.isOpen()) {
                channel.force(false);
                writeHint();
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
