package io.cesiumfolia.folia;

import io.cesiumfolia.storage.StorageException;
import io.cesiumfolia.storage.lmdb.LmdbStorageBackend;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Owns the paired global and dimension storage scopes for one Folia world. */
public final class FoliaStorageRuntime implements AutoCloseable {
    public static final String GLOBAL_DIRECTORY = "global";
    public static final String DIMENSIONS_DIRECTORY = "dimensions";

    private final Path root;
    private final LmdbStorageBackend globalBackend;
    private final LmdbStorageBackend dimensionsBackend;
    private final StorageHookAdapter global;
    private final StorageHookAdapter dimensions;
    private boolean closed;

    private FoliaStorageRuntime(final Path root, final LmdbStorageBackend globalBackend,
                                 final LmdbStorageBackend dimensionsBackend,
                                 final StorageHookAdapter global, final StorageHookAdapter dimensions) {
        this.root = root;
        this.globalBackend = globalBackend;
        this.dimensionsBackend = dimensionsBackend;
        this.global = global;
        this.dimensions = dimensions;
    }

    public static FoliaStorageRuntime open(final Path root, final int minecraftDataVersion,
                                           final boolean existingWorld,
                                           final LmdbStorageBackend.Options backendOptions,
                                           final CommitPumpConfig pumpConfig,
                                           final boolean allowUncleanRecovery) {
        final Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        final LmdbStorageBackend.Options options = Objects.requireNonNull(backendOptions, "backendOptions");
        final CommitPumpConfig pumps = Objects.requireNonNull(pumpConfig, "pumpConfig");
        if (minecraftDataVersion < 0) {
            throw new IllegalArgumentException("minecraftDataVersion must not be negative");
        }
        final Path globalRoot = normalizedRoot.resolve(GLOBAL_DIRECTORY);
        final Path dimensionsRoot = normalizedRoot.resolve(DIMENSIONS_DIRECTORY);
        preflight(normalizedRoot, globalRoot, dimensionsRoot, minecraftDataVersion, existingWorld);

        final boolean globalWasAbsent = !Files.exists(globalRoot, LinkOption.NOFOLLOW_LINKS);
        final boolean dimensionsWasAbsent = !Files.exists(dimensionsRoot, LinkOption.NOFOLLOW_LINKS);
        LmdbStorageBackend globalBackend = null;
        LmdbStorageBackend dimensionsBackend = null;
        StorageHookAdapter global = null;
        StorageHookAdapter dimensions = null;
        try {
            final LmdbStorageBackend.UncleanOpenPolicy policy = allowUncleanRecovery
                ? LmdbStorageBackend.UncleanOpenPolicy.RECOVER : LmdbStorageBackend.UncleanOpenPolicy.REJECT;
            globalBackend = LmdbStorageBackend.open(globalRoot,
                FoliaStorageSchemas.globalManifest(minecraftDataVersion), options,
                LmdbStorageBackend.OpenMode.READ_WRITE, policy);
            global = new StorageHookAdapter(globalBackend, StorageHookAdapter.Scope.GLOBAL,
                "global", pumps);
            dimensionsBackend = LmdbStorageBackend.open(dimensionsRoot,
                FoliaStorageSchemas.dimensionsManifest(minecraftDataVersion), options,
                LmdbStorageBackend.OpenMode.READ_WRITE, policy);
            dimensions = new StorageHookAdapter(dimensionsBackend, StorageHookAdapter.Scope.DIMENSION,
                "dimensions", pumps);
            return new FoliaStorageRuntime(normalizedRoot, globalBackend, dimensionsBackend, global, dimensions);
        } catch (final Throwable failure) {
            abortOpened(global, dimensions, globalBackend, dimensionsBackend, failure);
            if (globalWasAbsent) deleteCreatedScope(globalRoot, failure);
            if (dimensionsWasAbsent) deleteCreatedScope(dimensionsRoot, failure);
            throw rethrow("Cannot open paired Folia storage scopes at " + normalizedRoot, failure);
        }
    }

    public Path root() {
        return root;
    }

    public LmdbStorageBackend globalBackend() {
        return globalBackend;
    }

    public LmdbStorageBackend dimensionsBackend() {
        return dimensionsBackend;
    }

