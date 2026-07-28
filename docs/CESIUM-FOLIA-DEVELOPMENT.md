# Cesium-Folia development boundary

Canvas is the first server adapter for Cesium-Folia; Canvas is not the owner of the storage module or its on-disk format. Cesium-Folia must remain usable by another Folia-derived server without depending on Canvas or Minecraft implementation classes.

## Architecture boundary

Cesium-Folia owns the reusable storage system:

- `storage-core` owns backend contracts, namespaces, binary keys and codecs, batches, scan results, exceptions, and `StorageManifest`.
- `storage-lmdb` owns the LMDB implementation, locking, compression, map growth, manifest persistence, clean/unclean state, and backend lifecycle.
- `storage-anvil` owns logical Anvil region reading and writing used by offline conversion.
- `folia-integration` owns `CommitPump`, `CommitPumpConfig`, `StorageHookAdapter`, `FoliaStorageSchemas`, and `FoliaStorageRuntime`.
- `tools` owns offline inspection, verification, import, and export through `io.cesiumfolia.tools.CesiumFoliaTool`.

`FoliaStorageRuntime` is the lifetime boundary used by a server adapter. It transactionally opens and owns the `global` and `dimensions` LMDB backends and their two `StorageHookAdapter`s. Its root contains `global/` and `dimensions/`. `close(true)` drains both scopes; `close(false)` aborts both scopes. Opening or closing one scope cannot leave the other scope presented to Canvas as a successfully opened runtime, and secondary close failures are attached to the primary failure as suppressed exceptions.

Canvas owns only server integration:

- `GlobalConfiguration.CesiumStorage` configuration, validation, and mapping to `LmdbStorageBackend.Options` and `CommitPumpConfig`;
- resolution of the configured storage directory as a normalized, world-relative path that cannot escape the world root;
- Minecraft object/key selection and NBT or JSON serialization at each hook;
- the process-wide `CesiumStorageManager` facade used by Minecraft patches;
- the `/canvas cesium [status]` command shape and status rendering;
- observation of asynchronous write failures and the policy of marking the server abnormal and halting it;
- startup and shutdown placement in the Minecraft lifecycle.

Canvas must not open an LMDB backend, construct a storage hook adapter, define a schema identifier, or parse a manifest. Those are Cesium-Folia responsibilities.

## Schema ownership

`io.cesiumfolia.folia.FoliaStorageSchemas` is the only authority for live schema identity and manifest creation/preflight:

- global scope: `cesium-folia-global-v1`;
- dimensions scope: `cesium-folia-dimensions-v1`.

Schema changes start in Cesium-Folia and must update its runtime, offline tools, and conversion behavior together. Do not copy schema strings or manifest rules into Canvas. This feature has no released compatibility contract, so development schema changes use a clean cutover rather than aliases, legacy acceptance, or Canvas-side compatibility shims.

The manifest's Minecraft data version must match the running server. Existing worlds are accepted only when both prepared scope stores pass Cesium-Folia preflight. `allowUncleanRecovery` changes only the unclean-open policy; it is not a schema override and it never enables an automatic fallback to Anvil.

## Canvas sources and hooks

The direct Canvas sources are:

- `canvas-server/src/main/java/io/canvasmc/canvas/storage/cesium/CesiumStorageManager.java`: thin runtime facade, key routing, failure observation, status snapshots, and Canvas shutdown policy.
- `canvas-server/src/main/java/io/canvasmc/canvas/GlobalConfiguration.java`: `CesiumStorage` options, validation, command registration, and reload notification.
- `canvas-server/src/main/java/io/canvasmc/canvas/subcommands/CesiumStatusSubCommand.java`: read-only command output.
- `canvas-server/build.gradle.kts.patch`: dependencies on the Cesium-Folia `storage-core`, `storage-lmdb`, and `folia-integration` projects.

Minecraft hooks are carried by the matching per-file patches under `canvas-server/minecraft-patches/sources/`; Cesium changes share each file's existing Canvas patch when one already exists:

