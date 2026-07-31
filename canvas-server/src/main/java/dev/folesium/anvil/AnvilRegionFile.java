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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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

    /** {@code r.<regionX>.<regionZ>.mca}, the only naming Anvil uses. */
    private static final java.util.regex.Pattern REGION_NAME =
            java.util.regex.Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mc[ar]$");

    private final Path path;
    private final FileChannel channel;
    private final int[] locations = new int[CHUNKS_PER_REGION];
    private final int[] timestamps = new int[CHUNKS_PER_REGION];
    private final BitSet usedSectors = new BitSet();

    public AnvilRegionFile(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        usedSectors.set(0, 2); // header sectors
        if (channel.size() < 2L * SECTOR_BYTES) {
            // fresh file: write empty header
            channel.write(ByteBuffer.allocate(2 * SECTOR_BYTES), 0);
        } else {
            ByteBuffer header = ByteBuffer.allocate(2 * SECTOR_BYTES);
            readFully(header, 0);
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                int loc = header.getInt(i * 4);
                locations[i] = loc;
                if (loc != 0) {
                    int off = loc >>> 8;
                    int count = loc & 0xFF;
                    usedSectors.set(off, off + count);
                }
            }
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                timestamps[i] = header.getInt(SECTOR_BYTES + i * 4);
            }
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
        byte compressionType = (byte) (rawType & ~EXTERNAL_FLAG);
        byte[] data;
        if ((rawType & EXTERNAL_FLAG) != 0) {
            // Oversized chunk: the region file holds only the header, the payload is in
            // c.<chunkX>.<chunkZ>.mcc next to it.
            data = java.nio.file.Files.readAllBytes(externalChunkPath(localX, localZ));
        } else {
            data = new byte[length - 1];
            buf.position(5);
            buf.get(data);
        }
        return switch (compressionType) {
            case COMPRESSION_GZIP -> readAll(new GZIPInputStream(new ByteArrayInputStream(data)));
            case COMPRESSION_ZLIB -> readAll(new InflaterInputStream(new ByteArrayInputStream(data)));
            case COMPRESSION_NONE -> data;
            case COMPRESSION_LZ4 -> Lz4Native.decompress(data);
            default -> throw new IOException("Unknown chunk compression type " + compressionType);
        };
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
        long chunkX = Long.parseLong(m.group(1)) * 32 + (localX & 31);
        long chunkZ = Long.parseLong(m.group(2)) * 32 + (localZ & 31);
        return path.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }

    /** Compresses (zlib) and writes a chunk payload, allocating sectors first-fit. */
    public void writeChunk(int localX, int localZ, byte[] uncompressed) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, uncompressed.length / 4));
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos)) {
            dos.write(uncompressed);
        }
        byte[] compressed = bos.toByteArray();

        int payloadLen = 4 + 1 + compressed.length;
        // A chunk that needs more than 255 sectors cannot be addressed by the 8-bit sector
        // count, so Anvil stores it in a sibling .mcc file and keeps only the header here.
        boolean external = (payloadLen + SECTOR_BYTES - 1) / SECTOR_BYTES > 255;
        int sectorsNeeded = external ? 1 : (payloadLen + SECTOR_BYTES - 1) / SECTOR_BYTES;

        int idx = indexOf(localX, localZ);
        int oldLoc = locations[idx];
        if (oldLoc != 0) {
            usedSectors.clear(oldLoc >>> 8, (oldLoc >>> 8) + (oldLoc & 0xFF));
        }

        int sectorOff = allocateSectors(sectorsNeeded);
        ByteBuffer out = ByteBuffer.allocate(sectorsNeeded * SECTOR_BYTES);
        if (external) {
            java.nio.file.Files.write(externalChunkPath(localX, localZ), compressed);
            out.putInt(1).put((byte) (COMPRESSION_ZLIB | EXTERNAL_FLAG));
        } else {
            // A previous, larger version of this chunk may have been stored externally.
            Path stale = externalChunkPathOrNull(localX, localZ);
            if (stale != null) {
                java.nio.file.Files.deleteIfExists(stale);
            }
            out.putInt(compressed.length + 1).put(COMPRESSION_ZLIB).put(compressed);
        }
        out.position(0);
        writeFully(out, (long) sectorOff * SECTOR_BYTES);

        locations[idx] = (sectorOff << 8) | sectorsNeeded;
        timestamps[idx] = (int) (System.currentTimeMillis() / 1000L);
        usedSectors.set(sectorOff, sectorOff + sectorsNeeded);
        writeHeaderEntry(idx);
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

    private void writeHeaderEntry(int idx) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(0, locations[idx]);
        writeFully(b, idx * 4L);
        ByteBuffer t = ByteBuffer.allocate(4);
        t.putInt(0, timestamps[idx]);
        writeFully(t, SECTOR_BYTES + idx * 4L);
    }

    public void sync() throws IOException {
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

    private void readFully(ByteBuffer buf, long pos) throws IOException {
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

    private void writeFully(ByteBuffer buf, long pos) throws IOException {
        long p = pos;
        while (buf.hasRemaining()) {
            p += channel.write(buf, p);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }
}
