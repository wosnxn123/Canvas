package io.cesiumfolia.folia;

import io.cesiumfolia.storage.BinaryKey;
import io.cesiumfolia.storage.CommitResult;
import io.cesiumfolia.storage.StorageBackend;
import io.cesiumfolia.storage.StorageBatch;
import io.cesiumfolia.storage.StorageNamespace;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bounded, non-blocking intake queue and asynchronous, retrying commit pump for one backend.
 * Enqueue methods copy immutable input and append to an in-memory queue; writes for the same
 * key use latest-wins coalescing, and each superseded completion represents that final value.
 * Backend I/O is owned by a dedicated invocation thread and is watchdog-bounded.
 */
public final class CommitPump implements AutoCloseable {
    private sealed interface Command permits WriteCommand, BarrierCommand {}

    private record WriteKey(StorageNamespace namespace, BinaryKey key) {}

    private static final class WriteCommand implements Command {
        private final WriteKey key;
        private final boolean delete;
        private byte[] value;
        private final CompletableFuture<Void> completion;
        private long retainedBytes;

        private WriteCommand(
            final StorageNamespace namespace,
            final BinaryKey key,
            final byte[] value,
            final CompletableFuture<Void> completion
        ) {
            this.key = new WriteKey(namespace, key);
            this.delete = value == null;
            this.value = value;
            this.completion = completion;
            this.retainedBytes = value == null ? 0 : value.length;
        }

        private long releaseRetainedValue() {
            final long released = retainedBytes;
            value = null;
            retainedBytes = 0;
            return released;
        }
    }

    private static final class PendingWrite {
        private WriteCommand latest;
        private final List<WriteCommand> waiters = new ArrayList<>();

        private PendingWrite(final WriteCommand command) {
            latest = command;
            waiters.add(command);
        }

        private long supersede(final WriteCommand command) {
            final long released = latest.releaseRetainedValue();
            latest = command;
            waiters.add(command);
            return released;
        }
    }

    static final class RetainedPut {
        private final byte[] snapshot;
        private final CompletionStage<Void> completion;

        private RetainedPut(final byte[] snapshot, final CompletionStage<Void> completion) {
            this.snapshot = snapshot;
            this.completion = completion;
        }

        byte[] snapshot() {
            return snapshot;
        }

        CompletionStage<Void> completion() {
            return completion;
        }
    }

    private record BarrierCommand(CompletableFuture<Void> completion, boolean closes) implements Command {}

    private enum ActiveOperation {
        COMMIT,
        FLUSH,
        CLOSE,
        ABORT
    }
    private final StorageBackend backend;
    private final CommitPumpConfig config;
    private final ScheduledExecutorService pumpExecutor;
    private final ExecutorService backendExecutor;
    private final ExecutorService callbackExecutor;
    private final ExecutorService emergencyExecutor;
    private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<WriteKey, PendingWrite> pending = new LinkedHashMap<>();
    private final Object intakeLock = new Object();
    private final Object metricsLock = new Object();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean executorsShutdown = new AtomicBoolean();
    private final AtomicBoolean abortCleanupFinished = new AtomicBoolean();
    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> abortFuture = new AtomicReference<>();

    private final AtomicLong outstandingOperations = new AtomicLong();
    private final AtomicLong outstandingBytes = new AtomicLong();
    private final AtomicLong highWaterOperations = new AtomicLong();
    private final AtomicLong highWaterBytes = new AtomicLong();
    private final AtomicLong coalescedWrites = new AtomicLong();
    private final AtomicLong successfulCommits = new AtomicLong();
    private final AtomicLong commitFailures = new AtomicLong();
    private final AtomicLong flushFailures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong backpressureEvents = new AtomicLong();

    /* The following state is accessed only by the pump thread. */
    private BarrierCommand activeBarrier;
    private ActiveOperation activeOperation;
    private List<Map.Entry<WriteKey, PendingWrite>> activeBatch;
    private Duration retryDelay;
    private int retryAttempts;
    private long retryNotBeforeNanos;
    private boolean backendCloseStarted;
    private ScheduledFuture<?> closeTimeoutTask;
    private ScheduledFuture<?> activeTimeoutTask;

