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

package dev.folesium.anvil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minimal, self-contained implementation of the Anvil region file format
 * (r.X.Z.mca), written from the publicly documented format:
 *
 * <pre>
 * sector 0   : 1024 x u32 location entries (u24 sector offset | u8 sector count)
 * sector 1   : 1024 x u32 modification timestamps (epoch seconds)
 * sector 2.. : chunk payloads, each aligned to 4096-byte sectors:
 *              u32 length (compressionType + data), u8 compressionType, data
 *              compressionType: 1 = gzip, 2 = zlib, 3 = uncompressed
 * </pre>
 *
 * <p>This class is intentionally NOT derived from Mojang, Paper or
 * cesium-fabric code; it exists so the converter and benchmarks have no
 * dependency on server internals.</p>
 *
 * <p>Thread model: one instance is confined to one thread OR guarded
 * externally; this mirrors vanilla, where a RegionFile is accessed under the
 * storage lock.</p>
 */
public final class AnvilRegionFile implements Closeable {
    public static final int SECTOR_BYTES = 4096;
    public static final int CHUNKS_PER_REGION = 1024;

    public static final byte COMPRESSION_GZIP = 1;
    public static final byte COMPRESSION_ZLIB = 2;
    public static final byte COMPRESSION_NONE = 3;
    /** Folia's {@code region-file-compression=lz4} option (region compression type 4). */
    public static final byte COMPRESSION_LZ4 = 4;

    /**
     * Set on the compression byte when the payload does not live in the region file but in a
     * sibling {@code c.<chunkX>.<chunkZ>.mcc} file. Vanilla writes these for chunks larger than
     * 255 sectors (~1 MiB); a converter that ignored the flag would silently skip them.
     */
    private static final int EXTERNAL_FLAG = 0x80;

    /**
     * Upper bound for a decompressed chunk payload. Vanilla caps a single chunk at 1 MiB
     * and routes anything larger to an external {@code .mcc} file, so 32 MiB is far above
     * any legitimate payload while still stopping decompression bombs (a small stub can
     * expand to gigabytes).
     */
    public static final int MAX_CHUNK_PAYLOAD_BYTES = 32 * 1024 * 1024;

    /** {@code r.<regionX>.<regionZ>.mca}, the only naming Anvil uses. */
    private static final java.util.regex.Pattern REGION_NAME =
            java.util.regex.Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mc[ar]$");

    private final Path path;
    private final FileChannel channel;
    private final boolean readOnly;
    private final int[] locations = new int[CHUNKS_PER_REGION];
    private final int[] timestamps = new int[CHUNKS_PER_REGION];
    private final BitSet usedSectors = new BitSet();

    /** Opens {@code path} read-write, creating and initializing it like vanilla. */
    public AnvilRegionFile(Path path) throws IOException {
        this(path, false);
    }

    /**
     * Opens {@code path} read-only: the channel is opened with {@code READ} only, so a
     * missing file fails instead of being created, and a file shorter than the two
     * header sectors is rejected instead of being initialized as an empty region (the
     * writable constructor pads such a file, which a read-only open must never do).
     * Chunk reads behave exactly as on a writable region file; {@link #writeChunk},
     * {@link #deleteChunk} and {@link #sync} fail with a
     * {@link java.nio.channels.NonWritableChannelException} on the read-only channel.
     *
     * <p>Intended for source worlds during conversion, where a region file must already
     * exist with real content and must never be touched.</p>
     */
    public static AnvilRegionFile openReadOnly(Path path) throws IOException {
        return new AnvilRegionFile(path, true);
    }

