package io.cesiumfolia.storage.lmdb;

import io.cesiumfolia.storage.StorageException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;

/** Process/world lock shared by the backend and offline tools. */
public final class StorageLock implements AutoCloseable {
    public static final String FILE_NAME = ".cesium-folia.lock";

    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private StorageLock(final FileChannel channel, final FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static StorageLock acquire(final Path root) {
        Objects.requireNonNull(root, "root");
        final Path normalized = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
            final FileChannel channel = FileChannel.open(normalized.resolve(FILE_NAME),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                final FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new StorageException("Storage root is already locked: " + normalized);
                }
                return new StorageLock(channel, lock);
            } catch (final OverlappingFileLockException exception) {
                channel.close();
                throw new StorageException("Storage root is already locked: " + normalized, exception);
            } catch (final RuntimeException exception) {
                channel.close();
                throw exception;
            }
        } catch (final IOException exception) {
            throw new StorageException("Cannot lock storage root " + normalized, exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        StorageException failure = null;
        try {
            lock.release();
        } catch (final IOException exception) {
            failure = new StorageException("Cannot release storage lock", exception);
        }
        try {
            channel.close();
        } catch (final IOException exception) {
            if (failure == null) {
                failure = new StorageException("Cannot close storage lock", exception);
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }
}
