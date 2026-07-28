package io.cesiumfolia.storage;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous byte-oriented storage contract. Implementations must snapshot a supplied batch
 * before returning, apply one batch atomically, and complete commit only after its bytes and
 * generation metadata satisfy the backend durability policy. {@link #flush()} fences all earlier
 * commits. Implementations must serialize commit, flush, and shutdown, make shutdown methods
 * idempotent, and return promptly rather than blocking the caller before producing a stage.
 * {@link #closeAsync()} performs a clean shutdown; {@link #abortAsync(Throwable)} stops without
 * recording a clean shutdown and fails queued work with the supplied cause.
 */
public interface StorageBackend extends AutoCloseable {
    CompletionStage<Optional<byte[]>> read(StorageNamespace namespace, BinaryKey key);
    CompletionStage<List<StorageEntry>> scan(StorageNamespace namespace);
    /**
     * Returns an ordered resource-bounded page strictly after {@code afterExclusive}, or from the
     * first key when it is {@code null}. Implementations may return fewer than {@code limit}
     * entries when a byte budget is reached. The returned cursor is the last returned key.
     */
    CompletionStage<ScanPage> scanPage(StorageNamespace namespace, BinaryKey afterExclusive, int limit);
    CompletionStage<Long> count(StorageNamespace namespace);
    CompletionStage<CommitResult> commit(StorageBatch batch);
    CompletionStage<Void> flush();
    StorageManifest manifest();
    CompletionStage<Void> closeAsync();
    CompletionStage<Void> abortAsync(Throwable cause);
    @Override
    default void close() {
        closeAsync().toCompletableFuture().join();
    }
}
