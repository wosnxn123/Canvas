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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
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
                validateFileHeader();
                if (!tryLoadHint()) {
                    scanAndRecover();
                }
            }
        } catch (IOException e) {
            throw new FolesiumException("Failed to open shard " + path, e);
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
        if (channel.read(b, 0) != FILE_HEADER_LEN) {
            throw new FolesiumException("Shard header truncated: " + path);
        }
        b.flip();
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

    /** Full sequential scan; truncates at the first torn/corrupt record. */
    private void scanAndRecover() throws IOException {
        long fileSize = channel.size();
        long pos = FILE_HEADER_LEN;
        ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_LEN);
        CRC32C crc = new CRC32C();

        while (pos < fileSize) {
            long recordStart = pos;
            header.clear();
            if (channel.read(header, pos) != RECORD_HEADER_LEN) {
                truncateAt(recordStart, "torn record header");
                return;
            }
            header.flip();
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
            if (channel.read(body, recordStart + RECORD_HEADER_LEN) != bodyLen + 4) {
                truncateAt(recordStart, "torn record body");
                return;
            }
            body.flip();

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
            Map<Bytes, Loc> loaded = new HashMap<>(count * 2);
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
            Files.move(tmp, hintPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
            byte[] stored;
            if (config.verifyChecksums()) {
                ByteBuffer whole = ByteBuffer.allocate(loc.recordLength);
                readFully(whole, loc.recordOffset);
                CRC32C crc = new CRC32C();
                crc.update(whole.array(), 0, loc.recordLength - 4);
                int expected = ByteBuffer.wrap(whole.array(), loc.recordLength - 4, 4).getInt();
                if ((int) crc.getValue() != expected) {
                    throw new FolesiumException("CRC mismatch reading " + path + " @" + loc.recordOffset);
                }
                stored = java.util.Arrays.copyOfRange(whole.array(),
                        RECORD_HEADER_LEN + loc.keyLen, RECORD_HEADER_LEN + loc.keyLen + loc.storedValLen);
            } else {
                ByteBuffer vb = ByteBuffer.allocate(loc.storedValLen);
                readFully(vb, loc.valueOffset());
                stored = vb.array();
            }
            Compression c = Compression.byId((byte) (loc.flags & 0x0F));
            return Compressors.decompress(c, stored, loc.rawValLen);
        } catch (IOException e) {
            throw new FolesiumException("Read failed in " + path, e);
        } finally {
            lock.readLock().unlock();
        }
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
            Loc old = index.remove(key);
            if (old == null) {
                return; // nothing to shadow
            }
            byte[] record = encodeRecord((byte) FLAG_TOMBSTONE, key.array(), 0, new byte[0]);
            long off = writePos;
            writeFully(ByteBuffer.wrap(record), off);
            writePos = off + record.length;
            deadBytes += old.recordLength + record.length;
            dirty = true;
            if (config.durability() == FolesiumConfig.DurabilityMode.ALWAYS) {
                channel.force(false);
                dirty = false;
            }
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

    /** Rewrites the shard keeping only live records. Tombstones are dropped. */
    public void compact() {
        lock.writeLock().lock();
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".compact");
            Map<Bytes, Loc> newIndex = new HashMap<>(index.size() * 2);
            try (FileChannel out = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer hdr = ByteBuffer.allocate(FILE_HEADER_LEN);
                hdr.put(FILE_MAGIC).putShort(FORMAT_VERSION).putShort((short) 0)
                        .putInt(shardIndex).putInt(shardCount);
                hdr.flip();
                out.write(hdr, 0);
                long pos = FILE_HEADER_LEN;
                for (Map.Entry<Bytes, Loc> e : index.entrySet()) {
                    Loc loc = e.getValue();
                    ByteBuffer whole = ByteBuffer.allocate(loc.recordLength);
                    readFully(whole, loc.recordOffset); // readFully flips: buffer ready to write out
                    long written = 0;
                    while (whole.hasRemaining()) {
                        written += out.write(whole, pos + written);
                    }
                    newIndex.put(e.getKey(), new Loc(pos, loc.recordLength, loc.keyLen, loc.rawValLen, loc.storedValLen, loc.flags));
                    pos += loc.recordLength;
                }
                out.force(false);
                channel.close();
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                index = newIndex;
                writePos = pos;
                deadBytes = 0;
                dirty = false;
                Files.deleteIfExists(hintPath);
            }
        } catch (IOException e) {
            throw new FolesiumException("Compaction failed for " + path, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Iterates all live entries (key, decompressed value). */
    public void forEach(BiConsumer<byte[], byte[]> consumer) {
        lock.readLock().lock();
        try {
            for (Map.Entry<Bytes, Loc> e : index.entrySet()) {
                Loc loc = e.getValue();
                ByteBuffer vb = ByteBuffer.allocate(loc.storedValLen);
                readFully(vb, loc.valueOffset());
                Compression c = Compression.byId((byte) (loc.flags & 0x0F));
                consumer.accept(e.getKey().array(), Compressors.decompress(c, vb.array(), loc.rawValLen));
            }
        } catch (IOException e) {
            throw new FolesiumException("Iteration failed in " + path, e);
        } finally {
            lock.readLock().unlock();
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
