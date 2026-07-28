package io.cesiumfolia.folia;

/** A lock-free snapshot of pressure and backend activity for one durability scope. */
public record BackpressureMetrics(
    long outstandingOperations,
    long outstandingBytes,
    long highWaterOperations,
    long highWaterBytes,
    long coalescedWrites,
    long successfulCommits,
    long commitFailures,
    long flushFailures,
    long retries,
    long backpressureEvents,
    boolean backpressured,
    boolean acceptingWrites
) {}
