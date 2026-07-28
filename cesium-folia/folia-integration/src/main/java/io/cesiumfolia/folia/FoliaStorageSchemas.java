package io.cesiumfolia.folia;

import io.cesiumfolia.storage.StorageException;
import io.cesiumfolia.storage.StorageManifest;
import io.cesiumfolia.storage.lmdb.LmdbStorageBackend;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;

/** Stable on-disk schema identities shared by Folia server-fork integrations. */
public final class FoliaStorageSchemas {
    public static final String GLOBAL = "cesium-folia-global-v1";
    public static final String DIMENSIONS = "cesium-folia-dimensions-v1";

    private FoliaStorageSchemas() {}

    public static StorageManifest globalManifest(final int minecraftDataVersion) {
        return manifest(GLOBAL, minecraftDataVersion);
    }

    public static StorageManifest dimensionsManifest(final int minecraftDataVersion) {
        return manifest(DIMENSIONS, minecraftDataVersion);
    }

    /** Creates the requested identity without opening an LMDB environment. */
    public static StorageManifest manifest(final String schemaId, final int minecraftDataVersion) {
        Objects.requireNonNull(schemaId, "schemaId");
        if (minecraftDataVersion < 0) {
            throw new IllegalArgumentException("minecraftDataVersion must not be negative");
        }
        return new StorageManifest(LmdbStorageBackend.FORMAT_ID, LmdbStorageBackend.FORMAT_VERSION,
            minecraftDataVersion, "zstd", schemaId, 0, true, Instant.now());
    }

    /** Validates an existing manifest identity without opening or mutating its LMDB environment. */
    public static StorageManifest preflight(final Path scopeRoot, final String schemaId,
                                            final int minecraftDataVersion) {
        Objects.requireNonNull(scopeRoot, "scopeRoot");
        Objects.requireNonNull(schemaId, "schemaId");
        if (minecraftDataVersion < 0) {
            throw new IllegalArgumentException("minecraftDataVersion must not be negative");
        }
        final Path path = scopeRoot.resolve("manifest.properties");
        final Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            final StorageManifest stored = new StorageManifest(
                required(properties, "format.id"),
                Integer.parseInt(required(properties, "format.version")),
                Integer.parseInt(required(properties, "minecraft.data-version")),
                required(properties, "compression"),
                required(properties, "schema.id"),
                Long.parseLong(required(properties, "generation")),
                strictBoolean(properties, "clean-shutdown"),
                Instant.parse(required(properties, "created-at"))
            );
            if (!LmdbStorageBackend.FORMAT_ID.equals(stored.formatId())
                || LmdbStorageBackend.FORMAT_VERSION != stored.formatVersion()
                || minecraftDataVersion != stored.minecraftDataVersion()
                || !"zstd".equals(stored.compression())
                || !schemaId.equals(stored.schemaId())) {
                throw new StorageException("Storage manifest format/schema does not match " + path);
            }
            return stored;
        } catch (final IOException | IllegalArgumentException | java.time.DateTimeException failure) {
            throw new StorageException("Cannot preflight storage manifest " + path, failure);
        }
    }

    private static String required(final Properties properties, final String key) {
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing property " + key);
        }
        return value;
    }

    private static boolean strictBoolean(final Properties properties, final String key) {
        final String value = required(properties, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid boolean property " + key);
        }
        return Boolean.parseBoolean(value);
    }
}
