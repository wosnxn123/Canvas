package io.cesiumfolia.storage;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable binary key. The byte array is copied at construction and on access. */
public final class BinaryKey implements Comparable<BinaryKey> {
    private final byte[] bytes;
    private final int hash;

    public BinaryKey(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        this.bytes = bytes.clone();
        this.hash = Arrays.hashCode(this.bytes);
    }

    public static BinaryKey of(final byte[] bytes) {
        return new BinaryKey(bytes);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    @Override
    public int compareTo(final BinaryKey other) {
        Objects.requireNonNull(other, "other");
        final int length = Math.min(this.bytes.length, other.bytes.length);
        for (int i = 0; i < length; i++) {
            final int left = this.bytes[i] & 0xff;
            final int right = other.bytes[i] & 0xff;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return Integer.compare(this.bytes.length, other.bytes.length);
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof BinaryKey other && Arrays.equals(this.bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return HexFormat.of().formatHex(bytes);
    }
}