| Patched Minecraft file | Responsibility |
| --- | --- |
| `net/minecraft/server/Main.java` | Open storage early enough to read Cesium world metadata; close it on bootstrap exits. |
| `net/minecraft/server/MinecraftServer.java` | Initialize persistent stores after configuration, order final flushing/close, and implement abnormal halt. |
| `net/minecraft/server/dedicated/DedicatedServer.java` | Enable configured persistent-storage initialization for a dedicated server. |
| `net/minecraft/gametest/framework/GameTestServer.java` | Initialize persistent state with Cesium configuration disabled. |
| `net/minecraft/world/level/storage/LevelStorageSource.java` | Serialize and route `level.dat`-equivalent world metadata. |
| `net/minecraft/world/level/storage/PlayerDataStorage.java` | Serialize, read, write, and data-fix player NBT by UUID. |
| `net/minecraft/server/PlayerAdvancements.java` | Serialize advancement JSON by player UUID. |
| `net/minecraft/stats/ServerStatsCounter.java` and `net/minecraft/server/players/PlayerList.java` | Carry the player UUID and serialize statistics JSON. |
| `net/minecraft/world/level/chunk/storage/RegionFileStorage.java` | Route chunk, POI, and entity records by dimension and chunk coordinates; retain vanilla region-file behavior when disabled. |
| `net/minecraft/world/level/storage/SavedDataStorage.java` | Route compressed saved-data NBT under a dimension-qualified key. |

Do not change the public `CesiumStorageManager` methods used by these patches merely to expose Cesium-Folia internals. The manager obtains adapters and backends from `FoliaStorageRuntime` and keeps the Minecraft-facing API stable.

## Threading and failure invariants

1. Minecraft code finishes serialization before calling the adapter. The bytes passed to Cesium-Folia are a snapshot; Cesium-Folia does not retain or inspect mutable Minecraft objects.
2. A region thread may enqueue a write but must not perform LMDB I/O. Each scope has its own commit pump and backend invocation threads.
3. The global and dimensions scopes are separate durability domains. There is no cross-scope transaction, ordering guarantee, or shared commit batch.
4. The adapter's pending overlay provides read-your-enqueued-write behavior while a durable commit is pending. Callers must use the adapter rather than bypassing it for reads.
5. The pump bounds outstanding work using the configured operation and byte thresholds, coalesces latest writes by key, and reports its state through metrics. Do not add another Canvas queue.
6. Every returned asynchronous Canvas write is passed through `CesiumStorageManager.observe` or `submitObserved`. A synchronous submission failure and an exceptional completion have the same terminal policy.
7. The first terminal storage failure is retained. Canvas logs it, calls `MinecraftServer.cesium$storageFailed()`, marks the exit abnormal, and requests a halt. Enabled mode never silently switches to vanilla files after a storage failure.
8. Reads and explicit flushes that Canvas joins also feed failures into the same terminal path. Cesium decode failures are not treated as missing data.
9. A clean manifest may be published only after all producers have stopped and all accepted writes have drained. A terminal failure, abnormal server exit, or explicit unclean close must abort instead.
10. Shutdown is idempotent at the Canvas facade. `current` is reset and per-scope metrics are logged even when runtime close fails.

## Startup flow

1. Canvas loads and validates `GlobalConfiguration.CesiumStorage`.
2. `CesiumStorageManager.open(worldRoot, existingWorld)` returns a disabled facade without touching the Cesium filesystem when `enabled` is false.
3. When enabled, Canvas resolves `rootDirectory` inside `worldRoot`, constructs only the backend and pump option records, obtains the current Minecraft data version, and calls `FoliaStorageRuntime.open(...)`.
4. Cesium-Folia preflights both manifests and data files, applies the configured unclean-open policy, opens both backends, and constructs both adapters transactionally.
5. Only the fully opened runtime is published through `CesiumStorageManager.current()`.
6. Minecraft world metadata and the remaining persistent stores are then initialized through the existing manager methods.

The early open in `Main` is required because world metadata itself may be in the global scope. The later call during dedicated-server initialization is deliberately idempotent for the same root and unchanged settings.

## Shutdown flow

1. Minecraft stops normal producers and closes saved-data resources.
2. Moonrise region-file I/O is flushed so every region task has at least submitted its Cesium write.
3. `CesiumStorageManager.flushAndClose(!abnormalExit)` computes `cleanClose = requestedClean && terminalFailure == null`.
4. The manager calls `FoliaStorageRuntime.close(cleanClose)` exactly once. The runtime drains and cleanly closes both scopes for a clean close, or aborts both for an unclean close.
5. Canvas logs final global/dimensions pump metrics, resets `current` to the disabled facade, and propagates any combined runtime close failure through its abnormal-halt policy.

Bootstrap failures call the same facade close path. No patch may directly close an adapter or backend.

## Disabled fallback

