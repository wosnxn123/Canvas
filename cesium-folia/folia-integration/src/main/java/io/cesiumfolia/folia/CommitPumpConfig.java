package io.cesiumfolia.folia;

import java.time.Duration;
import java.util.Objects;

/** Configuration for one independent backend commit pump. */
public record CommitPumpConfig(
    int maxBatchOperations,
    long backpressureOperationThreshold,
    long backpressureByteThreshold,
    Duration initialRetryDelay,
    Duration maximumRetryDelay,
    int maximumRetryAttempts,
    Duration closeTimeout
) {
    public CommitPumpConfig {
        if (maxBatchOperations <= 0) {
            throw new IllegalArgumentException("maxBatchOperations must be positive");
        }
        if (backpressureOperationThreshold <= 0) {
            throw new IllegalArgumentException("backpressureOperationThreshold must be positive");
        }
        if (backpressureByteThreshold <= 0) {
            throw new IllegalArgumentException("backpressureByteThreshold must be positive");
        }
        initialRetryDelay = positive(initialRetryDelay, "initialRetryDelay");
        maximumRetryDelay = positive(maximumRetryDelay, "maximumRetryDelay");
        closeTimeout = positive(closeTimeout, "closeTimeout");
        nanos(initialRetryDelay, "initialRetryDelay");
        nanos(maximumRetryDelay, "maximumRetryDelay");
        nanos(closeTimeout, "closeTimeout");
        if (maximumRetryAttempts < 0) {
            throw new IllegalArgumentException("maximumRetryAttempts must not be negative");
        }
        if (maximumRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException("maximumRetryDelay must not be shorter than initialRetryDelay");
        }
    }

    public CommitPumpConfig(final int maxBatchOperations,
                            final long backpressureOperationThreshold,
                            final long backpressureByteThreshold,
                            final Duration initialRetryDelay,
                            final Duration maximumRetryDelay,
                            final Duration closeTimeout) {
        this(maxBatchOperations, backpressureOperationThreshold, backpressureByteThreshold,
            initialRetryDelay, maximumRetryDelay, 8, closeTimeout);
    }

    public static CommitPumpConfig defaults() {
        return new CommitPumpConfig(
            4_096,
            16_384,
            256L * 1024L * 1024L,
            Duration.ofMillis(10),
            Duration.ofSeconds(5),
            8,
            Duration.ofSeconds(30)
        );
    }

    private static Duration positive(final Duration duration, final String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static void nanos(final Duration duration, final String name) {
        try {
            duration.toNanos();
        } catch (final ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " must fit in nanoseconds", overflow);
        }
    }
}
