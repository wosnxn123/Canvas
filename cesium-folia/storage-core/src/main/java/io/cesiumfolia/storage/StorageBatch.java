package io.cesiumfolia.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Thread-safe-at-boundary batch builder; publish only after building is complete. */
public final class StorageBatch {
    private record Slot(StorageNamespace namespace, BinaryKey key) {}
    private final Map<Slot, WriteOperation> operations = new LinkedHashMap<>();

    public StorageBatch put(final StorageNamespace namespace, final BinaryKey key, final byte[] value) {
        final WriteOperation operation = WriteOperation.put(namespace, key, value);
        operations.put(new Slot(namespace, key), operation);
        return this;
    }

    /**
     * Adds a value whose array ownership is transferred to this batch. The caller must never
     * mutate the array after this call. Backend consumers still receive defensive copies.
     */
    public StorageBatch putOwned(final StorageNamespace namespace, final BinaryKey key, final byte[] ownedValue) {
        final WriteOperation operation = WriteOperation.putOwned(namespace, key, ownedValue);
        operations.put(new Slot(namespace, key), operation);
        return this;
    }

    public StorageBatch delete(final StorageNamespace namespace, final BinaryKey key) {
        final WriteOperation operation = WriteOperation.delete(namespace, key);
        operations.put(new Slot(namespace, key), operation);
        return this;
    }

    public boolean isEmpty() { return operations.isEmpty(); }
    public int size() { return operations.size(); }

    public List<WriteOperation> operations() {
        return List.copyOf(operations.values());
    }

    public StorageBatch copy() {
        final StorageBatch copy = new StorageBatch();
        for (final WriteOperation operation : operations.values()) {
            copy.operations.put(new Slot(operation.namespace(), operation.key()), operation);
        }
        return copy;
    }
}
