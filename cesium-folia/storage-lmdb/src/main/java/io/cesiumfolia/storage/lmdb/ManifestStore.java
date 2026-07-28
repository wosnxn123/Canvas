package io.cesiumfolia.storage.lmdb;

import io.cesiumfolia.storage.StorageException;
import io.cesiumfolia.storage.StorageManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

final class ManifestStore {
    static final String FILE_NAME = "manifest.properties";

    private ManifestStore() {}

    static StorageManifest load(final Path root) {
        final Path path = root.resolve(FILE_NAME);
        final Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (final IOException exception) {
            throw new StorageException("Cannot read " + path, exception);
        }
        try {
            return new StorageManifest(
                required(properties, "format.id"),
                Integer.parseInt(required(properties, "format.version")),
                Integer.parseInt(required(properties, "minecraft.data-version")),
                required(properties, "compression"),
                required(properties, "schema.id"),
                Long.parseLong(required(properties, "generation")),
                parseBoolean(properties, "clean-shutdown"),
                Instant.parse(required(properties, "created-at"))
            );
        } catch (final IllegalArgumentException | DateTimeParseException exception) {
            throw new StorageException("Invalid " + path, exception);
        }
    }

    static void save(final Path root, final StorageManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        final Path target = root.resolve(FILE_NAME);
        final Path temporary = root.resolve(FILE_NAME + ".tmp-" + UUID.randomUUID());
        final Properties properties = new Properties();
        properties.setProperty("format.id", manifest.formatId());
        properties.setProperty("format.version", Integer.toString(manifest.formatVersion()));
        properties.setProperty("minecraft.data-version", Integer.toString(manifest.minecraftDataVersion()));
        properties.setProperty("compression", manifest.compression());
        properties.setProperty("schema.id", manifest.schemaId());
        properties.setProperty("generation", Long.toString(manifest.generation()));
        properties.setProperty("clean-shutdown", Boolean.toString(manifest.cleanShutdown()));
        properties.setProperty("created-at", manifest.createdAt().toString());

        try {
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                properties.store(output, "Cesium-Folia storage manifest");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException exception) {
                throw new StorageException("Filesystem does not support atomic manifest replacement", exception);
            }
            forceDirectory(root);
        } catch (final IOException exception) {
            throw new StorageException("Cannot persist " + target, exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (final IOException ignored) {
                // The committed target is authoritative; a uniquely named orphan is harmless.
            }
        }
    }

    static void validateIdentity(final StorageManifest requested, final StorageManifest stored) {
        if (!requested.formatId().equals(stored.formatId())
            || requested.formatVersion() != stored.formatVersion()
            || requested.minecraftDataVersion() != stored.minecraftDataVersion()
            || !requested.compression().equals(stored.compression())
            || !requested.schemaId().equals(stored.schemaId())) {
            throw new StorageException("Storage manifest format/schema does not match the requested backend");
        }
    }

    private static String required(final Properties properties, final String key) {
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing property " + key);
        }
        return value;
    }

    private static boolean parseBoolean(final Properties properties, final String key) {
        final String value = required(properties, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid boolean property " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static void forceDirectory(final Path root) {
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
            directory.force(true);
        } catch (final IOException | UnsupportedOperationException ignored) {
            // Windows and some providers do not expose durable directory handles.
            // The temporary file itself was forced before the atomic rename.
        }
    }
}