    private volatile Throwable shutdownCause;
    public CommitPump(final StorageBackend backend, final String name) {
        this(backend, name, CommitPumpConfig.defaults());
    }

    public CommitPump(final StorageBackend backend, final String name, final CommitPumpConfig config) {
        this.backend = Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.config = Objects.requireNonNull(config, "config");
        this.retryDelay = config.initialRetryDelay();
        this.pumpExecutor = Executors.newSingleThreadScheduledExecutor(daemonFactory("cesium-pump-" + name));
        this.backendExecutor = Executors.newSingleThreadExecutor(daemonFactory("cesium-backend-" + name));
        this.callbackExecutor = Executors.newSingleThreadExecutor(daemonFactory("cesium-callback-" + name));
        this.emergencyExecutor = Executors.newSingleThreadExecutor(daemonFactory("cesium-emergency-" + name));
    }

    public CompletionStage<Void> enqueuePut(
        final StorageNamespace namespace,
        final BinaryKey key,
        final byte[] immutableSnapshot
    ) {
        return enqueueRetainedPut(namespace, key, immutableSnapshot).completion();
    }

    /** Returns the pump-owned defensive snapshot so the adapter can share it without another copy. */
    RetainedPut enqueueRetainedPut(final StorageNamespace namespace, final BinaryKey key,
                                   final byte[] immutableSnapshot) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(immutableSnapshot, "immutableSnapshot");
        final WriteCommand command;
        final byte[] retainedSnapshot;
        synchronized (intakeLock) {
            requireAccepting();
            reserveEnqueue(immutableSnapshot.length);
            try {
                retainedSnapshot = immutableSnapshot.clone();
                command = new WriteCommand(namespace, key, retainedSnapshot, new CompletableFuture<>());
                commands.add(command);
            } catch (final RuntimeException | Error failure) {
                rollbackReservation(immutableSnapshot.length);
                throw failure;
            }
        }
        schedulePump();
        return new RetainedPut(retainedSnapshot, command.completion);
    }

    public CompletionStage<Void> enqueueDelete(final StorageNamespace namespace, final BinaryKey key) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        final WriteCommand command;
        synchronized (intakeLock) {
            requireAccepting();
            reserveEnqueue(0);
            try {
                command = new WriteCommand(namespace, key, null, new CompletableFuture<>());
                commands.add(command);
            } catch (final RuntimeException | Error failure) {
                rollbackReservation(0);
                throw failure;
            }
        }
        schedulePump();
        return command.completion;
    }

    private void requireAccepting() {
        if (!accepting.get()) {
            throw new IllegalStateException("Commit pump is closing and no longer accepts writes");
        }
    }

    /** Completes after all earlier enqueues are represented by durable latest values and flush succeeds. */
    public CompletionStage<Void> flushAsync() {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (intakeLock) {
            if (!accepting.get()) {
                final CompletableFuture<Void> closing = closeFuture.get();
                return closing == null ? CompletableFuture.failedFuture(
                    new IllegalStateException("Commit pump is closing")) : closing;
            }
            commands.add(new BarrierCommand(result, false));
        }
        schedulePump();
        return result;
    }

    /**
     * Atomically stops intake, drains and retries earlier writes, flushes, and then closes the backend.
     * The returned future fails if the configured timeout expires; it never reports a partial drain as success.
     */
    public CompletionStage<Void> closeAsync() {
        final CompletableFuture<Void> existing = closeFuture.get();
        if (existing != null) {
            return existing;
        }
        final Throwable failure = terminalFailure.get();
        if (failure != null) {
            beginAbort(failure);
            return closeFuture.get();
        }

        final CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (intakeLock) {
            final CompletableFuture<Void> raced = closeFuture.get();
            if (raced != null) {
                return raced;
            }
            closeFuture.set(result);
            accepting.set(false);
            commands.add(new BarrierCommand(result, true));
        }
        try {
            closeTimeoutTask = pumpExecutor.schedule(
                () -> timeoutClose(result),
                config.closeTimeout().toNanos(),
                TimeUnit.NANOSECONDS
            );
        } catch (final RejectedExecutionException rejected) {
            enterTerminalFailure(rejected);
        }
        schedulePump();
        return result;
    }

    public CompletionStage<Void> abortAsync() {
        return abortAsync(new IllegalStateException("Commit pump aborted"));
    }

    public CompletionStage<Void> abortAsync(final Throwable cause) {
        beginAbort(Objects.requireNonNull(cause, "cause"));
        return abortFuture.get();
    }

    private void beginAbort(final Throwable cause) {
        synchronized (intakeLock) {
            if (terminal.get()) {
                completeAbortAfterTerminal();
                return;
            }
            if (closeFuture.get() == null) {
                closeFuture.set(new CompletableFuture<>());
            }
            if (abortFuture.get() == null) {
                abortFuture.set(new CompletableFuture<>());
            }
            if (!terminal.compareAndSet(false, true)) {
                completeAbortAfterTerminal();
                return;
            }
            terminalFailure.compareAndSet(null, cause);
            accepting.set(false);
            shutdownCause = terminalFailure.get();
        }
        final Runnable transition = this::transitionToAbort;
        try {
            pumpExecutor.execute(transition);
        } catch (final RejectedExecutionException rejected) {
            transition.run();
        }
    }

    private void completeAbortAfterTerminal() {
        CompletableFuture<Void> completed = abortFuture.get();
        if (completed != null && shutdownCause != null) {
            return;
        }
        if (completed == null) {
            completed = new CompletableFuture<>();
            abortFuture.set(completed);
        }
        final Throwable existingFailure = terminalFailure.get();
        if (existingFailure == null) {
            completed.complete(null);
        } else {
            completed.completeExceptionally(existingFailure);
        }
    }

    private void transitionToAbort() {
        cancelCloseTimeout();
        cancelActiveTimeout();
        failOutstanding(shutdownCause);
        if (activeOperation == null && !backendCloseStarted) {
            startBackendAbort();
        } else {
            startEmergencyCleanup();
        }
    }


    public BackpressureMetrics metrics() {
        final long operations;
        final long bytes;
        final long highOperations;
        final long highBytes;
        synchronized (metricsLock) {
            operations = outstandingOperations.get();
            bytes = outstandingBytes.get();
            highOperations = highWaterOperations.get();
            highBytes = highWaterBytes.get();
        }
        return new BackpressureMetrics(
            operations,
            bytes,
            highOperations,
            highBytes,
            coalescedWrites.get(),
            successfulCommits.get(),
            commitFailures.get(),
            flushFailures.get(),
            retries.get(),
            backpressureEvents.get(),
            exceedsThreshold(operations, bytes),
            accepting.get()
        );
    }

    private void schedulePump() {
        if (!terminal.get() && scheduled.compareAndSet(false, true)) {
            try {
                pumpExecutor.execute(this::runPump);
            } catch (final RejectedExecutionException rejected) {
                scheduled.set(false);
                enterTerminalFailure(rejected);
            }
        }
    }

    private void runPump() {
        scheduled.set(false);
        if (terminal.get() || activeOperation != null) {
            return;
        }
        if (retryNotBeforeNanos != 0L) {
            if (System.nanoTime() < retryNotBeforeNanos) {
                return;
            }
            retryNotBeforeNanos = 0L;
        }

        collectCommands();
        if (!pending.isEmpty()) {
            commitNextBatch();
            return;
        }
        if (activeBarrier != null) {
            flushBarrier();
            return;
        }

        if (!commands.isEmpty()) {
            schedulePump();
        }
    }

    private void collectCommands() {
        if (activeBarrier != null) {
            return;
        }
        Command command;
        while ((command = commands.poll()) != null) {
            if (command instanceof BarrierCommand barrier) {
                activeBarrier = barrier;
                return;
            }
            merge((WriteCommand) command);
        }
    }

    private void merge(final WriteCommand command) {
        final PendingWrite current = pending.get(command.key);
        if (current == null) {
            pending.put(command.key, new PendingWrite(command));
        } else {
            releaseRetainedBytes(current.supersede(command));
            coalescedWrites.incrementAndGet();
        }
    }

    private void commitNextBatch() {
        final List<Map.Entry<WriteKey, PendingWrite>> batchEntries = new ArrayList<>(
            Math.min(pending.size(), config.maxBatchOperations())
        );
        final StorageBatch batch = new StorageBatch();
        final var iterator = pending.entrySet().iterator();
        while (iterator.hasNext() && batchEntries.size() < config.maxBatchOperations()) {
            final Map.Entry<WriteKey, PendingWrite> entry = iterator.next();
            final WriteCommand latest = entry.getValue().latest;
            if (latest.delete) {
                batch.delete(entry.getKey().namespace(), entry.getKey().key());
            } else {
                batch.putOwned(entry.getKey().namespace(), entry.getKey().key(), latest.value);
            }
            batchEntries.add(Map.entry(entry.getKey(), entry.getValue()));
            iterator.remove();
        }

        activeOperation = ActiveOperation.COMMIT;
        activeBatch = batchEntries;
        activeTimeoutTask = scheduleActiveTimeout(ActiveOperation.COMMIT);
        this.<CommitResult>invoke(() -> backend.commit(batch)).whenComplete((commit, failure) -> dispatchToPump(
            () -> completeCommit(batchEntries, commit, failure)
        ));
    }
    private void completeCommit(
        final List<Map.Entry<WriteKey, PendingWrite>> batchEntries,
        final CommitResult commit,
        Throwable failure
    ) {
        if (activeOperation != ActiveOperation.COMMIT) {
            return;
        }
        cancelActiveTimeout();
        activeOperation = null;
        if (terminal.get()) {
            return;
        }
        if (failure == null && !validCommit(commit, batchEntries.size())) {
            failure = new IllegalStateException("Backend returned an invalid commit result");
        }
        if (failure == null) {
            activeBatch = null;
            retryDelay = config.initialRetryDelay();
            retryAttempts = 0;
            successfulCommits.incrementAndGet();
            for (final Map.Entry<WriteKey, PendingWrite> entry : batchEntries) {
                completeWrites(entry.getValue(), null);
            }
            schedulePump();
        } else {
            commitFailures.incrementAndGet();
            if (!retryable(failure)) {
                enterTerminalFailure(failure);
            } else {
                activeBatch = null;
                restoreFailedBatch(batchEntries);
                scheduleRetry(failure);
            }
        }
    }

    private static boolean validCommit(final CommitResult commit, final int expectedOperations) {
        return commit != null && commit.operationCount() == expectedOperations
            && commit.generation() >= 0 && commit.committedAt() != null;
    }

    private void restoreFailedBatch(final List<Map.Entry<WriteKey, PendingWrite>> failedEntries) {
        final LinkedHashMap<WriteKey, PendingWrite> restored = new LinkedHashMap<>();
        for (final Map.Entry<WriteKey, PendingWrite> entry : failedEntries) {
            restored.put(entry.getKey(), entry.getValue());
        }
        for (final Map.Entry<WriteKey, PendingWrite> entry : pending.entrySet()) {
            final PendingWrite existing = restored.get(entry.getKey());
            if (existing == null) {
                restored.put(entry.getKey(), entry.getValue());
            } else {
                for (final WriteCommand waiter : entry.getValue().waiters) {
                    releaseRetainedBytes(existing.supersede(waiter));
                    coalescedWrites.incrementAndGet();
                }
            }
        }
        pending.clear();
        pending.putAll(restored);
    }

    private void flushBarrier() {
        final BarrierCommand barrier = activeBarrier;
        activeOperation = ActiveOperation.FLUSH;
        activeTimeoutTask = scheduleActiveTimeout(ActiveOperation.FLUSH);
        invoke(backend::flush).whenComplete((ignored, failure) -> dispatchToPump(
            () -> completeFlush(barrier, failure)
        ));
    }

    private void completeFlush(final BarrierCommand barrier, final Throwable failure) {
        if (activeOperation != ActiveOperation.FLUSH) {
            return;
        }
        cancelActiveTimeout();
        activeOperation = null;
        if (terminal.get()) {
            return;
        }
        if (failure != null) {
            flushFailures.incrementAndGet();
            if (!retryable(failure)) {
                enterTerminalFailure(failure);
            } else {
                scheduleRetry(failure);
            }
            return;
        }
        retryDelay = config.initialRetryDelay();
        retryAttempts = 0;
        activeBarrier = null;
        if (barrier.closes()) {
            startBackendClose(barrier.completion());
        } else {
            completeFuture(barrier.completion(), null);
            schedulePump();
        }
    }

    private void startBackendClose(final CompletableFuture<Void> result) {
        if (backendCloseStarted) {
            return;
        }
        backendCloseStarted = true;
        activeOperation = ActiveOperation.CLOSE;
        activeTimeoutTask = scheduleActiveTimeout(ActiveOperation.CLOSE);
        invoke(backend::closeAsync).whenComplete((ignored, failure) -> dispatchToPump(
            () -> completeClose(result, failure)
        ));
    }

    private void completeClose(final CompletableFuture<Void> result, final Throwable failure) {
        if (activeOperation != ActiveOperation.CLOSE) {
            return;
        }
        cancelActiveTimeout();
        activeOperation = null;
        if (terminal.get()) {
            if (result != null && failure != null) {
                completeFuture(result, failure);
            }
            shutdownExecutors();
            return;
        }
        finishTerminal(result, failure);
    }

    private void scheduleRetry(final Throwable failure) {
        if (terminal.get()) {
            return;
        }
        if (retryAttempts >= config.maximumRetryAttempts()) {
            enterTerminalFailure(failure);
            return;
        }
        retryAttempts++;
        retries.incrementAndGet();
        final Duration delay = retryDelay;
        if (retryDelay.compareTo(config.maximumRetryDelay()) >= 0) {
            retryDelay = config.maximumRetryDelay();
        } else {
            try {
                retryDelay = retryDelay.multipliedBy(2);
            } catch (final ArithmeticException overflow) {
                retryDelay = config.maximumRetryDelay();
            }
            if (retryDelay.compareTo(config.maximumRetryDelay()) > 0) {
                retryDelay = config.maximumRetryDelay();
            }
        }
        final long delayNanos = delay.toNanos();
        final long now = System.nanoTime();
        retryNotBeforeNanos = now > Long.MAX_VALUE - delayNanos ? Long.MAX_VALUE : now + delayNanos;
        pumpExecutor.schedule(() -> {
            retryNotBeforeNanos = 0L;
            schedulePump();
        }, delayNanos, TimeUnit.NANOSECONDS);
    }

    private void timeoutClose(final CompletableFuture<Void> result) {
        if (result.isDone() || terminal.get()) {
            return;
        }
        enterTerminalFailure(new TimeoutException(
            "Commit pump did not drain and close within " + config.closeTimeout()
        ));
    }

    private void enterTerminalFailure(final Throwable failure) {
        beginAbort(Objects.requireNonNull(failure, "failure"));
    }

    private void startBackendAbort() {
        backendCloseStarted = true;
        activeOperation = ActiveOperation.ABORT;
        activeTimeoutTask = scheduleActiveTimeout(ActiveOperation.ABORT);
        invoke(() -> backend.abortAsync(shutdownCause)).whenComplete((ignored, failure) -> dispatchToPump(
            () -> completeAbort(failure)
        ));
    }

    private void completeAbort(final Throwable cleanupFailure) {
        if (activeOperation != ActiveOperation.ABORT) {
            return;
        }
        cancelActiveTimeout();
        activeOperation = null;
        finishAbortCleanup(cleanupFailure);
    }

    private void startEmergencyCleanup() {
        backendCloseStarted = true;
        backendExecutor.shutdownNow();
        try {
            emergencyExecutor.execute(() -> {
                Throwable cleanupFailure = null;
                try {
                    final boolean backendStopped = backendExecutor.awaitTermination(
                        config.closeTimeout().toNanos(), TimeUnit.NANOSECONDS);
                    if (!backendStopped) {
                        cleanupFailure = new TimeoutException("Timed out stopping backend invocation thread");
                    } else {
                        backend.abortAsync(shutdownCause).toCompletableFuture()
                            .orTimeout(config.closeTimeout().toNanos(), TimeUnit.NANOSECONDS).join();
                    }
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    cleanupFailure = exception;
                } catch (final RuntimeException exception) {
                    cleanupFailure = unwrap(exception);
                }
                finishAbortCleanup(cleanupFailure);
            });
        } catch (final RejectedExecutionException rejected) {
            finishAbortCleanup(rejected);
        }
    }

    private void finishAbortCleanup(final Throwable cleanupFailure) {
        if (!abortCleanupFinished.compareAndSet(false, true)) {
            return;
        }
        final Throwable unwrappedCleanup = cleanupFailure == null ? null : unwrap(cleanupFailure);
        final Throwable closeFailure = shutdownCause;
        if (unwrappedCleanup != null && closeFailure != unwrappedCleanup) {
            closeFailure.addSuppressed(unwrappedCleanup);
        }
        completeFuture(closeFuture.get(), closeFailure);
        completeFuture(abortFuture.get(), unwrappedCleanup);
        shutdownExecutors();
    }
    private void finishTerminal(final CompletableFuture<Void> result, final Throwable failure) {
        if (failure != null) {
            terminalFailure.compareAndSet(null, failure);
        }
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        cancelCloseTimeout();
        cancelActiveTimeout();
        accepting.set(false);
        if (failure != null) {
            failOutstanding(failure);
        }
        completeFuture(result, failure);
        shutdownExecutors();
    }

    private void failOutstanding(final Throwable failure) {
        final List<Map.Entry<WriteKey, PendingWrite>> inFlight = activeBatch;
        activeBatch = null;
        if (inFlight != null) {
            for (final Map.Entry<WriteKey, PendingWrite> entry : inFlight) {
                completeWrites(entry.getValue(), failure);
            }
        }
        for (final PendingWrite write : pending.values()) {
            completeWrites(write, failure);
        }
        pending.clear();
        Command command;
        while ((command = commands.poll()) != null) {
            if (command instanceof WriteCommand write) {
                accountCompletion(write);
                completeFuture(write.completion, failure);
            } else {
                final CompletableFuture<Void> barrier = ((BarrierCommand) command).completion();
                if (barrier != closeFuture.get()) {
                    completeFuture(barrier, failure);
                }
            }
        }
        if (activeBarrier != null) {
            if (activeBarrier.completion() != closeFuture.get()) {
                completeFuture(activeBarrier.completion(), failure);
            }
            activeBarrier = null;
        }
    }

    private void completeWrites(final PendingWrite pendingWrite, final Throwable failure) {
        for (final WriteCommand waiter : pendingWrite.waiters) {
            accountCompletion(waiter);
            completeFuture(waiter.completion, failure);
        }
    }

    private void completeFuture(final CompletableFuture<Void> future, final Throwable failure) {
        if (future == null) {
            return;
        }
        final Runnable completion = () -> {
            if (failure == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(unwrap(failure));
            }
        };
        try {
            callbackExecutor.execute(completion);
        } catch (final RejectedExecutionException rejected) {
            completion.run();
        }
    }

    private void dispatchToPump(final Runnable callback) {
        try {
            pumpExecutor.execute(callback);
        } catch (final RejectedExecutionException ignored) {
            // Terminal shutdown deliberately drops late backend callbacks.
        }
    }

    private void reserveEnqueue(final long retainedBytes) {
        synchronized (metricsLock) {
            final long operations = outstandingOperations.get();
            final long bytes = outstandingBytes.get();
            final boolean operationLimit = operations >= config.backpressureOperationThreshold();
            final boolean byteLimit = retainedBytes > config.backpressureByteThreshold() - bytes;
            if (operationLimit || byteLimit) {
                backpressureEvents.incrementAndGet();
                throw new RejectedExecutionException("Commit pump backpressure limit exceeded");
            }
            final long updatedOperations = outstandingOperations.incrementAndGet();
            final long updatedBytes = outstandingBytes.addAndGet(retainedBytes);
            highWaterOperations.accumulateAndGet(updatedOperations, Math::max);
            highWaterBytes.accumulateAndGet(updatedBytes, Math::max);
        }
    }

    private void rollbackReservation(final long retainedBytes) {
        synchronized (metricsLock) {
            outstandingOperations.decrementAndGet();
            outstandingBytes.addAndGet(-retainedBytes);
        }
    }

    private void releaseRetainedBytes(final long retainedBytes) {
        if (retainedBytes == 0) {
            return;
        }
        synchronized (metricsLock) {
            outstandingBytes.addAndGet(-retainedBytes);
        }
    }

    private void accountCompletion(final WriteCommand command) {
        synchronized (metricsLock) {
            outstandingOperations.decrementAndGet();
            outstandingBytes.addAndGet(-command.releaseRetainedValue());
        }
    }

    private boolean exceedsThreshold(final long operations, final long bytes) {
        return operations >= config.backpressureOperationThreshold()
            || bytes >= config.backpressureByteThreshold();
    }

    private ScheduledFuture<?> scheduleActiveTimeout(final ActiveOperation expected) {
        return pumpExecutor.schedule(() -> {
            if (activeOperation != expected) {
                return;
            }
            final TimeoutException timeout = new TimeoutException(
                "Commit pump backend operation timed out: " + expected
            );
            enterTerminalFailure(timeout);
            if (expected == ActiveOperation.ABORT) {
                activeOperation = null;
                finishAbortCleanup(timeout);
                return;
            }
        }, config.closeTimeout().toNanos(), TimeUnit.NANOSECONDS);
    }

    private void cancelActiveTimeout() {
        if (activeTimeoutTask != null) {
            activeTimeoutTask.cancel(false);
            activeTimeoutTask = null;
        }
    }

    private void cancelCloseTimeout() {
        if (closeTimeoutTask != null) {
            closeTimeoutTask.cancel(false);
            closeTimeoutTask = null;
        }
    }

    private static boolean retryable(final Throwable failure) {
        final Throwable cause = unwrap(failure);
        return !(cause instanceof Error)
            && !(cause instanceof io.cesiumfolia.storage.StorageException)
            && !(cause instanceof IllegalArgumentException)
            && !(cause instanceof IllegalStateException)
            && !(cause instanceof NullPointerException);
    }

    private <T> CompletionStage<T> invoke(final StageSupplier<T> supplier) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        try {
            backendExecutor.execute(() -> {
                final CompletionStage<T> stage;
                try {
                    stage = Objects.requireNonNull(supplier.get(), "Backend returned a null completion stage");
                } catch (final Throwable failure) {
                    result.completeExceptionally(failure);
                    return;
                }
                try {
                    stage.whenComplete((value, failure) -> {
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
                } catch (final Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (final Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
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

    private void shutdownExecutors() {
        if (executorsShutdown.compareAndSet(false, true)) {
            pumpExecutor.shutdown();
            backendExecutor.shutdown();
            callbackExecutor.shutdown();
            emergencyExecutor.shutdown();
        }
    }

    private static ThreadFactory daemonFactory(final String name) {
        return runnable -> Thread.ofPlatform().daemon(true).name(name).unstarted(runnable);
    }

    @FunctionalInterface
    private interface StageSupplier<T> {
        CompletionStage<T> get();
    }

    @Override
    public void close() {
        closeAsync().toCompletableFuture().join();
    }
}