    private AnvilRegionFile(Path path, boolean readOnly) throws IOException {
        this.path = path;
        this.readOnly = readOnly;
        FileChannel opened = null;
        try {
            opened = FileChannel.open(path, readOnly
                    ? new StandardOpenOption[]{StandardOpenOption.READ}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.READ,
                            StandardOpenOption.WRITE});
            BitSet loadedSectors = new BitSet();
            loadedSectors.set(0, 2); // header sectors
            long size = opened.size();
            if (size < 2L * SECTOR_BYTES) {
                if (readOnly) {
                    throw new IOException("Region file " + path + " is too short to hold an Anvil "
                            + "header (" + size + " bytes)");
                }
                // A partial or fresh file is initialized as a complete empty header.
                writeFully(opened, ByteBuffer.allocate(2 * SECTOR_BYTES), 0);
            } else {
                ByteBuffer header = ByteBuffer.allocate(2 * SECTOR_BYTES);
                readFully(opened, header, 0);
                long availableSectors = (size + SECTOR_BYTES - 1L) / SECTOR_BYTES;
                int[] loadedLocations = new int[CHUNKS_PER_REGION];
                int[] loadedTimestamps = new int[CHUNKS_PER_REGION];
                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    int loc = header.getInt(i * 4);
                    if (loc != 0) {
                        int off = loc >>> 8;
                        int count = loc & 0xFF;
                        int firstUsed = loadedSectors.nextSetBit(off);
                        if (count == 0 || off < 2
                                || (long) off + count > availableSectors
                                || (firstUsed >= 0 && firstUsed < off + count)) {
                            throw new IOException("Invalid chunk location 0x"
                                    + Integer.toHexString(loc) + " at header index " + i);
                        }
                        loadedSectors.set(off, off + count);
                    }
                    loadedLocations[i] = loc;
                    loadedTimestamps[i] = header.getInt(SECTOR_BYTES + i * 4);
                }
                System.arraycopy(loadedLocations, 0, locations, 0, CHUNKS_PER_REGION);
                System.arraycopy(loadedTimestamps, 0, timestamps, 0, CHUNKS_PER_REGION);
            }
            usedSectors.or(loadedSectors);
            this.channel = opened;
        } catch (IOException | RuntimeException failure) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private static int indexOf(int localX, int localZ) {
        return (localX & 31) | ((localZ & 31) << 5);
    }

    public boolean hasChunk(int localX, int localZ) {
        return locations[indexOf(localX, localZ)] != 0;
    }

    /** Returns local (x,z) pairs of all present chunks. */
    public List<int[]> listChunks() {
        List<int[]> out = new ArrayList<>();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                if (hasChunk(x, z)) {
                    out.add(new int[]{x, z});
                }
            }
        }
        return out;
    }

    /**
     * Removes a chunk from the region: its header slot (location and timestamp) is zeroed
     * and its payload sectors are released, so the chunk no longer exists in the region
     * file. The zeroed header entry plus the force is the commit point; only afterwards is
     * an external ({@code .mcc}) payload of an oversized chunk deleted, best-effort, so a
     * leftover is at most a harmless orphan the region no longer references. Deleting an
     * already-empty slot is a no-op.
     */
    public void deleteChunk(int localX, int localZ) throws IOException {
        int idx = indexOf(localX, localZ);
        int oldLoc = locations[idx];
        if (oldLoc == 0) {
            if (readOnly) {
                throw new NonWritableChannelException();
            }
            return;
        }
        int oldSectorOff = oldLoc >>> 8;
        int oldSectorCount = oldLoc & 0xFF;
        // Detect the external stub (length 1 + EXTERNAL_FLAG) before the commit point:
        // it reads the payload bytes the region file still references, and a stale or
        // foreign .mcc file must not be deleted on its own.
        boolean external = false;
        Path externalPath = externalChunkPathOrNull(localX, localZ);
        if (externalPath != null) {
            ByteBuffer stub = ByteBuffer.allocate(5);
            if (readUpTo(stub, (long) oldSectorOff * SECTOR_BYTES) == 5
                    && stub.getInt(0) == 1
                    && (stub.get(4) & 0xFF & EXTERNAL_FLAG) != 0) {
                external = true;
            }
        }
        // Commit point: the chunk is gone from the region once the zeroed header entry
        // is forced.
        writeHeaderEntry(0, 0, idx);
        channel.force(false);
        locations[idx] = 0;
        timestamps[idx] = 0;
        usedSectors.clear(oldSectorOff, oldSectorOff + oldSectorCount);
        if (external) {
            try {
                java.nio.file.Files.deleteIfExists(externalPath);
            } catch (IOException ignored) {
                // Best-effort orphan cleanup after the commit; a leftover .mcc file is
                // harmless because the region file no longer references it.
            }
        }
    }

    /** Reads and decompresses a chunk payload; null if absent. */
    public byte[] readChunk(int localX, int localZ) throws IOException {
        int loc = locations[indexOf(localX, localZ)];
        if (loc == 0) {
            return null;
        }
        int sectorOff = loc >>> 8;
        int sectorCount = loc & 0xFF;
        ByteBuffer buf = ByteBuffer.allocate(sectorCount * SECTOR_BYTES);
        // The last chunk in a file may not be padded to a full 4096-byte sector
        // (both vanilla and Paper tolerate this), so allow a short read here and
        // validate against the actual number of bytes available instead.
        int read = readUpTo(buf, (long) sectorOff * SECTOR_BYTES);
        if (read < 5) {
            throw new IOException("Truncated chunk payload at sector " + sectorOff + " (" + read + " bytes)");
        }
        int length = buf.getInt(0);
        if (length <= 0 || length > read - 4) {
            throw new IOException("Corrupt chunk payload length " + length + " at sector " + sectorOff);
        }
        int rawType = buf.get(4) & 0xFF;
        boolean external = (rawType & EXTERNAL_FLAG) != 0;
        byte compressionType = (byte) (rawType & ~EXTERNAL_FLAG);
        validateCompressionType(compressionType);
        byte[] data;
        if (external) {
            if (length != 1) {
                throw new IOException("External chunk stub must have length 1, got " + length);
            }
            // Oversized chunk: the region file holds only the header, the payload is in
            // c.<chunkX>.<chunkZ>.mcc next to it.  Do not accept a directory, stale link,
            // or an unknown compression type as an external payload.
            Path externalPath = externalChunkPath(localX, localZ);
            if (!java.nio.file.Files.isRegularFile(externalPath)) {
                throw new IOException("External chunk payload is not a regular file: " + externalPath);
            }
            // Bound the .mcc file while materializing it: it holds the *compressed*
            // payload, which may legitimately grow slightly beyond MAX_CHUNK_PAYLOAD_BYTES
            // (deflate inflates incompressible input by a small margin and the write side
            // admits any uncompressed payload up to that bound), so the payload bound plus a
            // 1 MiB safety margin is far above any legal compressed size - anything larger
            // is a corrupt or foreign file that must not be read into memory wholesale. The
            // bound is enforced on the bytes actually read (not by a size check that could
            // race a concurrent grow) and sits before the compression-type branch, so it
            // bounds every payload type, COMPRESSION_NONE included (whose raw payload is
            // then additionally held to the exact bound by the length check below); the
            // exact per-payload limit for the compressed types is still enforced by the
            // bounded decompression below.
            long externalLimit = MAX_CHUNK_PAYLOAD_BYTES + 1024L * 1024L;
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            try (FileChannel externalChannel = FileChannel.open(externalPath, StandardOpenOption.READ)) {
                // Read up to the limit in bounded chunks, then probe one further byte so
                // an over-limit file is rejected by its cumulative count (limit + 1)
                // instead of by a size() check that can race a concurrent grow (TOCTOU).
                ByteBuffer tmp = ByteBuffer.allocate(8192);
                long total = 0;
                while (total < externalLimit) {
                    tmp.clear();
                    tmp.limit((int) Math.min(tmp.capacity(), externalLimit - total));
                    int n = externalChannel.read(tmp);
                    if (n < 0) {
                        break;
                    }
                    total += n;
                    collected.write(tmp.array(), 0, n);
                }
                if (total == externalLimit) {
                    tmp.clear();
                    tmp.limit(1);
                    if (externalChannel.read(tmp) > 0) {
                        total++;
                    }
                }
                if (total > externalLimit) {
                    throw new IOException("External chunk payload file of " + total
                            + " bytes exceeds the " + externalLimit + " byte limit: " + externalPath);
                }
            }
            data = collected.toByteArray();
        } else {
            data = new byte[length - 1];
            buf.position(5);
            buf.get(data);
        }
        byte[] payload = switch (compressionType) {
            case COMPRESSION_GZIP -> readBounded(new GZIPInputStream(new ByteArrayInputStream(data)),
                    MAX_CHUNK_PAYLOAD_BYTES);
            case COMPRESSION_ZLIB -> readBounded(new InflaterInputStream(new ByteArrayInputStream(data)),
                    MAX_CHUNK_PAYLOAD_BYTES);
            case COMPRESSION_NONE -> data;
            case COMPRESSION_LZ4 -> decompressLz4Bounded(data);
            default -> throw new IOException("Unknown chunk compression type " + compressionType);
        };
        if (payload.length > MAX_CHUNK_PAYLOAD_BYTES) {
            throw new IOException("Decompressed chunk payload of " + payload.length
                    + " bytes exceeds the " + MAX_CHUNK_PAYLOAD_BYTES + " byte limit");
        }
        return payload;
    }

    /** Location of the external payload of an oversized chunk. */
    private Path externalChunkPath(int localX, int localZ) throws IOException {
        Path p = externalChunkPathOrNull(localX, localZ);
        if (p == null) {
            throw new IOException("Chunk in " + path + " is stored externally, but the region file name"
                    + " does not encode its coordinates, so the .mcc file cannot be located");
        }
        return p;
    }

    /** Same, but {@code null} when the file is not named {@code r.<x>.<z>.mca}. */
    private Path externalChunkPathOrNull(int localX, int localZ) {
        java.util.regex.Matcher m = REGION_NAME.matcher(path.getFileName().toString());
        if (!m.matches()) {
            return null;
        }
        long chunkX;
        long chunkZ;
        try {
            chunkX = Math.multiplyExact(Long.parseLong(m.group(1)), 32) + (localX & 31);
            chunkZ = Math.multiplyExact(Long.parseLong(m.group(2)), 32) + (localZ & 31);
        } catch (NumberFormatException | ArithmeticException e) {
            // A name like r.9223372036854775808.0.mca (or a region coordinate whose x32
            // expansion overflows long) cannot encode valid coordinates; treat it the
            // same as an unparseable file name.
            return null;
        }
        return path.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }

    /**
     * Compresses (zlib) and writes a chunk payload, allocating sectors first-fit. Rejects
     * uncompressed payloads larger than {@link #MAX_CHUNK_PAYLOAD_BYTES} up front, mirroring
     * the same bound enforced when decompressing in {@link #readChunk}. The external
     * {@code .mcc} file of an oversized chunk holds the deflate output, which can be slightly
     * larger than 32 MiB for a payload at the bound (deflate inflates incompressible data by
     * a small margin); the read side therefore bounds by the <em>decompressed</em> output,
     * not by the compressed file size.
     *
     * @throws IllegalArgumentException if {@code uncompressed.length > MAX_CHUNK_PAYLOAD_BYTES}
     */
    public void writeChunk(int localX, int localZ, byte[] uncompressed) throws IOException {
        if (uncompressed.length > MAX_CHUNK_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Chunk payload of " + uncompressed.length
                    + " bytes exceeds the " + MAX_CHUNK_PAYLOAD_BYTES + " byte limit");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, uncompressed.length / 4));
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos)) {
            dos.write(uncompressed);
        }
        byte[] compressed = bos.toByteArray();
        int payloadLen = 4 + 1 + compressed.length;
        boolean external = (payloadLen + SECTOR_BYTES - 1) / SECTOR_BYTES > 255;
        int sectorsNeeded = external ? 1 : (payloadLen + SECTOR_BYTES - 1) / SECTOR_BYTES;
        Path externalPath = external ? externalChunkPath(localX, localZ) : externalChunkPathOrNull(localX, localZ);
        if (external && java.nio.file.Files.exists(externalPath)
                && !java.nio.file.Files.isRegularFile(externalPath)) {
            throw new IOException("External chunk payload is not a regular file: " + externalPath);
        }

        int idx = indexOf(localX, localZ);
        int oldLoc = locations[idx];
        int oldTimestamp = timestamps[idx];
        int sectorOff = allocateSectors(sectorsNeeded); // old allocation remains reserved
        if (sectorOff > 0xFFFFFF) {
            // (sectorOff << 8) would silently wrap inside the 32-bit location entry and
            // corrupt the region file: reject the write instead of losing the offset.
            throw new IOException("Region file " + path + " is too large: sector offset "
                    + sectorOff + " exceeds the 24-bit location-table limit");
        }
        int newLoc = (sectorOff << 8) | sectorsNeeded;
        int newTimestamp = (int) (System.currentTimeMillis() / 1000L);
        ByteBuffer out = ByteBuffer.allocate(sectorsNeeded * SECTOR_BYTES);
        if (external) {
            out.putInt(1).put((byte) (COMPRESSION_ZLIB | EXTERNAL_FLAG));
        } else {
            out.putInt(compressed.length + 1).put(COMPRESSION_ZLIB).put(compressed);
        }
        out.position(0);

        Path staged = null;
        Path backup = null;
        boolean externalPublished = false;
        boolean headerWriteStarted = false;
        try {
            writeFully(out, (long) sectorOff * SECTOR_BYTES);
            channel.force(false);

            if (external) {
                staged = stageExternal(externalPath, compressed);
                if (java.nio.file.Files.exists(externalPath)) {
                    backup = temporarySibling(externalPath);
                    java.nio.file.Files.move(externalPath, backup,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    // Make the old-file rename durable BEFORE the staged publish: with
                    // both renames in flight and a crash between them, journal replay
                    // could drop the first rename while keeping the second, leaving the
                    // canonical .mcc path gone (the old payload survives only under the
                    // temporary backup name). Forcing the directory after the first
                    // rename keeps the rename order deterministic and the canonical name
                    // present on every crash boundary.
                    forceParentDirectory(externalPath);
                }
                java.nio.file.Files.move(staged, externalPath,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                staged = null;
                externalPublished = true;
                // Force the directory again so the publish itself is durable (crash
                // persistence): an unforced rename can be replayed as if it never
                // happened, reverting the canonical .mcc to its pre-publish state while
                // the region header already points at the new payload.
                forceParentDirectory(externalPath);
            }
            channel.force(false);

            headerWriteStarted = true;
            writeHeaderEntry(newLoc, newTimestamp, idx);
            channel.force(false);
            locations[idx] = newLoc;
            timestamps[idx] = newTimestamp;
            usedSectors.set(sectorOff, sectorOff + sectorsNeeded);
            if (oldLoc != 0) {
                usedSectors.clear(oldLoc >>> 8, (oldLoc >>> 8) + (oldLoc & 0xFF));
            }
            if (!external && externalPath != null) {
                try {
                    java.nio.file.Files.deleteIfExists(externalPath);
                } catch (IOException ignored) {
                    // The new inline header no longer references it.
                }
            }
            if (backup != null) {
                try {
                    java.nio.file.Files.deleteIfExists(backup);
                } catch (IOException ignored) {
                    // A backup left behind is not referenced by the region file.
                }
            }
        } catch (IOException failure) {
            if (headerWriteStarted) {
                try {
                    writeHeaderEntry(oldLoc, oldTimestamp, idx);
                    channel.force(false);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (externalPublished) {
                try {
                    java.nio.file.Files.deleteIfExists(externalPath);
                    if (backup != null) {
                        java.nio.file.Files.move(backup, externalPath,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        backup = null;
                    }
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            } else if (backup != null) {
                try {
                    java.nio.file.Files.move(backup, externalPath,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    backup = null;
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        } finally {
            deleteQuietly(staged);
            deleteQuietly(backup);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Temporary cleanup must not replace the original write failure.
            }
        }
    }
    private int allocateSectors(int count) {
        int start = 2;
        while (true) {
            int free = usedSectors.nextClearBit(start);
            int end = usedSectors.nextSetBit(free);
            if (end < 0 || end - free >= count) {
                return free;
            }
            start = end;
        }
    }

    private void writeHeaderEntry(int location, int timestamp, int idx) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(0, location);
        writeFully(b, idx * 4L);
        ByteBuffer t = ByteBuffer.allocate(4);
        t.putInt(0, timestamp);
        writeFully(t, SECTOR_BYTES + idx * 4L);
    }

    private static void validateCompressionType(byte compressionType) throws IOException {
        if (compressionType != COMPRESSION_GZIP && compressionType != COMPRESSION_ZLIB
                && compressionType != COMPRESSION_NONE && compressionType != COMPRESSION_LZ4) {
            throw new IOException("Unknown chunk compression type " + compressionType);
        }
    }

    private static Path temporarySibling(Path target) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = java.nio.file.Files.createTempFile(absolute.getParent(),
                absolute.getFileName().toString() + ".backup-", ".tmp");
        java.nio.file.Files.deleteIfExists(temporary);
        return temporary;
    }

    private static Path stageExternal(Path target, byte[] compressed) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = java.nio.file.Files.createTempFile(absolute.getParent(),
                absolute.getFileName().toString() + ".stage-", ".tmp");
        boolean complete = false;
        try (FileChannel staged = FileChannel.open(temporary,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(staged, ByteBuffer.wrap(compressed), 0);
            staged.force(true);
            complete = true;
            return temporary;
        } finally {
            if (!complete) {
                java.nio.file.Files.deleteIfExists(temporary);
            }
        }
    }

    private static void forceParentDirectory(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory handles cannot be forced on some Windows filesystems.
        }
    }

    public void sync() throws IOException {
        if (readOnly) {
            throw new NonWritableChannelException();
        }
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /** Reads until the buffer is full or EOF; returns the number of bytes read. */
    private int readUpTo(ByteBuffer buf, long pos) throws IOException {
        long p = pos;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, p);
            if (n < 0) {
                break;
            }
            p += n;
        }
        buf.flip();
        return (int) (p - pos);
    }

    private static void readFully(FileChannel channel, ByteBuffer buf, long pos) throws IOException {
        long p = pos;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, p);
            if (n < 0) {
                throw new EOFException("EOF at " + p);
            }
            p += n;
        }
        buf.flip();
    }

    private void readFully(ByteBuffer buf, long pos) throws IOException {
        readFully(channel, buf, pos);
    }

    private static void writeFully(FileChannel channel, ByteBuffer buf, long pos) throws IOException {
        long p = pos;
        while (buf.hasRemaining()) {
            p += channel.write(buf, p);
        }
    }

    private void writeFully(ByteBuffer buf, long pos) throws IOException {
        writeFully(channel, buf, pos);
    }

    /**
     * Reads a decompressing stream, throwing an {@link IOException} as soon as more
     * than {@code limit} bytes have been produced. This bounds the decompression
     * itself: the size check in {@link #readChunk} is only the second line of
     * defense, because by then the expansion has already been materialized.
     */
    private static byte[] readBounded(InputStream in, int limit) throws IOException {
        try (InputStream limited = new LimitedInputStream(in, limit)) {
            return limited.readAllBytes();
        }
    }

    /**
     * lz4-java block stream magic ({@code "LZ4Block"}, written by {@code LZ4BlockOutputStream});
     * every block starts with it, followed by a 1-byte token, a 4-byte little-endian
     * compressed length, a 4-byte little-endian original length and a 4-byte little-endian
     * xxhash32 checksum (21 header bytes per block). The block stream is self-contained:
     * the header lives inside the payload, both for inline region chunks and for the raw
     * compressed stream of an external {@code .mcc} file.
     */
    private static final byte[] LZ4_BLOCK_MAGIC = { 'L', 'Z', '4', 'B', 'l', 'o', 'c', 'k' };
    private static final int LZ4_BLOCK_HEADER_LENGTH = 8 + 1 + 4 + 4 + 4; // magic + token + lengths + checksum

    /**
     * Upper bound for a single LZ4 block's <em>compressed</em> length: the payload bound
     * plus the same 1 MiB margin the external {@code .mcc} size check uses. A legal writer
     * ({@code LZ4BlockOutputStream}, max block 32 MiB) stays far below it, so anything
     * larger is a hostile or corrupt stream.
     */
    private static final int LZ4_BLOCK_COMPRESSED_LIMIT = MAX_CHUNK_PAYLOAD_BYTES + 1024 * 1024;

    /**
     * Decompresses an LZ4 chunk with the same output bound as {@link #readBounded}.
     * Reads through {@link Lz4Native#newInputStream} -- which reuses the constructor
     * cached in {@code Lz4Native}'s static bridge (lz4-java is an optional runtime
     * dependency), so no per-chunk reflection lookup happens -- and a
     * {@link LimitedInputStream}, because {@link Lz4Native#decompress} has no size
     * bound and would materialize the whole expansion before returning. The error
     * behavior for a missing lz4 library matches {@link Lz4Native} (an
     * {@link UnsupportedOperationException}); corrupt data, which {@code Lz4Native}
     * reports as an unchecked {@code LZ4Exception}, is wrapped in an {@link IOException}
     * here so that every decode failure of {@link #readChunk} is a checked one -- the
     * same checked-{@code Exception} -> {@link IOException} wrapping
     * {@link Lz4Native#decompress} applies.
     */
    private static byte[] decompressLz4Bounded(byte[] data) throws IOException {
        if (!Lz4Native.available()) {
            throw new UnsupportedOperationException(
                    "Cannot read LZ4-compressed Anvil chunk: lz4-java is not on the classpath. "
                            + "Run the conversion on a Folia/Canvas server, or add net.jpountz.lz4:lz4.");
        }
        validateLz4BlockStream(data);
        try (InputStream in = Lz4Native.newInputStream(data)) {
            return readBounded(in, MAX_CHUNK_PAYLOAD_BYTES);
        } catch (RuntimeException e) {
            // LZ4Exception on corrupt data: Lz4Native throws it unchecked, but this path
            // feeds readChunk, which promises IOException for chunk read/decode failures.
            throw new IOException("LZ4 decompression failed", e);
        }
    }

    /**
     * Pre-validates every block header of an LZ4 block stream before it is handed to
     * {@link Lz4Native#newInputStream}, closing the input-side allocation hole:
     * {@code LZ4BlockInputStream.refill()} allocates a buffer of the block's claimed
     * compressed length and only rejects negative lengths, so a forged header claiming
     * ~2 GiB allocates ~2 GiB (OOM) before any read bound applies. The chunk payload is
     * fully in memory here (inline payloads are capped by the sector allocation,
     * external {@code .mcc} payloads by the size check in {@link #readChunk}), so walking
     * every 21-byte header is a cheap, allocation-free guard: the compressed length is
     * held to {@code MAX_CHUNK_PAYLOAD_BYTES + 1 MiB} and the original length to
     * {@link #MAX_CHUNK_PAYLOAD_BYTES} (the same output bound
     * {@link #LimitedInputStream} enforces). The layout mirrors {@code LZ4BlockInputStream}
     * exactly -- a stream this walk accepts is decoded by lz4-java without any unbounded
     * allocation; anything else fails here with an {@link IOException} instead of an OOM.
     */
    private static void validateLz4BlockStream(byte[] data) throws IOException {
        int off = 0;
        while (true) {
            if (data.length - off < LZ4_BLOCK_HEADER_LENGTH) {
                throw new IOException("Truncated LZ4 block stream: expected a " + LZ4_BLOCK_HEADER_LENGTH
                        + " byte block header, " + (data.length - off) + " bytes remain");
            }
            for (int i = 0; i < LZ4_BLOCK_MAGIC.length; i++) {
                if (data[off + i] != LZ4_BLOCK_MAGIC[i]) {
                    throw new IOException("Corrupt LZ4 block stream: bad block magic at byte " + off);
                }
            }
            // Little-endian lengths, exactly as LZ4BlockInputStream reads them.
            int compressedLen = (data[off + 9] & 0xFF) | (data[off + 10] & 0xFF) << 8
                    | (data[off + 11] & 0xFF) << 16 | (data[off + 12] & 0xFF) << 24;
            int originalLen = (data[off + 13] & 0xFF) | (data[off + 14] & 0xFF) << 8
                    | (data[off + 15] & 0xFF) << 16 | (data[off + 16] & 0xFF) << 24;
            if (compressedLen < 0 || originalLen < 0) {
                throw new IOException("Corrupt LZ4 block stream: negative block length at byte " + off);
            }
            if (compressedLen > LZ4_BLOCK_COMPRESSED_LIMIT) {
                throw new IOException("LZ4 block compressed length " + compressedLen
                        + " exceeds the " + LZ4_BLOCK_COMPRESSED_LIMIT + " byte limit");
            }
            if (originalLen > MAX_CHUNK_PAYLOAD_BYTES) {
                throw new IOException("LZ4 block original length " + originalLen
                        + " exceeds the " + MAX_CHUNK_PAYLOAD_BYTES + " byte limit");
            }
            if (originalLen == 0 && compressedLen == 0) {
                return; // end-of-stream marker written by LZ4BlockOutputStream.finish()
            }
            if (originalLen == 0 || compressedLen == 0) {
                throw new IOException("Corrupt LZ4 block stream: zero length on one side only at byte " + off);
            }
            off += LZ4_BLOCK_HEADER_LENGTH + compressedLen;
            if (off > data.length) {
                throw new IOException("LZ4 block at byte " + (off - LZ4_BLOCK_HEADER_LENGTH - compressedLen)
                        + " extends past the end of the chunk payload");
            }
        }
    }

    /** Throws an {@link IOException} once more than {@code limit} bytes have been read. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final int limit;
        private int count;

        LimitedInputStream(InputStream in, int limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++count > limit) {
                throw tooLarge();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && (count += n) > limit) {
                throw tooLarge();
            }
            return n;
        }

        private IOException tooLarge() {
            return new IOException("Decompressed chunk payload of " + count + " bytes exceeds the "
                    + MAX_CHUNK_PAYLOAD_BYTES + " byte limit");
        }
    }
}
