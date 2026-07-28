package io.cesiumfolia.folia;

import io.cesiumfolia.storage.BinaryKey;
import io.cesiumfolia.storage.StorageBackend;
import io.cesiumfolia.storage.StorageCodec;
import io.cesiumfolia.storage.StorageEntry;
import io.cesiumfolia.storage.StorageNamespace;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Server-hook boundary for one durability scope.
 *
 * <p>The server hook supplies an already serialized immutable snapshot. This adapter only queues
 * writes; it never performs backend I/O or waits on a region thread. A dimension adapter owns
 * chunk, POI, and entity records. A global adapter owns player and global data records. These
 * scopes are intentionally separate and do not imply one global transaction.</p>
 */
public final class StorageHookAdapter implements AutoCloseable {
    public enum Scope {
        DIMENSION,
        GLOBAL
    }
    private record OverlayKey(StorageNamespace namespace, BinaryKey key) {}
    private static final class PendingValue {
        private byte[] value;

        private PendingValue(final byte[] value) {
            this.value = value;
        }

        private byte[] value() {
            return value;
        }

        private void release() {
            value = null;
        }
    }


    private final StorageBackend backend;
    private final CommitPump pump;
    private final Scope scope;
    private final EnumSet<StorageNamespace> namespaces;
    private final Map<OverlayKey, PendingValue> pending = new LinkedHashMap<>();
    private final Object overlayLock = new Object();
    private Throwable terminalFailure;

    public StorageHookAdapter(final StorageBackend backend, final Scope scope, final String name) {
        this(backend, scope, name, CommitPumpConfig.defaults());
    }

