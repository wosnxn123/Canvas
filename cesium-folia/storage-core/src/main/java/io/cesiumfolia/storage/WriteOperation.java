package io.cesiumfolia.storage;

import java.util.Objects;

/** A staged put or delete. A null value is never exposed; deletes have an explicit kind. */
public final class WriteOperation {
    public enum Kind { PUT, DELETE }

    private final StorageNamespace namespace;
    private final BinaryKey key;
    private final Kind kind;
    private final byte[] value;

    private WriteOperation(final StorageNamespace namespace, final BinaryKey key, final Kind kind,
                           final byte[] value, final boolean takeOwnership) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.key = Objects.requireNonNull(key, "key");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.value = value == null || takeOwnership ? value : value.clone();
    }

    public static WriteOperation put(final StorageNamespace namespace, final BinaryKey key, final byte[] value) {
        Objects.requireNonNull(value, "value");
        return new WriteOperation(namespace, key, Kind.PUT, value, false);
    }

    static WriteOperation putOwned(final StorageNamespace namespace, final BinaryKey key, final byte[] value) {
        Objects.requireNonNull(value, "value");
        return new WriteOperation(namespace, key, Kind.PUT, value, true);
    }

    public static WriteOperation delete(final StorageNamespace namespace, final BinaryKey key) {
        return new WriteOperation(namespace, key, Kind.DELETE, null, true);
    }


    public StorageNamespace namespace() { return namespace; }
    public BinaryKey key() { return key; }
    public Kind kind() { return kind; }
    public byte[] value() { return value == null ? null : value.clone(); }
}
