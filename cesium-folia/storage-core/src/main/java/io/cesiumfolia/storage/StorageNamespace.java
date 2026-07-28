package io.cesiumfolia.storage;

/** Logical databases stored by the backend. Values are immutable logical NBT/JSON bytes supplied by the server bridge. */
public enum StorageNamespace {
    CHUNKS("chunks"),
    POI("poi"),
    ENTITIES("entities"),
    PLAYERS("players"),
    ADVANCEMENTS("advancements"),
    STATISTICS("statistics"),
    WORLD_DATA("world_data"),
    SAVED_DATA("saved_data");

    private final String databaseName;

    StorageNamespace(final String databaseName) {
        this.databaseName = databaseName;
    }

    public String databaseName() {
        return databaseName;
    }
}
