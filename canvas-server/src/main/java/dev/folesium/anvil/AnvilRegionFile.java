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
        FileChannel opened = null;
        try {
            opened = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            BitSet loadedSectors = new BitSet();
            loadedSectors.set(0, 2); // header sectors
            long size = opened.size();
            if (size < 2L * SECTOR_BYTES) {
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
            data = java.nio.file.Files.readAllBytes(externalPath);
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
                }
                java.nio.file.Files.move(staged, externalPath,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                staged = null;
                externalPublished = true;
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

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }
}
