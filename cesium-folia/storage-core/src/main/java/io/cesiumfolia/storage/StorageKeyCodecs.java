package io.cesiumfolia.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Canonical key encodings used by the new format. */
public final class StorageKeyCodecs {
    private StorageKeyCodecs() {}
    /** Decoded components of a canonical dimension-qualified chunk key. */
    public record DimensionChunkKey(String dimensionId, int chunkX, int chunkZ) {
        public DimensionChunkKey {
            Objects.requireNonNull(dimensionId, "dimensionId");
            if (dimensionId.isBlank()) {
                throw new IllegalArgumentException("Dimension id must not be blank");
            }
        }
    }

    /** Decoded components of a canonical dimension-qualified string key. */
    public record DimensionStringKey(String dimensionId, String id) {
        public DimensionStringKey {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(id, "id");
            if (dimensionId.isBlank() || id.isBlank()) {
                throw new IllegalArgumentException("Dimension id and string id must not be blank");
            }
        }
    }

    /** Encodes the canonical length-prefixed UTF-8 prefix shared by all dimension-qualified keys. */
    public static BinaryKey dimensionPrefix(final String dimensionId) {
        return new BinaryKey(dimensionPrefixBytes(dimensionId));
    }

    /**
     * Encodes a dimension id and signed chunk coordinates as a canonical, big-endian key.
     * The dimension length is the unsigned byte length of its strict UTF-8 encoding.
     */
    public static BinaryKey dimensionChunk(final String dimensionId, final int x, final int z) {
        final byte[] dimensionPrefix = dimensionPrefixBytes(dimensionId);
        final int keyLength;
        try {
            keyLength = Math.addExact(dimensionPrefix.length, 2 * Integer.BYTES);
        } catch (final ArithmeticException failure) {
            throw new IllegalArgumentException("Dimension id is too long", failure);
        }
        final byte[] bytes = new byte[keyLength];
        final ByteBuffer key = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        key.put(dimensionPrefix).putInt(x).putInt(z);
        return new BinaryKey(bytes);
    }

    /** Strictly decodes a canonical dimension-qualified chunk key. */
    public static DimensionChunkKey decodeDimensionChunk(final BinaryKey key) {
        Objects.requireNonNull(key, "key");
        if (key.size() < 3 * Integer.BYTES) {
            throw new IllegalArgumentException("Dimension chunk key is too short");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(key.bytes()).order(ByteOrder.BIG_ENDIAN);
        final long dimensionLength = Integer.toUnsignedLong(buffer.getInt());
        if (dimensionLength == 0 || dimensionLength != buffer.remaining() - 2L * Integer.BYTES) {
            throw new IllegalArgumentException("Dimension chunk key has an invalid dimension length");
        }
        final ByteBuffer dimensionBytes = buffer.slice();
        dimensionBytes.limit((int) dimensionLength);
        final String dimensionId;
        try {
            dimensionId = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(dimensionBytes).toString();
        } catch (final CharacterCodingException exception) {
            throw new IllegalArgumentException("Dimension chunk key contains invalid UTF-8", exception);
        }
        buffer.position(buffer.position() + (int) dimensionLength);
        return new DimensionChunkKey(dimensionId, buffer.getInt(), buffer.getInt());
    }

    /** Encodes two strict UTF-8 strings, each prefixed by its unsigned big-endian byte length. */
    public static BinaryKey dimensionString(final String dimensionId, final String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("String id must not be blank");
        }
        final byte[] dimensionPrefix = dimensionPrefixBytes(dimensionId);
        final byte[] idBytes = encodeUtf8(id, "String id");
        final int length;
        try {
            length = Math.addExact(Math.addExact(dimensionPrefix.length, Integer.BYTES), idBytes.length);
        } catch (final ArithmeticException failure) {
            throw new IllegalArgumentException("Dimension string key is too long", failure);
        }
        return new BinaryKey(ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
            .put(dimensionPrefix).putInt(idBytes.length).put(idBytes).array());
    }

    /** Strictly decodes a canonical dimension-qualified string key. */
    public static DimensionStringKey decodeDimensionString(final BinaryKey key) {
        Objects.requireNonNull(key, "key");
        if (key.size() < 2 * Integer.BYTES + 2) {
            throw new IllegalArgumentException("Dimension string key is too short");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(key.bytes()).order(ByteOrder.BIG_ENDIAN);
        final long dimensionLength = Integer.toUnsignedLong(buffer.getInt());
        if (dimensionLength == 0 || dimensionLength > buffer.remaining() - Integer.BYTES - 1L) {
            throw new IllegalArgumentException("Dimension string key has an invalid dimension length");
        }
        final byte[] dimensionBytes = new byte[(int) dimensionLength];
        buffer.get(dimensionBytes);
        final long idLength = Integer.toUnsignedLong(buffer.getInt());
        if (idLength == 0 || idLength != buffer.remaining()) {
            throw new IllegalArgumentException("Dimension string key has an invalid string length");
        }
        final byte[] idBytes = new byte[(int) idLength];
        buffer.get(idBytes);
        return new DimensionStringKey(decodeUtf8(dimensionBytes, "dimension id"),
            decodeUtf8(idBytes, "string id"));
    }

    private static byte[] dimensionPrefixBytes(final String dimensionId) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (dimensionId.isBlank()) {
            throw new IllegalArgumentException("Dimension id must not be blank");
        }
        final byte[] dimensionBytes = encodeUtf8(dimensionId, "Dimension id");
        final int length;
        try {
            length = Math.addExact(Integer.BYTES, dimensionBytes.length);
        } catch (final ArithmeticException failure) {
            throw new IllegalArgumentException("Dimension id is too long", failure);
        }
        return ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
            .putInt(dimensionBytes.length).put(dimensionBytes).array();
    }

    private static byte[] encodeUtf8(final String value, final String description) {
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value));
            final byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (final CharacterCodingException exception) {
            throw new IllegalArgumentException(description + " is not valid UTF-8 text", exception);
        }
    }

    private static String decodeUtf8(final byte[] value, final String description) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString();
        } catch (final CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 in " + description, exception);
        }
    }

    /** Encodes a canonical nonblank strict UTF-8 string key. */
    public static BinaryKey string(final String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("String key must not be blank");
        }
        return new BinaryKey(encodeUtf8(value, "String key"));
    }

    /** Strictly decodes a canonical UTF-8 string key. */
    public static String decodeString(final BinaryKey key) {
        Objects.requireNonNull(key, "key");
        if (key.size() == 0) {
            throw new IllegalArgumentException("String key must not be empty");
        }
        return decodeUtf8(key.bytes(), "string key");
    }

    public static BinaryKey chunk(final int x, final int z) {
        return new BinaryKey(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(x).putInt(z).array());
    }

    public static int chunkX(final BinaryKey key) {
        return chunkParts(key)[0];
    }

    public static int chunkZ(final BinaryKey key) {
        return chunkParts(key)[1];
    }

    private static int[] chunkParts(final BinaryKey key) {
        if (key.size() != 8) {
            throw new IllegalArgumentException("Chunk key must contain 8 bytes");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(key.bytes()).order(ByteOrder.BIG_ENDIAN);
        return new int[] {buffer.getInt(), buffer.getInt()};
    }

    public static BinaryKey uuid(final UUID uuid) {
        return new BinaryKey(ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array());
    }

    public static UUID decodeUuid(final BinaryKey key) {
        if (key.size() != 16) {
            throw new IllegalArgumentException("UUID key must contain 16 bytes");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(key.bytes()).order(ByteOrder.BIG_ENDIAN);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
