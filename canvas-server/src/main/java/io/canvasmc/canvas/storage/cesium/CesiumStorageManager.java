package io.canvasmc.canvas.storage.cesium;

import io.canvasmc.canvas.GlobalConfiguration;
import io.cesiumfolia.folia.BackpressureMetrics;
import io.cesiumfolia.folia.CommitPumpConfig;
import io.cesiumfolia.folia.FoliaStorageRuntime;
import io.cesiumfolia.folia.StorageHookAdapter;
import io.cesiumfolia.storage.BinaryKey;
import io.cesiumfolia.storage.StorageKeyCodecs;
import io.cesiumfolia.storage.StorageManifest;
import io.cesiumfolia.storage.StorageNamespace;
import io.cesiumfolia.storage.lmdb.LmdbStorageBackend;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide Canvas adapter for the reusable Cesium-Folia storage runtime. */
public final class CesiumStorageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CesiumStorage");
    private static final CesiumStorageManager DISABLED = new CesiumStorageManager();
    private static volatile CesiumStorageManager current = DISABLED;

    private final boolean enabled;
    private final @Nullable Settings settings;
    private final @Nullable FoliaStorageRuntime runtime;
    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    private final AtomicBoolean closing = new AtomicBoolean();

    private CesiumStorageManager() {
        this.enabled = false;
        this.settings = null;
        this.runtime = null;
    }

    private CesiumStorageManager(final Settings settings) {
        this.enabled = false;
        this.settings = settings;
        this.runtime = null;
    }

    private CesiumStorageManager(final Settings settings, final FoliaStorageRuntime runtime) {
        this.enabled = true;
        this.settings = settings;
        this.runtime = runtime;
    }

    public static CesiumStorageManager current() {
        return current;
    }

    /** Disabled mode performs no filesystem access. Enabled mode opens both scopes or neither. */
    public static synchronized CesiumStorageManager open(final Path worldRoot, final boolean existingWorld) {
        Objects.requireNonNull(worldRoot, "worldRoot");
        final Settings settings = Settings.from(GlobalConfiguration.getInstance().cesiumStorage);
        if (!settings.enabled()) {
            if (current.enabled) {
                throw new IllegalStateException("Cannot disable open Cesium storage");
            }
            current = new CesiumStorageManager(settings);
            LOGGER.info("Cesium storage is disabled (configured backend={})", settings.backend());
            return current;
        }

        final Path root = resolveStorageRoot(worldRoot, settings.rootDirectory());
        if (current.enabled) {
            if (root.equals(current.runtime().root()) && settings.equals(current.settings)) return current;
            throw new IllegalStateException("Cesium storage is already open for " + current.runtime().root());
        }
        final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        final LmdbStorageBackend.Options backendOptions = new LmdbStorageBackend.Options(
            settings.initialMapSizeBytes(), settings.maximumMapSizeBytes(), settings.maximumReaders(),
            settings.maximumValueBytes(), settings.compressionLevel(), settings.closeTimeout(),
            settings.maximumCommitAttempts());
        final CommitPumpConfig pumpConfig = new CommitPumpConfig(
            settings.maxBatchOperations(), settings.backpressureOperationThreshold(),
            settings.backpressureByteThreshold(), settings.initialRetryDelay(),
            settings.maximumRetryDelay(), settings.closeTimeout());

        try {
            final FoliaStorageRuntime runtime = FoliaStorageRuntime.open(root, dataVersion, existingWorld,
                backendOptions, pumpConfig, settings.allowUncleanRecovery());
            current = new CesiumStorageManager(settings, runtime);
            LOGGER.info("Cesium storage enabled: backend={}, path={}, map={}..{} bytes, readers={}, maxValue={} bytes",
                settings.backend(), runtime.root(), settings.initialMapSizeBytes(), settings.maximumMapSizeBytes(),
                settings.maximumReaders(), settings.maximumValueBytes());
            return current;
        } catch (final Throwable failure) {
            current = DISABLED;
            throw propagate(failure);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public @Nullable RegionStore regionStore(final RegionStorageInfo info) {
        Objects.requireNonNull(info, "info");
        if (!enabled) return null;
        ensureHealthy();
        final StorageNamespace namespace = switch (info.type()) {
            case "chunk", "chunks" -> StorageNamespace.CHUNKS;
            case "poi" -> StorageNamespace.POI;
            case "entity", "entities" -> StorageNamespace.ENTITIES;
            default -> throw new IllegalArgumentException("Unsupported Cesium region type: " + info.type());
        };
        return new RegionStore(this, dimensions(), namespace, info.dimension().identifier().toString());
    }

    public SavedDataStore savedData(final ResourceKey<Level> dimension) {
        Objects.requireNonNull(dimension, "dimension");
        requireEnabled();
        ensureHealthy();
        return new SavedDataStore(this, dimensions(), dimension.identifier().toString());
    }

    public Optional<byte[]> readPlayer(final UUID uuid) {
        return readGlobal(StorageNamespace.PLAYERS, uuid);
    }

    public CompletionStage<Void> writePlayer(final UUID uuid, final @Nullable byte[] value) {
        return writeGlobal(StorageNamespace.PLAYERS, uuid, value, "player " + uuid);
    }

    public Optional<byte[]> readAdvancement(final UUID uuid) {
        return readGlobal(StorageNamespace.ADVANCEMENTS, uuid);
    }

    public CompletionStage<Void> writeAdvancement(final UUID uuid, final @Nullable byte[] value) {
        return writeGlobal(StorageNamespace.ADVANCEMENTS, uuid, value, "advancements " + uuid);
    }

    public Optional<byte[]> readStatistics(final UUID uuid) {
        return readGlobal(StorageNamespace.STATISTICS, uuid);
    }

    public CompletionStage<Void> writeStatistics(final UUID uuid, final @Nullable byte[] value) {
        return writeGlobal(StorageNamespace.STATISTICS, uuid, value, "statistics " + uuid);
    }
    public Optional<byte[]> readWorldData() {
        requireEnabled();
        ensureHealthy();
        return joinObserved(global().read(StorageNamespace.WORLD_DATA, StorageKeyCodecs.string("level")),
            "world metadata read");
    }

    public CompletionStage<Void> writeWorldData(final @Nullable byte[] value) {
        requireEnabled();
        ensureHealthy();
        final BinaryKey key = StorageKeyCodecs.string("level");
        return submitObserved(() -> value == null ? global().delete(StorageNamespace.WORLD_DATA, key)
            : global().put(StorageNamespace.WORLD_DATA, key, value), "world metadata write");
    }


    public CompletionStage<Void> observe(final CompletionStage<Void> stage, final String operation) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(operation, "operation");
        stage.whenComplete((ignored, failure) -> {
            if (failure != null) terminalFailure(operation, unwrap(failure));
        });
        return stage;
    }

    /** Clean close drains both runtime scopes; abnormal close aborts both. */
    public void flushAndClose(final boolean clean) {
        if (!enabled || !closing.compareAndSet(false, true)) return;
        final boolean cleanClose = clean && terminalFailure.get() == null;
        Throwable failure = null;
        try {
            runtime().close(cleanClose);
        } catch (final Throwable thrown) {
            failure = unwrap(thrown);
        } finally {
            logMetrics("dimensions", dimensions().metrics());
            logMetrics("global", global().metrics());
            current = DISABLED;
        }
        if (failure != null) {
            terminalFailure("shutdown", failure);
            throw propagate(failure);
        }
        LOGGER.info("Cesium storage {} at {}", cleanClose ? "closed cleanly" : "aborted uncleanly", runtime().root());
    }

    public void configurationReloaded(final GlobalConfiguration.CesiumStorage configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (enabled && !settings().equals(Settings.from(configuration))) {
            LOGGER.warn("Cesium storage settings changed on reload; startup settings remain active until restart");
        } else if (!enabled && configuration.enabled) {
            LOGGER.warn("Cesium storage was enabled on reload and will remain disabled until restart");
        }
    }

    /** Cheap immutable diagnostic snapshot; no filesystem or backend I/O is performed. */
    public Status status() {
        if (!enabled) return Status.disabled(settings == null ? "unconfigured" : settings.backend());
        final Throwable failure = terminalFailure.get();
        final String state = closing.get() ? "closing" : failure == null ? "healthy" : "failed";
        return new Status(true, settings().backend(), runtime().root(), state,
            failure == null ? null : failure.toString(),
            scopeStatus(globalBackend(), global().metrics()),
            scopeStatus(dimensionsBackend(), dimensions().metrics()));
    }

    private Optional<byte[]> readGlobal(final StorageNamespace namespace, final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireEnabled();
        ensureHealthy();
        return joinObserved(global().read(namespace, StorageKeyCodecs.uuid(uuid)), namespace.databaseName() + " read " + uuid);
    }

    private CompletionStage<Void> writeGlobal(final StorageNamespace namespace, final UUID uuid,
                                               final @Nullable byte[] value, final String operation) {
        Objects.requireNonNull(uuid, "uuid");
        requireEnabled();
        ensureHealthy();
        final BinaryKey key = StorageKeyCodecs.uuid(uuid);
        return submitObserved(() -> value == null ? global().delete(namespace, key)
            : global().put(namespace, key, value), operation);
    }

    private CompletionStage<Void> submitObserved(
        final java.util.function.Supplier<CompletionStage<Void>> submission,
        final String operation
    ) {
        try {
            return observe(submission.get(), operation);
        } catch (final RuntimeException failure) {
            terminalFailure(operation, failure);
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    private void terminalFailure(final String operation, final Throwable failure) {
        if (!terminalFailure.compareAndSet(null, failure)) return;
        LOGGER.error("Cesium terminal failure during {}; requesting abnormal server halt", operation, failure);
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null) server.cesium$storageFailed();
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Cesium storage is disabled");
    }

    private void ensureHealthy() {
        final Throwable failure = terminalFailure.get();
        if (failure != null) throw new IllegalStateException("Cesium storage terminally failed", failure);
        if (closing.get()) throw new IllegalStateException("Cesium storage is closing");
    }

    private FoliaStorageRuntime runtime() {
        return Objects.requireNonNull(runtime, "storage runtime");
    }

    private StorageHookAdapter global() {
        return runtime().global();
    }

    private StorageHookAdapter dimensions() {
        return runtime().dimensions();
    }

    private LmdbStorageBackend globalBackend() {
        return runtime().globalBackend();
    }

    private LmdbStorageBackend dimensionsBackend() {
        return runtime().dimensionsBackend();
    }

    private Settings settings() {
        return Objects.requireNonNull(settings, "settings");
    }

    private static Path resolveStorageRoot(final Path worldRoot, final String configuredRoot) {
        final Path relative;
        try {
            relative = Path.of(configuredRoot);
        } catch (final java.nio.file.InvalidPathException failure) {
            throw new IllegalArgumentException("Invalid cesiumStorage.rootDirectory", failure);
        }
        if (configuredRoot.isBlank() || relative.isAbsolute()) {
            throw new IllegalArgumentException("cesiumStorage.rootDirectory must be a nonblank world-relative path");
        }
        final Path normalizedWorldRoot = worldRoot.toAbsolutePath().normalize();
        final Path resolved = normalizedWorldRoot.resolve(relative).normalize();
        if (resolved.equals(normalizedWorldRoot) || !resolved.startsWith(normalizedWorldRoot)) {
            throw new IllegalArgumentException("cesiumStorage.rootDirectory must remain inside the world root");
        }
        return resolved;
    }


    private static Throwable unwrap(final Throwable thrown) {
        return (thrown instanceof CompletionException || thrown instanceof ExecutionException)
            && thrown.getCause() != null ? thrown.getCause() : thrown;
    }

    private static RuntimeException propagate(final Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new CompletionException(failure);
    }

    private <T> T joinObserved(final CompletionStage<T> stage, final String operation) {
        try {
            return stage.toCompletableFuture().join();
        } catch (final CompletionException failure) {
            terminalFailure(operation, unwrap(failure));
            throw failure;
        }
    }

    private static void logMetrics(final String scope, final BackpressureMetrics metrics) {
        LOGGER.info("Cesium {} metrics: outstanding={}/{}, highWater={}/{}, coalesced={}, commits={}, failures={}, retries={}, backpressure={}, flushFailures={}",
            scope, metrics.outstandingOperations(), metrics.outstandingBytes(), metrics.highWaterOperations(),
            metrics.highWaterBytes(), metrics.coalescedWrites(), metrics.successfulCommits(),
            metrics.commitFailures(), metrics.retries(), metrics.backpressureEvents(), metrics.flushFailures());
    }

    private static ScopeStatus scopeStatus(final LmdbStorageBackend backend, final BackpressureMetrics metrics) {
        final StorageManifest manifest = backend.manifest();
        return new ScopeStatus(manifest.schemaId(), manifest.generation(), manifest.cleanShutdown(),
            metrics.outstandingOperations(), metrics.outstandingBytes(), metrics.highWaterOperations(),
            metrics.highWaterBytes(), metrics.coalescedWrites(), metrics.successfulCommits(),
            metrics.commitFailures(), metrics.flushFailures(), metrics.retries(),
            metrics.backpressureEvents(), metrics.backpressured(), metrics.acceptingWrites());
    }

    public record Status(boolean enabled, String backend, @Nullable Path path, String state,
                         @Nullable String terminalFailure, @Nullable ScopeStatus global,
                         @Nullable ScopeStatus dimensions) {
        private static Status disabled(final String backend) {
            return new Status(false, backend, null, "disabled", null, null, null);
        }
    }

    public record ScopeStatus(String schemaId, long manifestGeneration, boolean manifestClean,
                              long outstandingOperations, long outstandingBytes,
                              long highWaterOperations, long highWaterBytes, long coalescedWrites,
                              long successfulCommits, long commitFailures, long flushFailures,
                              long retries, long backpressureEvents, boolean backpressured,
                              boolean acceptingWrites) {}

    public static final class RegionStore {
        private final CesiumStorageManager manager;
        private final StorageHookAdapter adapter;
        private final StorageNamespace namespace;
        private final String dimension;

        private RegionStore(final CesiumStorageManager manager, final StorageHookAdapter adapter,
                            final StorageNamespace namespace, final String dimension) {
            this.manager = manager;
            this.adapter = adapter;
            this.namespace = namespace;
            this.dimension = dimension;
        }

        public boolean contains(final int x, final int z) {
            return read(x, z).isPresent();
        }

        public Optional<byte[]> read(final int x, final int z) {
            manager.ensureHealthy();
            return manager.joinObserved(adapter.read(namespace, StorageKeyCodecs.dimensionChunk(dimension, x, z)),
                namespace.databaseName() + " read " + dimension + " [" + x + ',' + z + ']');
        }

        public CompletionStage<Void> write(final int x, final int z, final @Nullable byte[] value) {
            manager.ensureHealthy();
            final BinaryKey key = StorageKeyCodecs.dimensionChunk(dimension, x, z);
            final String operation = namespace.databaseName() + ' ' + dimension + " [" + x + ',' + z + ']';
            return manager.submitObserved(() -> value == null ? adapter.delete(namespace, key)
                : adapter.put(namespace, key, value), operation);
        }

        public void flush() {
            manager.ensureHealthy();
            manager.joinObserved(manager.observe(adapter.flushAsync(), namespace.databaseName() + " flush for " + dimension),
                namespace.databaseName() + " flush for " + dimension);
        }
    }

    public static final class SavedDataStore {
        private final CesiumStorageManager manager;
        private final StorageHookAdapter adapter;
        private final String dimension;

        private SavedDataStore(final CesiumStorageManager manager, final StorageHookAdapter adapter,
                               final String dimension) {
            this.manager = manager;
            this.adapter = adapter;
            this.dimension = dimension;
        }

        public Optional<byte[]> read(final String id) {
            Objects.requireNonNull(id, "id");
            manager.ensureHealthy();
            return manager.joinObserved(adapter.read(StorageNamespace.SAVED_DATA,
                StorageKeyCodecs.dimensionString(dimension, id)), "saved data read " + dimension + '/' + id);
        }

        public CompletionStage<Void> write(final String id, final @Nullable byte[] value) {
            Objects.requireNonNull(id, "id");
            manager.ensureHealthy();
            final BinaryKey key = StorageKeyCodecs.dimensionString(dimension, id);
            final String operation = "saved data " + dimension + '/' + id;
            return manager.submitObserved(() -> value == null
                ? adapter.delete(StorageNamespace.SAVED_DATA, key)
                : adapter.put(StorageNamespace.SAVED_DATA, key, value), operation);
        }
    }

    private record Settings(boolean enabled, boolean allowUncleanRecovery,
                            String backend, String rootDirectory,
                            long initialMapSizeBytes, long maximumMapSizeBytes, int maximumReaders,
                            int maximumValueBytes, int compressionLevel, int maximumCommitAttempts,
                            int maxBatchOperations, long backpressureOperationThreshold,
                            long backpressureByteThreshold, Duration initialRetryDelay,
                            Duration maximumRetryDelay, Duration closeTimeout) {
        private static Settings from(final GlobalConfiguration.CesiumStorage config) {
            return new Settings(config.enabled, config.allowUncleanRecovery,
                config.backend.toLowerCase(java.util.Locale.ROOT), config.rootDirectory,
                config.initialMapSizeBytes, config.maximumMapSizeBytes, config.maximumReaders,
                config.maximumValueBytes, config.compressionLevel, config.maximumCommitAttempts,
                config.maxBatchOperations, config.backpressureOperationThreshold,
                config.backpressureByteThreshold, Duration.ofMillis(config.initialRetryDelayMillis),
                Duration.ofMillis(config.maximumRetryDelayMillis),
                Duration.ofSeconds(config.closeTimeoutSeconds));
        }
    }
}
