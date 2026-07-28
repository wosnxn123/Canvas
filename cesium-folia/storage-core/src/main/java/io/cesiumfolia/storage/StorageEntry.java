package io.cesiumfolia.storage;

import java.util.Arrays;
import java.util.Objects;

public record StorageEntry(BinaryKey key, byte[] value) {
    public StorageEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof StorageEntry other
            && key.equals(other.key)
            && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + Arrays.hashCode(value);
    }
}
