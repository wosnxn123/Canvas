package io.cesiumfolia.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Small codecs for values whose wire representation is independent of Minecraft/NBT. */
public final class StorageCodecs {
    private static final StorageCodec<byte[]> BYTES = new StorageCodec<>() {
        @Override
        public byte[] encode(final byte[] value) {
            return Objects.requireNonNull(value, "value").clone();
        }

        @Override
        public byte[] decode(final byte[] bytes) {
            return Objects.requireNonNull(bytes, "bytes").clone();
        }
    };

    private static final StorageCodec<String> UTF_8 = new StorageCodec<>() {
        @Override
        public byte[] encode(final String value) {
            return Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(final byte[] bytes) {
            return new String(Objects.requireNonNull(bytes, "bytes"), StandardCharsets.UTF_8);
        }
    };

    private static final StorageCodec<UUID> UUID_CODEC = new StorageCodec<>() {
        @Override
        public byte[] encode(final UUID value) {
            Objects.requireNonNull(value, "value");
            return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
        }

        @Override
        public UUID decode(final byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length != 16) {
                throw new StorageException("UUID value must contain 16 bytes");
            }
            final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
    };

    private StorageCodecs() {}

    public static StorageCodec<byte[]> bytes() {
        return BYTES;
    }

    public static StorageCodec<String> utf8() {
        return UTF_8;
    }

    public static StorageCodec<UUID> uuid() {
        return UUID_CODEC;
    }
}
