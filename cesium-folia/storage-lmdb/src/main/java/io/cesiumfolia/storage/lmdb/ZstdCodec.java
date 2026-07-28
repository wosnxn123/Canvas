package io.cesiumfolia.storage.lmdb;

import com.github.luben.zstd.Zstd;
import io.cesiumfolia.storage.StorageException;
import java.util.Objects;

/** Small, allocation-bounded Zstandard codec for logical storage values. */
public final class ZstdCodec {
    private final int maximumBytes;
    private final int level;

    public ZstdCodec(final int maximumBytes, final int level) {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must be non-negative");
        }
        this.maximumBytes = maximumBytes;
        this.level = level;
    }

    public ZstdCodec(final int maximumBytes) {
        this(maximumBytes, 3);
    }

    public ZstdCodec() {
        this(16 * 1024 * 1024, 3);
    }

    public byte[] compress(final byte[] logical) {
        Objects.requireNonNull(logical, "logical");
        if (logical.length > maximumBytes) {
            throw new StorageException("Logical value exceeds configured limit: " + logical.length);
        }
        try {
            return Zstd.compress(logical, level);
        } catch (final RuntimeException exception) {
            throw new StorageException("Zstandard compression failed", exception);
        }
    }

    public byte[] decompress(final byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        final long size;
        try {
            size = Zstd.getFrameContentSize(encoded);
        } catch (final RuntimeException exception) {
            throw new StorageException("Invalid Zstandard frame", exception);
        }
        if (size < 0 || size > maximumBytes || size > Integer.MAX_VALUE) {
            throw new StorageException("Zstandard frame exceeds configured decompression limit: " + size);
        }
        final byte[] result = new byte[(int) size];
        try {
            final long written = Zstd.decompress(result, encoded);
            if (Zstd.isError(written) || written != size) {
                throw new StorageException("Zstandard decompression failed: " + Zstd.getErrorName(written));
            }
            return result;
        } catch (final StorageException exception) {
            throw exception;
        } catch (final RuntimeException exception) {
            throw new StorageException("Zstandard decompression failed", exception);
        }
    }
}
