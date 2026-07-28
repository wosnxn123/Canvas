package io.cesiumfolia.storage;

import java.time.Instant;

public record CommitResult(long generation, int operationCount, Instant committedAt) {
    public CommitResult {
        if (generation < 0 || operationCount < 0) {
            throw new IllegalArgumentException("Invalid commit result");
        }
        if (committedAt == null) {
            throw new NullPointerException("committedAt");
        }
    }
}