Cesium is opt-in. With `enabled: false`, `CesiumStorageManager.enabled()` is false, no Cesium directory is accessed, `regionStore(...)` returns `null`, and every patched Minecraft class follows its original vanilla/Anvil filesystem path. GameTest initialization also explicitly avoids configuring Cesium.

This fallback is a code-path choice, not live failover. Once a running Cesium world has accepted writes, its old vanilla files are stale. Do not recover or downgrade by merely disabling the option; export a coherent clean Cesium snapshot back to a separate Anvil world first.

## Offline conversion and deployment

Conversion is an offline operation. Stop the server, retain a tested backup, and ensure no server or tool process holds either source or destination. Invoke the tools main class from the Cesium-Folia tools runtime classpath:

```text
java -cp <cesium-folia-tools-runtime-classpath> io.cesiumfolia.tools.CesiumFoliaTool import-global <world-root> <staging-root>/global --data-version <version>
java -cp <cesium-folia-tools-runtime-classpath> io.cesiumfolia.tools.CesiumFoliaTool import-anvil <overworld-root> <staging-root>/dimensions --dimension minecraft:overworld --data-version <version>
java -cp <cesium-folia-tools-runtime-classpath> io.cesiumfolia.tools.CesiumFoliaTool import-anvil <nether-root> <staging-root>/dimensions --dimension minecraft:the_nether --data-version <version>
java -cp <cesium-folia-tools-runtime-classpath> io.cesiumfolia.tools.CesiumFoliaTool import-anvil <end-root> <staging-root>/dimensions --dimension minecraft:the_end --data-version <version>
```

Import every custom dimension into the same dimensions scope using the exact resource identifier Canvas will derive from its `ResourceKey<Level>`. Use that dimension's directory as the Anvil root. The data version must be the version expected by the target Canvas server, not a guessed Minecraft release number.

Before deployment, verify both completed stores while the server remains stopped:

```text
java -cp <classpath> io.cesiumfolia.tools.CesiumFoliaTool inspect <staging-root>/global
java -cp <classpath> io.cesiumfolia.tools.CesiumFoliaTool verify <staging-root>/global
java -cp <classpath> io.cesiumfolia.tools.CesiumFoliaTool inspect <staging-root>/dimensions
java -cp <classpath> io.cesiumfolia.tools.CesiumFoliaTool verify <staging-root>/dimensions
```

Move the complete staging root into the configured world-relative location as one deployment, leave `allowUncleanRecovery` false unless an incident has been reviewed, enable Cesium, and perform a controlled startup. Confirm `/canvas cesium status` reports both scopes, the expected schema identities, no terminal failure, and accepting pumps. Keep the pre-conversion backup until a clean shutdown and a second offline verification have succeeded.

For rollback after Cesium has been used, stop cleanly and export both scopes to a new destination. Use `export-global <lmdb-global-root> <new-world-root>` plus `export-anvil <lmdb-dimensions-root> <dimension-anvil-root> --dimension <id>` for every dimension. Verify the exported world independently before changing configuration or replacing any production directory.

## Maintenance checklist

- Keep `FoliaStorageRuntime` as the only dual-scope owner; Canvas must contain no direct backend open or adapter construction.
- Keep all schema identifiers and manifest validation in `FoliaStorageSchemas` and the Cesium-Folia tools/runtime.
- When adding a namespace, assign it to exactly one adapter scope, add an unambiguous key codec, update offline import/export, then add only serialization/routing in Canvas.
- Preserve the existing public manager methods unless every Minecraft patch callsite is deliberately migrated in the same change.
- Recheck every Cesium-bearing patch under `canvas-server/minecraft-patches/sources/` after each Minecraft update, especially bootstrap exits and shutdown ordering.
- Preserve disabled branches byte-for-byte in behavior: no Cesium filesystem access and the original vanilla files remain authoritative.
- Never add automatic enabled-mode fallback after open, read, decode, enqueue, commit, flush, or close failure.
- Ensure every async write is observed and every synchronous join routes failure to the abnormal-halt policy.
- Keep Minecraft serialization and data fixing in Canvas; never introduce Minecraft classes into Cesium-Folia modules.
- Keep dimension keys based on the full resource identifier, not folder names or display names.
- Treat data-version or schema changes as conversion events and test import, inspect, verify, startup, clean shutdown, and export before deployment.
- Keep command status read-only and sourced from runtime backend manifests and adapter metrics; it must not perform filesystem I/O.
- Review clean versus unclean close paths whenever producer lifecycle changes. Region producers must stop before runtime drain begins.