    public StorageHookAdapter(final StorageBackend backend, final Scope scope, final String name,
                              final CommitPumpConfig config) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.namespaces = allowedNamespaces(scope);
        this.pump = new CommitPump(backend, Objects.requireNonNull(name, "name"), config);
    }

    public CompletionStage<Void> put(final StorageNamespace namespace, final BinaryKey key,
                                     final byte[] serializedSnapshot) {
        requireNamespace(namespace);
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(serializedSnapshot, "serializedSnapshot");
        final OverlayKey overlayKey = new OverlayKey(namespace, key);
        synchronized (overlayLock) {
            if (terminalFailure != null) {
                return CompletableFuture.failedFuture(terminalFailure);
            }
            final CommitPump.RetainedPut submission = pump.enqueueRetainedPut(namespace, key, serializedSnapshot);
            return publishOverlay(overlayKey, new PendingValue(submission.snapshot()), submission.completion());
        }
    }

    public CompletionStage<Void> delete(final StorageNamespace namespace, final BinaryKey key) {
        requireNamespace(namespace);
        Objects.requireNonNull(key, "key");
        final OverlayKey overlayKey = new OverlayKey(namespace, key);
        synchronized (overlayLock) {
            if (terminalFailure != null) {
                return CompletableFuture.failedFuture(terminalFailure);
            }
            return publishOverlay(overlayKey, new PendingValue(null), pump.enqueueDelete(namespace, key));
        }
    }

    public <T> CompletionStage<Void> put(final StorageNamespace namespace, final BinaryKey key,
                                         final T value, final StorageCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        final byte[] snapshot = Objects.requireNonNull(codec.encode(value), "codec returned null");
        return put(namespace, key, snapshot);
    }

    public CompletionStage<Optional<byte[]>> read(final StorageNamespace namespace, final BinaryKey key) {
        requireNamespace(namespace);
        Objects.requireNonNull(key, "key");
        synchronized (overlayLock) {
            if (terminalFailure != null) {
                return CompletableFuture.failedFuture(terminalFailure);
            }
            final PendingValue overlay = pending.get(new OverlayKey(namespace, key));
            if (overlay != null) {
                return CompletableFuture.completedFuture(overlay.value() == null
                    ? Optional.empty() : Optional.of(overlay.value().clone()));
            }
        }
        return backend.read(namespace, key).thenApply(value -> {
            synchronized (overlayLock) {
                if (terminalFailure != null) {
                    throw new java.util.concurrent.CompletionException(terminalFailure);
                }
                return value;
            }
        });
    }

    public <T> CompletionStage<Optional<T>> read(final StorageNamespace namespace, final BinaryKey key,
                                                 final StorageCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        return read(namespace, key).thenApply(value -> value.map(bytes ->
            Objects.requireNonNull(codec.decode(bytes), "codec returned null")));
    }

    public CompletionStage<List<StorageEntry>> scan(final StorageNamespace namespace) {
        requireNamespace(namespace);
        final Map<OverlayKey, byte[]> overlaySnapshot = new LinkedHashMap<>();
        synchronized (overlayLock) {
            if (terminalFailure != null) {
                return CompletableFuture.failedFuture(terminalFailure);
            }
            pending.forEach((overlayKey, value) -> {
                if (overlayKey.namespace() == namespace) {
                    overlaySnapshot.put(overlayKey, value.value());
                }
            });
        }
        return backend.scan(namespace).thenApply(entries -> {
            synchronized (overlayLock) {
                if (terminalFailure != null) {
                    throw new java.util.concurrent.CompletionException(terminalFailure);
                }
            }
            final Map<BinaryKey, StorageEntry> merged = new LinkedHashMap<>();
            for (final StorageEntry entry : entries) {
                merged.put(entry.key(), entry);
            }
            overlaySnapshot.forEach((overlayKey, value) -> {
                if (value == null) {
                    merged.remove(overlayKey.key());
                } else {
                    merged.put(overlayKey.key(), new StorageEntry(overlayKey.key(), value));
                }
            });
            final List<StorageEntry> result = new ArrayList<>(merged.values());
            result.sort(Comparator.comparing(StorageEntry::key));
            return List.copyOf(result);
        });
    }

    public CompletionStage<Void> flushAsync() {
        return observeTerminalFailure(pump.flushAsync());
    }

    public BackpressureMetrics metrics() {
        return pump.metrics();
    }

    public Scope scope() {
        return scope;
    }

    public CompletionStage<Void> closeAsync() {
        return observeTerminalFailure(pump.closeAsync());
    }

    public CompletionStage<Void> abortAsync() {
        return abortAsync(new IllegalStateException("Storage hook adapter aborted"));
    }

    public CompletionStage<Void> abortAsync(final Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        synchronized (overlayLock) {
            markTerminal(cause);
        }
        return pump.abortAsync(cause);
    }


    @Override
    public void close() {
        closeAsync().toCompletableFuture().join();
    }

    private CompletionStage<Void> publishOverlay(final OverlayKey overlayKey, final PendingValue pendingValue,
                                                 final CompletionStage<Void> completion) {
        final PendingValue previous = pending.put(overlayKey, pendingValue);
        if (previous != null) {
            previous.release();
        }
        final CompletableFuture<Void> result = new CompletableFuture<>();
        completion.whenComplete((ignored, failure) -> {
            synchronized (overlayLock) {
                if (failure == null) {
                    pending.remove(overlayKey, pendingValue);
                    pendingValue.release();
                } else {
                    markTerminal(failure);
                }
            }
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CompletionStage<Void> observeTerminalFailure(final CompletionStage<Void> completion) {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        completion.whenComplete((ignored, failure) -> {
            if (failure != null) {
                synchronized (overlayLock) {
                    markTerminal(failure);
                }
                result.completeExceptionally(failure);
            } else {
                result.complete(null);
            }
        });
        return result;
    }

    private void markTerminal(final Throwable failure) {
        if (terminalFailure == null) {
            terminalFailure = failure;
        }
        pending.values().forEach(PendingValue::release);
        pending.clear();
    }

    private void requireNamespace(final StorageNamespace namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (!namespaces.contains(namespace)) {
            throw new IllegalArgumentException("Namespace " + namespace + " is not valid for " + scope);
        }
    }

    private static EnumSet<StorageNamespace> allowedNamespaces(final Scope scope) {
        return switch (scope) {
            case DIMENSION -> EnumSet.of(StorageNamespace.CHUNKS, StorageNamespace.POI,
                StorageNamespace.ENTITIES, StorageNamespace.SAVED_DATA);
            case GLOBAL -> EnumSet.of(StorageNamespace.PLAYERS, StorageNamespace.ADVANCEMENTS,
                StorageNamespace.STATISTICS, StorageNamespace.WORLD_DATA, StorageNamespace.SAVED_DATA);
        };
    }
}
