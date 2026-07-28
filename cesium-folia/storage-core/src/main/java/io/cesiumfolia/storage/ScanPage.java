package io.cesiumfolia.storage;

import java.util.List;
import java.util.Objects;

/** Immutable ordered page of storage entries. */
public record ScanPage(List<StorageEntry> entries, BinaryKey nextCursor, boolean hasMore) {
    public ScanPage {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
        BinaryKey previous = null;
        for (final StorageEntry entry : entries) {
            Objects.requireNonNull(entry, "entries contains null");
            if (previous != null && previous.compareTo(entry.key()) >= 0) {
                throw new IllegalArgumentException("entries must be in strictly increasing key order");
            }
            previous = entry.key();
        }
        if (entries.isEmpty()) {
            if (nextCursor != null || hasMore) {
                throw new IllegalArgumentException("An empty scan page cannot have a cursor or more entries");
            }
        } else {
            Objects.requireNonNull(nextCursor, "nextCursor");
            if (!entries.getLast().key().equals(nextCursor)) {
                throw new IllegalArgumentException("nextCursor must be the last returned key");
            }
        }
    }
}
