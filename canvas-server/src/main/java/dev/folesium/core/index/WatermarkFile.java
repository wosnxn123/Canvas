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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * One 8-byte per-shard watermark file (u64, big-endian), written atomically with a
 * {@code .tmp} sibling and {@link FileChannel#force(boolean)}. Crash-safe: a torn write
 * can never produce a partially updated value — the reader sees either the old or the
 * new watermark, never garbage.
 *
 * <p>File location convention: {@code <store>/idx/<keyspace>/<shardName>.wmk} (and
 * {@code .cwmk} for the compaction watermark). A missing, short, oversized, or
 * unreadable file reads back as 0.</p>
 */
public final class WatermarkFile {
    private static final int LENGTH = 8;

    private WatermarkFile() {
    }

    /**
     * Reads the watermark from {@code file} as an unsigned 64-bit big-endian value.
     *
     * @return the stored watermark, or {@code 0} if the file is missing, is not exactly
     *         8 bytes long, or cannot be read
     */
    public static long read(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() != LENGTH) {
                return 0L;
            }
            ByteBuffer b = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
            while (b.hasRemaining()) {
                if (channel.read(b) < 0) {
                    return 0L;
                }
            }
            b.flip();
            return b.getLong();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Atomically writes the watermark: serializes to a unique {@code .tmp-<uuid>}
     * sibling, forces it to disk (including metadata), and moves it over {@code file},
     * preferring an atomic replace and falling back to a plain replace move on
     * filesystems that do not support atomic moves. The unique temporary name makes
     * concurrent writers to the same file safe: no two calls share a staging path, so
     * one writer's move can never consume another writer's temporary file.
     *
     * @throws IOException if the file cannot be written
     */
    public static void write(Path file, long watermark) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            ByteBuffer b = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN).putLong(watermark);
            b.flip();
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (b.hasRemaining()) {
                    channel.write(b);
                }
                channel.force(true);
            }
            moveReplacing(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
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
}
