package io.cesiumfolia.storage;

import java.time.Instant;
import java.util.Objects;

/** On-disk format identity. It is deliberately independent from a Minecraft DataVersion. */
public record StorageManifest(
    String formatId,
    int formatVersion,
    int minecraftDataVersion,
    String compression,
    String schemaId,
    long generation,
    boolean cleanShutdown,
    Instant createdAt
) {
    public StorageManifest {
        Objects.requireNonNull(formatId, "formatId");
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(schemaId, "schemaId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (formatId.isBlank() || compression.isBlank() || schemaId.isBlank()) {
            throw new IllegalArgumentException("Manifest identity fields must not be blank");
        }
        if (formatVersion <= 0 || generation < 0) {
            throw new IllegalArgumentException("Invalid format version or generation");
        }
    }

    public StorageManifest nextGeneration(final boolean clean) {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("Storage manifest generation overflow");
        }
        return new StorageManifest(formatId, formatVersion, minecraftDataVersion, compression, schemaId,
            generation + 1, clean, createdAt);
    }
}
