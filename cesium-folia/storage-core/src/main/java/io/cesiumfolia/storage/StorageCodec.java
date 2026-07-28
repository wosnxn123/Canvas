package io.cesiumfolia.storage;

/**
 * Typed value codec for the byte-oriented storage boundary.
 *
 * <p>Implementations must not retain or mutate the supplied value or encoded byte array. Callers
 * own the returned bytes and the storage boundary defensively copies them before asynchronous use.
 * A codec should reject malformed input with a {@link StorageException} (or another unchecked
 * exception) rather than silently returning a different value.</p>
 *
 * @param <T> logical value type
 */
public interface StorageCodec<T> {
    byte[] encode(T value);

    T decode(byte[] bytes);
}