    public StorageHookAdapter global() {
        return global;
    }

    public StorageHookAdapter dimensions() {
        return dimensions;
    }

    /** Drains both pumps for a clean close, or aborts both without claiming a clean shutdown. */
    public synchronized void close(final boolean clean) {
        if (closed) return;
        closed = true;
        final Throwable cause = clean ? null : new IllegalStateException("Folia storage runtime closed uncleanly");
        final CompletionStage<Void> globalClose = beginClose(global, clean, cause);
        final CompletionStage<Void> dimensionsClose = beginClose(dimensions, clean, cause);
        Throwable failure = await(globalClose, null);
        failure = await(dimensionsClose, failure);
        if (failure != null) throw rethrow("Folia storage runtime close failed", failure);
    }

    @Override
    public void close() {
        close(true);
    }

    private static CompletionStage<Void> beginClose(final StorageHookAdapter adapter, final boolean clean,
                                                     final Throwable cause) {
        try {
            return clean ? adapter.closeAsync() : adapter.abortAsync(cause);
        } catch (final Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedStage(failure);
        }
    }

    private static Throwable await(final CompletionStage<Void> stage, final Throwable primary) {
        Throwable failure = primary;
        try {
            stage.toCompletableFuture().join();
        } catch (final Throwable thrown) {
            final Throwable unwrapped = unwrap(thrown);
            if (failure == null) failure = unwrapped;
            else suppress(failure, unwrapped);
        }
        return failure;
    }

    private static void abortOpened(final StorageHookAdapter global, final StorageHookAdapter dimensions,
                                    final LmdbStorageBackend globalBackend,
                                    final LmdbStorageBackend dimensionsBackend, final Throwable primary) {
        abortOne(dimensions, dimensionsBackend, primary);
        abortOne(global, globalBackend, primary);
    }

    private static void abortOne(final StorageHookAdapter adapter, final LmdbStorageBackend backend,
                                 final Throwable primary) {
        if (adapter != null) {
            try {
                adapter.abortAsync(primary).toCompletableFuture().join();
            } catch (final Throwable failure) {
                suppress(primary, unwrap(failure));
            }
        } else if (backend != null) {
            try {
                backend.abortAsync(primary).toCompletableFuture().join();
            } catch (final Throwable failure) {
                suppress(primary, unwrap(failure));
            }
        }
    }

    private static void deleteCreatedScope(final Path scope, final Throwable primary) {
        if (!Files.exists(scope, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(scope, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path directory, final IOException exception)
                    throws IOException {
                    if (exception != null) throw exception;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final Throwable failure) {
            suppress(primary, failure);
        }
    }

    private static void preflight(final Path root, final Path global, final Path dimensions,
                                  final int minecraftDataVersion, final boolean existingWorld) {
        final boolean globalPresent = Files.exists(global, LinkOption.NOFOLLOW_LINKS);
        final boolean dimensionsPresent = Files.exists(dimensions, LinkOption.NOFOLLOW_LINKS);
        if (!existingWorld) {
            if (globalPresent || dimensionsPresent) {
                throw new StorageException("New-world storage root already contains a paired scope: " + root);
            }
            return;
        }
        if (!globalPresent || !dimensionsPresent) {
            throw new StorageException("Existing-world storage requires both global and dimensions scopes: " + root);
        }
        requireCompleteScope(global, FoliaStorageSchemas.GLOBAL, minecraftDataVersion);
        requireCompleteScope(dimensions, FoliaStorageSchemas.DIMENSIONS, minecraftDataVersion);
    }

    private static void requireCompleteScope(final Path scope, final String schema,
                                             final int minecraftDataVersion) {
        if (!Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(scope.resolve("manifest.properties"), LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(scope.resolve("data.mdb"), LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Incomplete storage scope: " + scope);
        }
        FoliaStorageSchemas.preflight(scope, schema, minecraftDataVersion);
    }

    private static void suppress(final Throwable primary, final Throwable secondary) {
        if (primary != secondary) primary.addSuppressed(secondary);
    }

    private static Throwable unwrap(final Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RuntimeException rethrow(final String message, final Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new StorageException(message, failure);
    }
}
