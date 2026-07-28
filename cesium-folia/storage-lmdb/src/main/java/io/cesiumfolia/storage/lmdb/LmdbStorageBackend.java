package io.cesiumfolia.storage.lmdb;

import io.cesiumfolia.storage.BinaryKey;
import io.cesiumfolia.storage.CommitResult;
import io.cesiumfolia.storage.StorageBackend;
import io.cesiumfolia.storage.StorageBatch;
import io.cesiumfolia.storage.StorageEntry;
import io.cesiumfolia.storage.StorageException;
import io.cesiumfolia.storage.StorageManifest;
import io.cesiumfolia.storage.ScanPage;
import io.cesiumfolia.storage.StorageNamespace;
import io.cesiumfolia.storage.WriteOperation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.lmdbjava.GetOp;
import org.lmdbjava.Txn;

/** LMDB storage backend with one serialized writer and short-lived readers. */
public final class LmdbStorageBackend implements StorageBackend {
    public enum OpenMode {
        READ_WRITE,
        READ_ONLY
    }
    public enum UncleanOpenPolicy {
        REJECT,
        RECOVER
    }
    public static final String FORMAT_ID = "cesium-folia-lmdb";
    public static final int MAX_SCAN_PAGE_SIZE = 4_096;
    public static final long MAX_SCAN_PAGE_LOGICAL_BYTES = 64L << 20;
    private static final String METADATA_DATABASE = "__cesium_folia_metadata";
    private static final byte[] METADATA_GENERATION = "generation".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] METADATA_CLEAN = "clean-shutdown".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    public static final int FORMAT_VERSION = 1;

    public record Options(long initialMapSize, long maximumMapSize, int maximumReaders,
                          int maximumValueBytes, int compressionLevel, Duration closeTimeout,
                          int maximumCommitAttempts) {
        public Options(final long initialMapSize, final long maximumMapSize, final int maximumReaders,
                       final int maximumValueBytes, final int compressionLevel, final Duration closeTimeout) {
            this(initialMapSize, maximumMapSize, maximumReaders, maximumValueBytes, compressionLevel,
                closeTimeout, 3);
        }

        public Options {
            Objects.requireNonNull(closeTimeout, "closeTimeout");
            try {
                closeTimeout.toNanos();
            } catch (final ArithmeticException exception) {
                throw new IllegalArgumentException("closeTimeout is too large", exception);
            }
            if (initialMapSize < 1 || maximumMapSize < initialMapSize || maximumReaders < 1
                || maximumValueBytes < 0 || maximumCommitAttempts < 1
                || closeTimeout.isNegative() || closeTimeout.isZero()) {
                throw new IllegalArgumentException("Invalid LMDB backend options");
            }
        }

        public static Options defaults() {
            return new Options(64L << 20, 8L << 30, 126, 64 << 20, 3, Duration.ofSeconds(30), 3);
        }
    }

    private record PendingCommit(StorageBatch batch, CompletableFuture<CommitResult> result) {}
    private record MetadataState(long generation, boolean cleanShutdown) {}
    private enum ShutdownMode {
        NONE,
        CLEAN,
        CLEAN_COMMITTED,
        ABORT
    }
    interface LifecycleHooks {
        enum QueuedOperation {
            READ,
            FLUSH,
            COMMIT
        }
        enum CloseDurabilityStep {
            INITIAL_SYNC,
            METADATA_TRANSACTION,
            MANIFEST_REPLACEMENT,
            FINAL_SYNC
        }

        LifecycleHooks NONE = new LifecycleHooks() {};

        default void beforeQueuedOperationIo(final QueuedOperation operation) {}
        default void beforeCloseDurabilityIo(final CloseDurabilityStep step) {}
    }


    private final Path root;
    private final StorageLock storageLock;
    private final Options options;
    private final Env<ByteBuffer> environment;
    private final EnumMap<StorageNamespace, Dbi<ByteBuffer>> databases;
    private final ZstdCodec codec;
    private final ExecutorService writer;
    private final Dbi<ByteBuffer> metadataDatabase;
    private final ExecutorService readers;
    private final LifecycleHooks lifecycleHooks;
    private final ReentrantReadWriteLock transactionLock = new ReentrantReadWriteLock(true);
    private final Object queueMonitor = new Object();
    private final ArrayDeque<PendingCommit> pending = new ArrayDeque<>();
    private PendingCommit activeCommit;
    private final Set<CompletableFuture<?>> queuedOperations =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private volatile StorageException terminalFailure;
    private CompletableFuture<Void> closeFuture;
    private ShutdownMode shutdownMode = ShutdownMode.NONE;
    private volatile Throwable abortCause;
    private boolean reportTerminalFailureOnClose;
    private boolean writerScheduled;
    private volatile StorageManifest currentManifest;
    private final OpenMode openMode;
    private final UncleanOpenPolicy uncleanOpenPolicy;
    private volatile boolean recoveredUncleanShutdown;

    public static LmdbStorageBackend open(final Path root, final StorageManifest requestedManifest) {
        return open(root, requestedManifest, Options.defaults(), OpenMode.READ_WRITE,
            UncleanOpenPolicy.REJECT);
    }

    public static LmdbStorageBackend open(final Path root, final StorageManifest requestedManifest,
                                          final Options options) {
        return open(root, requestedManifest, options, OpenMode.READ_WRITE, UncleanOpenPolicy.REJECT);
    }

    public static LmdbStorageBackend open(final Path root, final StorageManifest requestedManifest,
                                          final OpenMode openMode) {
        return open(root, requestedManifest, Options.defaults(), openMode, UncleanOpenPolicy.REJECT);
    }

    public static LmdbStorageBackend open(final Path root, final StorageManifest requestedManifest,
                                          final Options options, final OpenMode openMode) {
        return open(root, requestedManifest, options, openMode, UncleanOpenPolicy.REJECT);
    }

    public static LmdbStorageBackend open(final Path root, final StorageManifest requestedManifest,
                                          final Options options, final OpenMode openMode,
                                          final UncleanOpenPolicy uncleanOpenPolicy) {
        return new LmdbStorageBackend(root, requestedManifest, options, openMode, uncleanOpenPolicy);
    }

    public LmdbStorageBackend(final Path root, final StorageManifest requestedManifest) {
        this(root, requestedManifest, Options.defaults(), OpenMode.READ_WRITE, UncleanOpenPolicy.REJECT);
    }

    public LmdbStorageBackend(final Path root, final StorageManifest requestedManifest, final Options options) {
        this(root, requestedManifest, options, OpenMode.READ_WRITE, UncleanOpenPolicy.REJECT);
    }

    public LmdbStorageBackend(final Path root, final StorageManifest requestedManifest,
                              final Options options, final OpenMode openMode) {
        this(root, requestedManifest, options, openMode, UncleanOpenPolicy.REJECT);
    }

    public LmdbStorageBackend(final Path root, final StorageManifest requestedManifest,
                              final Options options, final OpenMode openMode,
                              final UncleanOpenPolicy uncleanOpenPolicy) {
        this(root, requestedManifest, options, openMode, uncleanOpenPolicy, LifecycleHooks.NONE);
    }

    LmdbStorageBackend(final Path root, final StorageManifest requestedManifest,
                       final Options options, final OpenMode openMode,
                       final UncleanOpenPolicy uncleanOpenPolicy, final LifecycleHooks lifecycleHooks) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Objects.requireNonNull(requestedManifest, "requestedManifest");
        this.options = Objects.requireNonNull(options, "options");
        this.openMode = Objects.requireNonNull(openMode, "openMode");
        this.uncleanOpenPolicy = Objects.requireNonNull(uncleanOpenPolicy, "uncleanOpenPolicy");
        this.lifecycleHooks = Objects.requireNonNull(lifecycleHooks, "lifecycleHooks");
        if (!"zstd".equals(requestedManifest.compression())) {
            throw new StorageException("LMDB backend requires zstd compression");
        }
        try {
            if (openMode == OpenMode.READ_ONLY && !Files.isDirectory(this.root)) {
                throw new IOException("read-only storage root does not exist");
            }
            if (openMode == OpenMode.READ_WRITE) {
                Files.createDirectories(this.root);
            }
        } catch (final IOException exception) {
            throw new StorageException("Cannot access LMDB storage root " + this.root, exception);
        }

        storageLock = StorageLock.acquire(this.root);
        final Path manifestPath = this.root.resolve(ManifestStore.FILE_NAME);
        final boolean manifestExists = Files.exists(manifestPath);
        final boolean dataEnvironmentExists = Files.exists(this.root.resolve("data.mdb"));
        final boolean existingData = dataEnvironmentExists || Files.exists(this.root.resolve("lock.mdb"));
        try {
            if (manifestExists) {
                if (!dataEnvironmentExists) {
                    throw new StorageException("LMDB manifest exists but data environment is missing at "
                        + this.root.resolve("data.mdb"));
                }
                currentManifest = ManifestStore.load(this.root);
                ManifestStore.validateIdentity(requestedManifest, currentManifest);
            } else if (openMode == OpenMode.READ_ONLY) {
                throw new StorageException("Read-only LMDB storage has no manifest at " + manifestPath);
            } else if (existingData) {
                throw new StorageException("Existing LMDB storage has no manifest at " + manifestPath
                    + "; open it through an explicit migration path");
            } else {
                if (!requestedManifest.cleanShutdown()
                    && uncleanOpenPolicy == UncleanOpenPolicy.REJECT) {
                    throw new StorageException("A fresh unclean manifest requires explicit recovery approval");
                }
                currentManifest = requestedManifest;
                ManifestStore.save(this.root, currentManifest);
            }
        } catch (final RuntimeException exception) {
            try {
                storageLock.close();
            } catch (final RuntimeException lockFailure) {
                exception.addSuppressed(lockFailure);
            }
            throw exception;
        }

        final long configuredMapSize = existingData ? options.maximumMapSize() : options.initialMapSize();
        Env<ByteBuffer> opened = null;
        try {
            final EnvFlags[] flags = openMode == OpenMode.READ_ONLY
                ? new EnvFlags[] {EnvFlags.MDB_RDONLY_ENV} : new EnvFlags[0];
            opened = Env.<ByteBuffer>create().setMapSize(configuredMapSize)
                .setMaxDbs(StorageNamespace.values().length + 1).setMaxReaders(options.maximumReaders())
                .open(this.root.toFile(), flags);
            final EnumMap<StorageNamespace, Dbi<ByteBuffer>> openedDatabases =
                new EnumMap<>(StorageNamespace.class);
            for (final StorageNamespace namespace : StorageNamespace.values()) {
                openedDatabases.put(namespace, openMode == OpenMode.READ_ONLY
                    ? opened.openDbi(namespace.databaseName())
                    : opened.openDbi(namespace.databaseName(), org.lmdbjava.DbiFlags.MDB_CREATE));
            }
            final Dbi<ByteBuffer> openedMetadata = openMode == OpenMode.READ_ONLY
                ? opened.openDbi(METADATA_DATABASE)
                : opened.openDbi(METADATA_DATABASE, org.lmdbjava.DbiFlags.MDB_CREATE);
            environment = opened;
            databases = openedDatabases;
            metadataDatabase = openedMetadata;
            codec = new ZstdCodec(options.maximumValueBytes(), options.compressionLevel());
        } catch (final RuntimeException exception) {
            if (opened != null) {
                opened.close();
            }
            try {
                storageLock.close();
            } catch (final RuntimeException lockFailure) {
                exception.addSuppressed(lockFailure);
            }
            throw new StorageException("Cannot open LMDB environment at " + this.root, exception);
        }

        final ThreadFactory writerFactory = Thread.ofPlatform().name("cesium-lmdb-writer-", 0).factory();
        writer = Executors.newSingleThreadExecutor(writerFactory);
        readers = Executors.newFixedThreadPool(Math.min(options.maximumReaders(), 32),
            Thread.ofPlatform().name("cesium-lmdb-reader-", 0).factory());
        try {
            final MetadataState state = readMetadata();
            if (state == null && existingData) {
                throw new StorageException("Existing LMDB storage has no recovery metadata");
            }
            StorageManifest base = currentManifest;
            if (state != null) {
                base = new StorageManifest(base.formatId(), base.formatVersion(), base.minecraftDataVersion(),
                    base.compression(), base.schemaId(), state.generation(), state.cleanShutdown(), base.createdAt());
            }
            if (!base.cleanShutdown()) {
                if (uncleanOpenPolicy == UncleanOpenPolicy.REJECT) {
                    throw new StorageException("LMDB storage was not shut down cleanly; reopen with "
                        + "UncleanOpenPolicy.RECOVER only after explicit recovery approval");
                }
                recoveredUncleanShutdown = true;
            }
            if (openMode == OpenMode.READ_ONLY) {
                currentManifest = base;
            } else {
                currentManifest = state != null && base.cleanShutdown() ? base.nextGeneration(false)
                    : new StorageManifest(base.formatId(), base.formatVersion(), base.minecraftDataVersion(),
                        base.compression(), base.schemaId(), base.generation(), false, base.createdAt());
                writeMetadata(currentManifest.generation(), false);
                ManifestStore.save(this.root, currentManifest);
            }
        } catch (final RuntimeException exception) {
            readers.shutdownNow();
            writer.shutdownNow();
            environment.close();
            try {
                storageLock.close();
            } catch (final RuntimeException lockFailure) {
                exception.addSuppressed(lockFailure);
            }
            throw exception;
        }
    }

    @Override
    public CompletionStage<Optional<byte[]>> read(final StorageNamespace namespace, final BinaryKey key) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        if (!accepting.get()) {
            return CompletableFuture.failedStage(new StorageException("Storage backend is closed"));
        }
        if (key.size() > environment.getMaxKeySize()) {
            return CompletableFuture.failedStage(new StorageException("Key exceeds LMDB maximum size"));
        }
        return submitRead(() -> {
            int attempts = 0;
            while (true) {
                boolean retry;
                transactionLock.readLock().lock();
                try {
                    try (Txn<ByteBuffer> txn = environment.txnRead()) {
                        final ByteBuffer value = databases.get(namespace).get(txn, direct(key.bytes()));
                        return value == null ? Optional.empty() : Optional.of(codec.decompress(copy(value)));
                    } catch (final Dbi.MapResizedException resized) {
                        if (++attempts > 8) {
                            throw resized;
                        }
                        retry = true;
                    }
                } finally {
                    transactionLock.readLock().unlock();
                }
                if (retry) {
                    adoptMapSize();
                }
            }
        });
    }

    @Override
    public CompletionStage<List<StorageEntry>> scan(final StorageNamespace namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return submitRead(() -> {
            int attempts = 0;
            while (true) {
                boolean retry;
                transactionLock.readLock().lock();
                try {
                    try (Txn<ByteBuffer> txn = environment.txnRead();
                         org.lmdbjava.Cursor<ByteBuffer> cursor = databases.get(namespace).openCursor(txn)) {
                        final List<StorageEntry> entries = new ArrayList<>();
                        if (cursor.first()) {
                            do {
                                entries.add(new StorageEntry(new BinaryKey(copy(cursor.key())),
                                    codec.decompress(copy(cursor.val()))));
                            } while (cursor.next());
                        }
                        return List.copyOf(entries);
                    } catch (final Dbi.MapResizedException resized) {
                        if (++attempts > 8) {
                            throw resized;
                        }
                        retry = true;
                    }
                } finally {
                    transactionLock.readLock().unlock();
                }
                if (retry) {
                    adoptMapSize();
                }
            }
        });
    }

    @Override
    public CompletionStage<ScanPage> scanPage(final StorageNamespace namespace,
                                               final BinaryKey afterExclusive, final int limit) {
        return scanPage(namespace, afterExclusive, limit, MAX_SCAN_PAGE_LOGICAL_BYTES);
    }

    CompletionStage<ScanPage> scanPage(final StorageNamespace namespace, final BinaryKey afterExclusive,
                                       final int limit, final long maximumLogicalBytes) {
        Objects.requireNonNull(namespace, "namespace");
        if (limit < 1 || limit > MAX_SCAN_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_SCAN_PAGE_SIZE);
        }
        if (maximumLogicalBytes < 1) {
            throw new IllegalArgumentException("maximumLogicalBytes must be positive");
        }
        if (afterExclusive != null && afterExclusive.size() > environment.getMaxKeySize()) {
            return CompletableFuture.failedStage(new StorageException("Key exceeds LMDB maximum size"));
        }
        return submitRead(() -> {
            int attempts = 0;
            while (true) {
                boolean retry;
                transactionLock.readLock().lock();
                try {
                    try (Txn<ByteBuffer> txn = environment.txnRead();
                         org.lmdbjava.Cursor<ByteBuffer> cursor = databases.get(namespace).openCursor(txn)) {
                        boolean positioned;
                        if (afterExclusive == null) {
                            positioned = cursor.first();
                        } else {
                            positioned = cursor.get(direct(afterExclusive.bytes()), GetOp.MDB_SET_RANGE);
                            if (positioned && afterExclusive.equals(new BinaryKey(copy(cursor.key())))) {
                                positioned = cursor.next();
                            }
                        }
                        final List<StorageEntry> entries = new ArrayList<>(limit);
                        long logicalBytes = 0;
                        while (positioned && entries.size() < limit) {
                            final byte[] key = copy(cursor.key());
                            final byte[] value = codec.decompress(copy(cursor.val()));
                            final long candidateBytes = (long) key.length + value.length;
                            if (!entries.isEmpty() && candidateBytes > maximumLogicalBytes - logicalBytes) {
                                break;
                            }
                            entries.add(new StorageEntry(new BinaryKey(key), value));
                            logicalBytes += candidateBytes;
                            positioned = cursor.next();
                        }
                        final BinaryKey nextCursor = entries.isEmpty() ? null : entries.getLast().key();
                        return new ScanPage(entries, nextCursor, positioned);
                    } catch (final Dbi.MapResizedException resized) {
                        if (++attempts > 8) {
                            throw resized;
                        }
                        retry = true;
                    }
                } finally {
                    transactionLock.readLock().unlock();
                }
                if (retry) {
                    adoptMapSize();
                }
            }
        });
    }

    @Override
    public CompletionStage<Long> count(final StorageNamespace namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return submitRead(() -> {
            int attempts = 0;
            while (true) {
                boolean retry;
                transactionLock.readLock().lock();
                try {
                    try (Txn<ByteBuffer> txn = environment.txnRead()) {
                        return databases.get(namespace).stat(txn).entries;
                    } catch (final Dbi.MapResizedException resized) {
                        if (++attempts > 8) {
                            throw resized;
                        }
                        retry = true;
                    }
                } finally {
                    transactionLock.readLock().unlock();
                }
                if (retry) {
                    adoptMapSize();
                }
            }
        });
    }

    @Override
    public CompletionStage<CommitResult> commit(final StorageBatch batch) {
        Objects.requireNonNull(batch, "batch");
        final CompletableFuture<CommitResult> result = new CompletableFuture<>();
        synchronized (queueMonitor) {
            if (openMode == OpenMode.READ_ONLY) {
                result.completeExceptionally(new StorageException("Read-only LMDB backend cannot commit"));
            } else if (terminalFailure != null) {
                result.completeExceptionally(terminalFailure);
            } else if (!accepting.get()) {
                result.completeExceptionally(new StorageException("Storage backend is closing"));
            } else {
                final StorageBatch copy = batch.copy();
                if (copy.isEmpty()) {
                    result.complete(new CommitResult(currentManifest.generation(), 0, Instant.now()));
                } else {
                    pending.addLast(new PendingCommit(copy, result));
                    scheduleWriterLocked();
                }
            }
        }
        return result;
    }

    @Override
    public CompletionStage<Void> flush() {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (queueMonitor) {
            if (!accepting.get()) {
                result.completeExceptionally(new StorageException("Storage backend is closing"));
                return result;
            }
            if (terminalFailure != null) {
                result.completeExceptionally(terminalFailure);
                return result;
            }
            if (openMode == OpenMode.READ_ONLY) {
                result.complete(null);
                return result;
            }
            queuedOperations.add(result);
            try {
                writer.execute(() -> {
                    if (!beginQueuedOperation(result)) {
                        return;
                    }
                    try {
                        lifecycleHooks.beforeQueuedOperationIo(LifecycleHooks.QueuedOperation.FLUSH);
                        final StorageException operationFailure = drainCommits();
                        if (operationFailure != null) {
                            throw operationFailure;
                        }
                        environment.sync(true);
                        result.complete(null);
                    } catch (final Throwable exception) {
                        result.completeExceptionally(asStorageException("LMDB flush failed", exception));
                    }
                });
            } catch (final RuntimeException exception) {
                queuedOperations.remove(result);
                result.completeExceptionally(asStorageException("Cannot schedule LMDB flush", exception));
            }
        }
        return result;
    }

    @Override
    public StorageManifest manifest() {
        return currentManifest;
    }
    public boolean recoveredUncleanShutdown() {
        return recoveredUncleanShutdown;
    }


    @Override
    public CompletionStage<Void> closeAsync() {
        synchronized (queueMonitor) {
            if (closeFuture == null) {
                closeFuture = new CompletableFuture<>();
                accepting.set(false);
                if (terminalFailure == null) {
                    shutdownMode = ShutdownMode.CLEAN;
                } else {
                    shutdownMode = ShutdownMode.ABORT;
                    abortCause = terminalFailure;
                    reportTerminalFailureOnClose = true;
                    failPendingLocked(terminalFailure);
                    failActiveCommitLocked(terminalFailure);
                    failQueuedOperationsLocked(terminalFailure);
                }
                scheduleCloseLocked();
            }
            return closeFuture;
        }
    }

    @Override
    public CompletionStage<Void> abortAsync(final Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        synchronized (queueMonitor) {
            if (closeFuture == null) {
                closeFuture = new CompletableFuture<>();
                accepting.set(false);
                shutdownMode = ShutdownMode.ABORT;
                abortCause = cause;
                failPendingLocked(cause);
                failActiveCommitLocked(cause);
                failQueuedOperationsLocked(cause);
                scheduleCloseLocked();
            } else if (!closeFuture.isDone() && shutdownMode == ShutdownMode.CLEAN) {
                shutdownMode = ShutdownMode.ABORT;
                abortCause = cause;
                failPendingLocked(cause);
                failActiveCommitLocked(cause);
                failQueuedOperationsLocked(cause);
            }
            return closeFuture;
        }
    }

    private void scheduleCloseLocked() {
        try {
            writer.execute(this::closeOnWriter);
        } catch (final RuntimeException exception) {
            closeFuture.completeExceptionally(asStorageException("Cannot schedule LMDB close", exception));
        }
    }


    private <T> CompletionStage<T> submitRead(final ReadTask<T> task) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (queueMonitor) {
            if (!accepting.get()) {
                result.completeExceptionally(new StorageException("Storage backend is closed"));
                return result;
            }
            queuedOperations.add(result);
            try {
                readers.execute(() -> {
                    if (!beginQueuedOperation(result)) {
                        return;
                    }
                    try {
                        lifecycleHooks.beforeQueuedOperationIo(LifecycleHooks.QueuedOperation.READ);
                        result.complete(task.run());
                    } catch (final Throwable exception) {
                        result.completeExceptionally(asStorageException("LMDB read failed", exception));
                    }
                });
            } catch (final RuntimeException exception) {
                queuedOperations.remove(result);
                result.completeExceptionally(asStorageException("Cannot schedule LMDB read", exception));
            }
        }
        return result;
    }

    private boolean beginQueuedOperation(final CompletableFuture<?> result) {
        synchronized (queueMonitor) {
            queuedOperations.remove(result);
            if (result.isDone() || shutdownMode == ShutdownMode.ABORT) {
                if (!result.isDone()) {
                    result.completeExceptionally(abortCause == null
                        ? new StorageException("Storage backend aborted") : abortCause);
                }
                return false;
            }
            return true;
        }
    }

    private void failQueuedOperationsLocked(final Throwable cause) {
        for (final CompletableFuture<?> operation : queuedOperations) {
            operation.completeExceptionally(cause);
        }
        queuedOperations.clear();
    }

    private MetadataState readMetadata() {
        transactionLock.readLock().lock();
        try {
            try (Txn<ByteBuffer> txn = environment.txnRead()) {
                final ByteBuffer generation = metadataDatabase.get(txn, direct(METADATA_GENERATION));
                final byte[] generationBytes = generation == null ? null : copy(generation);
                final ByteBuffer clean = metadataDatabase.get(txn, direct(METADATA_CLEAN));
                final byte[] cleanBytes = clean == null ? null : copy(clean);
                if (generationBytes == null && cleanBytes == null) {
                    return null;
                }
                if (generationBytes == null || cleanBytes == null) {
                    throw new StorageException("Incomplete LMDB metadata");
                }
                if (generationBytes.length != Long.BYTES || cleanBytes.length != 1
                    || (cleanBytes[0] != 0 && cleanBytes[0] != 1)) {
                    throw new StorageException("Invalid LMDB metadata");
                }
                return new MetadataState(ByteBuffer.wrap(generationBytes).order(ByteOrder.BIG_ENDIAN).getLong(),
                    cleanBytes[0] == 1);
            }
        } finally {
            transactionLock.readLock().unlock();
        }
    }

    private void writeMetadata(final long generation, final boolean cleanShutdown) {
        transactionLock.writeLock().lock();
        try {
            int attempts = 0;
            while (true) {
                try (Txn<ByteBuffer> txn = environment.txnWrite()) {
                    metadataDatabase.put(txn, direct(METADATA_GENERATION), direct(longBytes(generation)));
                    metadataDatabase.put(txn, direct(METADATA_CLEAN), direct(new byte[] {cleanShutdown ? (byte) 1 : (byte) 0}));
                    txn.commit();
                    return;
                } catch (final Env.MapFullException mapFull) {
                    if (++attempts > 8 || !growMap()) {
                        throw mapFull;
                    }
                } catch (final Dbi.MapResizedException resized) {
                    if (++attempts > 8) {
                        throw resized;
                    }
                    environment.setMapSize(0);
                }
            }
        } finally {
            transactionLock.writeLock().unlock();
        }
    }

    private static byte[] longBytes(final long value) {
        final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(value);
        return buffer.array();
    }

    private void scheduleWriterLocked() {
        if (!writerScheduled) {
            writerScheduled = true;
            try {
                writer.execute(this::drainCommits);
            } catch (final RuntimeException exception) {
                final StorageException failure = asStorageException("Cannot schedule LMDB writer", exception);
                terminalFailure = failure;
                accepting.set(false);
                writerScheduled = false;
                failPendingLocked(failure);
                failQueuedOperationsLocked(failure);
            }
        }
    }

    private StorageException drainCommits() {
        while (true) {
            final PendingCommit pendingCommit;
            synchronized (queueMonitor) {
                if (terminalFailure != null) {
                    writerScheduled = false;
                    failPendingLocked(terminalFailure);
                    failQueuedOperationsLocked(terminalFailure);
                    return terminalFailure;
                }
                if (shutdownMode == ShutdownMode.ABORT) {
                    writerScheduled = false;
                    return null;
                }
                pendingCommit = pending.pollFirst();
                if (pendingCommit == null) {
                    writerScheduled = false;
                    return null;
                }
                activeCommit = pendingCommit;
            }
            int attempts = 0;
            while (true) {
                synchronized (queueMonitor) {
                    if (shutdownMode == ShutdownMode.ABORT) {
                        activeCommit = null;
                        pendingCommit.result().completeExceptionally(abortCause == null
                            ? new StorageException("Storage backend aborted") : abortCause);
                        writerScheduled = false;
                        return null;
                    }
                }
                try {
                    lifecycleHooks.beforeQueuedOperationIo(LifecycleHooks.QueuedOperation.COMMIT);
                    final long generation = executeWrite(pendingCommit.batch().operations());
                    synchronized (queueMonitor) {
                        activeCommit = null;
                        if (shutdownMode == ShutdownMode.ABORT) {
                            pendingCommit.result().completeExceptionally(abortCause == null
                                ? new StorageException("Storage backend aborted") : abortCause);
                            writerScheduled = false;
                            return null;
                        }
                        pendingCommit.result().complete(new CommitResult(generation,
                            pendingCommit.batch().size(), Instant.now()));
                    }
                    break;
                } catch (final Throwable exception) {
                    synchronized (queueMonitor) {
                        if (shutdownMode == ShutdownMode.ABORT) {
                            activeCommit = null;
                            pendingCommit.result().completeExceptionally(abortCause == null
                                ? new StorageException("Storage backend aborted") : abortCause);
                            writerScheduled = false;
                            return null;
                        }
                    }
                    if (retryableCommitFailure(exception)
                        && ++attempts < options.maximumCommitAttempts()) {
                        continue;
                    }
                    final StorageException failure = asStorageException("LMDB commit failed", exception);
                    synchronized (queueMonitor) {
                        activeCommit = null;
                        terminalFailure = failure;
                        accepting.set(false);
                        pendingCommit.result().completeExceptionally(failure);
                        writerScheduled = false;
                        failPendingLocked(failure);
                        failQueuedOperationsLocked(failure);
                    }
                    return failure;
                }
            }
        }
    }

    private void failActiveCommitLocked(final Throwable failure) {
        if (activeCommit != null) {
            activeCommit.result().completeExceptionally(failure);
        }
    }

    private void failPendingLocked(final Throwable failure) {
        PendingCommit pendingCommit;
        while ((pendingCommit = pending.pollFirst()) != null) {
            pendingCommit.result().completeExceptionally(failure);
        }
    }
    private long executeWrite(final Iterable<WriteOperation> operations) {
        final StorageManifest committed = currentManifest.nextGeneration(false);
        int attempts = 0;
        while (true) {
            Env.MapFullException mapFullFailure = null;
            boolean resized = false;
            boolean committedTransaction = false;
            transactionLock.readLock().lock();
            try {
                try (Txn<ByteBuffer> txn = environment.txnWrite()) {
                    for (final WriteOperation operation : operations) {
                        if (operation.key().size() > environment.getMaxKeySize()) {
                            throw new StorageException("Key exceeds LMDB maximum size");
                        }
                        final Dbi<ByteBuffer> dbi = databases.get(operation.namespace());
                        final ByteBuffer key = direct(operation.key().bytes());
                        if (operation.kind() == WriteOperation.Kind.PUT) {
                            dbi.put(txn, key, direct(codec.compress(operation.value())));
                        } else {
                            dbi.delete(txn, key);
                        }
                    }
                    metadataDatabase.put(txn, direct(METADATA_GENERATION), direct(longBytes(committed.generation())));
                    metadataDatabase.put(txn, direct(METADATA_CLEAN), direct(new byte[] {0}));
                    txn.commit();
                    committedTransaction = true;
                } catch (final Env.MapFullException mapFull) {
                    if (++attempts > 32) {
                        throw mapFull;
                    }
                    mapFullFailure = mapFull;
                } catch (final Dbi.MapResizedException mapResized) {
                    if (++attempts > 32) {
                        throw mapResized;
                    }
                    resized = true;
                }
            } finally {
                transactionLock.readLock().unlock();
            }
            if (committedTransaction) {
                currentManifest = committed;
                ManifestStore.save(root, committed);
                return committed.generation();
            }
            if (mapFullFailure != null) {
                transactionLock.writeLock().lock();
                try {
                    if (!growMap()) {
                        throw mapFullFailure;
                    }
                } finally {
                    transactionLock.writeLock().unlock();
                }
            } else if (resized) {
                adoptMapSize();
            }
        }
    }

    private boolean growMap() {
        final long current = environment.info().mapSize;
        if (current >= options.maximumMapSize()) {
            return false;
        }
        final long doubled = current > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : current * 2;
        final long minimumGrowth = current > Long.MAX_VALUE - (64L << 20)
            ? Long.MAX_VALUE : current + (64L << 20);
        final long requested = Math.max(doubled, minimumGrowth);
        environment.setMapSize(Math.min(options.maximumMapSize(), requested));
        return true;
    }
    private void adoptMapSize() {
        transactionLock.writeLock().lock();
        try {
            environment.setMapSize(0);
        } finally {
            transactionLock.writeLock().unlock();
        }
    }

    private void closeOnWriter() {
        StorageException failure = null;
        boolean readersStopped = false;
        boolean environmentClosed = false;
        try {
            final boolean abortingAtStart;
            synchronized (queueMonitor) {
                abortingAtStart = shutdownMode == ShutdownMode.ABORT;
            }
            if (openMode == OpenMode.READ_WRITE && !abortingAtStart) {
                failure = drainCommits();
                if (failure != null) {
                    synchronized (queueMonitor) {
                        shutdownMode = ShutdownMode.ABORT;
                        if (abortCause == null) {
                            abortCause = failure;
                        }
                    }
                }
            }
            readersStopped = stopReaders();
            if (!readersStopped && failure == null) {
                failure = new StorageException("Timed out waiting for LMDB readers to close");
            }
            if (failure == null && openMode == OpenMode.READ_WRITE) {
                final boolean cleanRequested;
                synchronized (queueMonitor) {
                    cleanRequested = shutdownMode == ShutdownMode.CLEAN;
                }
                if (cleanRequested) {
                    lifecycleHooks.beforeCloseDurabilityIo(LifecycleHooks.CloseDurabilityStep.INITIAL_SYNC);
                    environment.sync(true);
                    final StorageManifest clean = currentManifest.nextGeneration(true);
                    lifecycleHooks.beforeCloseDurabilityIo(
                        LifecycleHooks.CloseDurabilityStep.METADATA_TRANSACTION);
                    writeMetadata(clean.generation(), true);
                    currentManifest = clean;
                    lifecycleHooks.beforeCloseDurabilityIo(
                        LifecycleHooks.CloseDurabilityStep.MANIFEST_REPLACEMENT);
                    ManifestStore.save(root, clean);
                    lifecycleHooks.beforeCloseDurabilityIo(LifecycleHooks.CloseDurabilityStep.FINAL_SYNC);
                    environment.sync(true);

                    final boolean cleanCommitted;
                    synchronized (queueMonitor) {
                        cleanCommitted = shutdownMode == ShutdownMode.CLEAN;
                        if (cleanCommitted) {
                            shutdownMode = ShutdownMode.CLEAN_COMMITTED;
                        }
                    }
                    if (!cleanCommitted) {
                        persistUncleanState();
                    }
                }
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure = appendFailure(failure,
                new StorageException("Interrupted while closing LMDB readers", exception));
            readers.shutdownNow();
        } catch (final Throwable exception) {
            failure = appendFailure(failure, asStorageException("LMDB close failed", exception));
            readers.shutdownNow();
        } finally {
            if (failure != null && openMode == OpenMode.READ_WRITE) {
                try {
                    persistUncleanState();
                } catch (final Throwable repairFailure) {
                    failure.addSuppressed(repairFailure);
                }
            }
            if (readersStopped || readers.isTerminated()) {
                try {
                    environment.close();
                    environmentClosed = true;
                } catch (final Throwable exception) {
                    failure = appendFailure(failure,
                        asStorageException("LMDB environment close failed", exception));
                }
            }
            if (environmentClosed) {
                try {
                    storageLock.close();
                } catch (final RuntimeException exception) {
                    failure = appendFailure(failure,
                        asStorageException("LMDB storage lock release failed", exception));
                }
            }
            writer.shutdown();
            if (failure == null && reportTerminalFailureOnClose) {
                failure = terminalFailure;
            }
            if (failure == null) {
                closeFuture.complete(null);
            } else {
                closeFuture.completeExceptionally(failure);
            }
        }
    }

    private void persistUncleanState() {
        final StorageManifest manifest = currentManifest;
        final StorageManifest unclean = new StorageManifest(manifest.formatId(), manifest.formatVersion(),
            manifest.minecraftDataVersion(), manifest.compression(), manifest.schemaId(),
            manifest.generation(), false, manifest.createdAt());
        writeMetadata(unclean.generation(), false);
        ManifestStore.save(root, unclean);
        currentManifest = unclean;
        environment.sync(true);
    }

    private static StorageException appendFailure(final StorageException current,
                                                  final StorageException addition) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private boolean stopReaders() throws InterruptedException {
        final long timeoutNanos = options.closeTimeout().toNanos();
        final long now = System.nanoTime();
        final long deadline = now > Long.MAX_VALUE - timeoutNanos
            ? Long.MAX_VALUE : now + timeoutNanos;
        final long gracefulNanos = timeoutNanos / 2;
        final long gracefulDeadline = now > Long.MAX_VALUE - gracefulNanos
            ? Long.MAX_VALUE : now + gracefulNanos;
        readers.shutdown();
        if (awaitReadersUntil(gracefulDeadline)) {
            return true;
        }
        readers.shutdownNow();
        return awaitReadersUntil(deadline);
    }

    private boolean awaitReadersUntil(final long deadline) throws InterruptedException {
        if (readers.isTerminated()) {
            return true;
        }
        final long remaining = deadline - System.nanoTime();
        return remaining > 0 && readers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    private static ByteBuffer direct(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put(bytes).flip();
        return buffer;
    }

    private static byte[] copy(final ByteBuffer value) {
        final ByteBuffer duplicate = value.duplicate();
        final byte[] result = new byte[duplicate.remaining()];
        duplicate.get(result);
        return result;
    }

    private static boolean retryableCommitFailure(final Throwable exception) {
        final Throwable cause = exception instanceof java.util.concurrent.CompletionException completion
            && completion.getCause() != null ? completion.getCause() : exception;
        return !(cause instanceof Error) && !(cause instanceof StorageException)
            && !(cause instanceof IllegalArgumentException) && !(cause instanceof NullPointerException);
    }

    private static StorageException asStorageException(final String message, final Throwable exception) {
        return exception instanceof StorageException storageException
            ? storageException : new StorageException(message, exception);
    }

    @FunctionalInterface
    private interface ReadTask<T> {
        T run();
    }
}
