# Folesium — Usage & Configuration Guide

> **English** | [简体中文](USAGE.zh.md)

This is the complete operator's guide for running **this server fork** (Folia 26.2 /
Canvas) with the Folesium storage backend. The current integration is **Folia/Canvas-only**:
Paper-style checkouts are not supported and no Paper integration code is provided. For the
engine internals see the [Folesium repository](https://github.com/wosnxn123/Folesium)
(`docs/ARCHITECTURE.md`, `docs/SCHEMA.md`).

---

## 1. What Folesium replaces

With Folesium enabled the server stops writing Anvil `.mca` files and vanilla
per-player files, and stores the same bytes in sharded append-only logs:

| vanilla data | vanilla location (MC 26.x) | Folesium store |
|---|---|---|
| chunks, entities, POI | `<dimension>/region\|entities\|poi/*.mca` | `<dimension>/folesium/` (`store.role=DIMENSION`) |
| player NBT | `world/players/data/<uuid>.dat` | `world/players/folesium/` (`store.role=PLAYERS`) |
| advancements | `world/players/advancements/<uuid>.json` | same store |
| statistics | `world/players/stats/<uuid>.json` | same store |

On pre-26 worlds the player files live at `world/playerdata|advancements|stats` and the
player store at `world/folesium/`. Both store directories are named `folesium/`; the
server tells them apart by the `store.role` recorded inside each store — never by path.

Payloads are stored as the **exact vanilla bytes** (uncompressed chunk NBT, gzip player
NBT, UTF-8 JSON). Nothing is parsed or re-encoded, so Folesium is Minecraft-version
agnostic and conversions are lossless and byte-identical both ways.

**Folesium is strictly opt-in.** Without `folesium.enabled=true` this server behaves
exactly like the stock fork and keeps writing `.mca`.

---

## 2. Getting a server jar

Run this kit from a Folia 26.2 or Canvas checkout only:

```bash
./folesium-integration/setup-folesium.sh          # clone/update engine, patch, build
# result: <fork>-server/build/libs/<fork>-paperclip-*.jar
```

The script refreshes the fork's tracked Folia file-patches or Canvas vendor sources and
applies the vanilla hooks to generated sources before building. Paper-style checkouts are
not detected or supported.

Options: `--no-build` (patch only), env vars `FOLESIUM_REPO` / `FOLESIUM_REF` /
`FOLESIUM_HOME` (use a local Folesium checkout instead of cloning).

Requirements: JDK 25 (this fork's build requirement), ~4 GB RAM for the build.

---

## 3. Enabling Folesium

Every option is resolved in this order (highest wins):

1. JVM system property: `-Dfolesium.<key>=<value>`
2. `folesium.properties` in the server working directory
   (override the path with `-Dfolesium.configFile=/path/to/file`)
3. built-in default

Minimal start:

```bash
java -Dfolesium.enabled=true -jar <fork>-paperclip-*.jar --nogui
```

Or create `folesium.properties` next to `server.jar` (keys **without** the
`folesium.` prefix):

```properties
enabled=true
```

and start the server normally. On boot you will see one line per opened store:

```text
Folesium: opened DIMENSION store .../world/dimensions/minecraft/overworld/folesium
Folesium: opened PLAYERS store .../world/players/folesium
```

---

## 4. Configuration reference

All file-backed keys work as `-Dfolesium.<key>=<value>` or as `<key>` in
`folesium.properties`. `logging.utf8` is the exception: it is a JVM system-property-only
setting and is not read from `folesium.properties`.
Unparseable **and out-of-range** file-backed values fall back to the default and log a warning —
they never abort startup.

The "applies" column says how a change reaches an **existing** store: **live** = within
seconds, no restart (see §4.1); **next start** = the store is rewritten when next opened;
**world load** = the world binds its backend when it loads; **startup** = read before the
configuration watcher is created.

| key | default | applies | meaning |
|---|---|---|---|
| `enabled` | **`false`** | world load | master switch. Off = 100 % stock server. |
| `configFile` | `folesium.properties` | startup | alternative config file path (system property only) |
| `shards` | auto (8/16/32/64/128 by CPU cores) | next start | shard count (power of two, 1–1024). Changing it reshards the existing store automatically and crash-safely on the next open. Auto-tuned from CPU core count; 8–16 is fine for small servers. |
| `durability` | `BATCH` | live | `ALWAYS` = fsync every write; `BATCH` = background group commit; `EXPLICIT` = fsync only on flush/close. Switching to/from `BATCH` starts/stops the group-commit thread. |
| `batchFlushMillis` | `500` | live | group-commit interval for `BATCH` |
| `compression` | `ZSTD` (when zstd-jni is available) else `DEFLATE` | live | `NONE` / `DEFLATE` / `ZSTD`. Applies to new writes only; every record records its own codec, so a change needs no migration and old records stay readable. |
| `compressionLevel` | auto: `9` (ZSTD) / `4` (DEFLATE) | live | Deflate 1–9, ZSTD 1–22. ZSTD 9 ≈ vanilla zlib-6 write CPU with better ratio. |
| `compactRatio` | `0.5` | live | compact a shard when dead bytes exceed this fraction of the file |
| `compactMinBytes` | `8388608` | live | never compact shards smaller than this (8 MiB) |
| `verifyChecksums` | `false` | live | re-verify record CRC32C on every read (~2× read I/O; recovery scans always verify) |
| `autoReload` | `true` | startup | create the watcher for `folesium.properties`; changing this file key does not stop a watcher already running |
| `autoReloadSeconds` | `10` | live | how often the file is checked for edits |
| `logging.utf8` | `true` | startup | JVM system-property-only switch for existing Folesium/JUL handler encoding; not a file-backed key and not the complete server log |

### 4.1 Retuning a running server

A configuration that turns out not to suit the machine does **not** need a restart to fix.
Edit `folesium.properties` and save it; within `autoReloadSeconds` the change is applied to
every open store and the log states exactly what changed:

```text
[INFO] Folesium: folesium.properties changed on disk, applying it to the running server
[INFO] Folesium: .../world/players/folesium: durability: BATCH -> ALWAYS, compressionLevel: 4 -> 9
```

`enabled` and `shards` are the only two that cannot fully apply live — they are reported in
the log rather than silently dropped, and `shards` is then applied by an automatic reshard on
the next start (staged three-phase commit: a crash leaves either the old store untouched or a
swap that resumes and finishes on the next open). Take the usual backup before a big layout
change on a large world.

`autoReload=false` must be set before startup to prevent watcher creation. Changing the file
from `true` to `false` does not stop an existing watcher; restart after that change.

### 4.2 JVM/JUL log encoding

On platforms whose JVM default charset is not UTF-8, `-Dfolesium.logging.utf8=true` (the default)
re-encodes the **existing** `java.util.logging` handlers when Folesium first loads its config.
This covers Folesium's JUL messages only. Folia/Canvas later route normal server output through
ForwardLogHandler/Log4j, so this setting does not claim to reconfigure the complete server log.
Use `-Dfolesium.logging.utf8=false` to disable it; the setting is system-property-only.

### Durability guidance

| mode | data-loss window on crash | typical use |
|---|---|---|
| `ALWAYS` | none for completed writes | maximum safety, higher latency |
| `BATCH` | up to `batchFlushMillis` | recommended default (still stronger than vanilla Anvil, which fsyncs region files only on close) |
| `EXPLICIT` | until next flush | bulk conversions only |

The integration uses `BATCH` and additionally flushes on every autosave and on
shutdown.

### ZSTD

`compression=ZSTD` uses the `zstd-jni` native library that Folia/Canvas already ship —
no setup needed on the server. It beats Deflate on both ratio and speed. If chosen where
`zstd-jni` is missing (e.g. a standalone converter run), the store open fails with a
clear message.

**Defaults are auto-tuned for the machine**: `shards` follows the CPU core count, and
`compression` becomes `ZSTD` automatically whenever `zstd-jni` is present; the default
`compressionLevel` is then 9 — about the same write CPU as vanilla zlib level 6, with
better ratio — or 4 for the DEFLATE fallback.

Changing `compression` / `compressionLevel` only affects **new** writes (every record
stores its own codec, so no migration is needed). To re-codec an existing store
entirely, rebuild it via the two-step conversion (§5).

Example of a durability-first setup:

```properties
enabled=true
durability=ALWAYS
compression=ZSTD
verifyChecksums=true
```

---

## 5. Adopting Folesium on an existing world

One path. **Back up your world first.**

### 5a. One-shot conversion (Cesium-style startup flags, recommended)

```bash
# server stopped:
java -jar <fork>-paperclip-*.jar --folesiumConvertToFolesium --nogui   # exits when done
java -Dfolesium.enabled=true -jar <fork>-paperclip-*.jar --nogui       # start on Folesium
```

* Converts **all dimensions** (recursively discovered, modded layouts included) **and
  the player data** in one run, multi-threaded.
* The conversion **merges**: records already in the store always win over the older
  files.
* Idempotent and crash-safe: re-running fills only the gaps.
* `--folesiumWorldDir <path>` overrides the world location; the level name otherwise
  comes from `server.properties`.

### 5c. Standalone converter CLI

From the Folesium repository (no server jar needed):

```bash
gradle folesium-converter:installDist
folesium-converter/build/install/folesium-converter/bin/folesium-converter \
    convert /srv/world to-folesium
```

The same tool offers `inspect` (record counts per keyspace) and `diff`
(byte-compare two stores, prints `STORES-EQUAL`).

### What happens to the old files?

**Nothing is ever deleted** — the same guarantee the original cesium-fabric converter
gives. The `.mca` and player files stay on disk as a backup and are ignored while
Folesium is enabled. Once you have verified the converted world, you may delete them
yourself to reclaim disk space: `region/`, `entities/`, `poi/` in every dimension and
`players/data|advancements|stats` (26.x) or `playerdata/ advancements/ stats/`
(pre-26).

### Conversion artifacts (expected, not errors)

* **Backup dirs** — `*.folesium-backup-<uuid>` hold the *old* Anvil files/dirs the
  converter renamed aside before restoring fresh ones (a same-volume rename, so no
  extra I/O). Delete them once the restore is verified
  (`find <world> -name '*.folesium-backup-*' -exec rm -rf {} +`).
* **Staging dirs** — `*.folesium-staging-<uuid>` appear while one data class
  (region / entities / poi) of a dimension is being written; they are renamed to the
  real name when the class finishes.
* **Progress order** — player data first, then dimensions one by one, each data class
  in turn; a per-dimension line prints when that dimension finishes.
* **"0 chunks"** in the output is normal when the Anvil side is empty (a world that
  ran with Folesium enabled keeps its data in the store). The store is never
  re-imported or deleted by a conversion.
* **Sibling worlds** (e.g. `world_creative/` next to `world/`) are not discovered
  automatically; convert them separately with `--folesiumWorldDir <path>`.

A full re-codec of an existing store is the same two-step conversion:
`--folesiumConvertToAnvil` (store → `.mca`), edit `folesium.properties`, then
`--folesiumConvertToFolesium` (`.mca` → store in the new codec).

---

## 6. Rolling back to Anvil

```bash
# server stopped:
java -jar <fork>-paperclip-*.jar --folesiumConvertToAnvil --nogui      # exits when done
java -jar <fork>-paperclip-*.jar --nogui                               # stock server again
```

Restores every chunk and player record back to the vanilla files, byte-identically.
Again **no files are deleted**: the `folesium/` stores are kept as a backup and the
conversion prints their exact, absolute (normalized) paths, e.g.

```text
Folesium: no files were deleted. The now-redundant Folesium stores were kept as a backup:
    /srv/world/players/folesium
    /srv/world/dimensions/minecraft/overworld/folesium
    ...
```

Delete them by hand once the restored world is verified.

> **Warning:** if you keep playing on Anvil after a rollback and later convert to
> Folesium again, **delete the leftover `folesium/` stores first**. The forward
> conversion merges, so stale store records would win over your newer Anvil data.

The two flags are mutually exclusive; passing both aborts with an error.

---

## 7. On-disk layout & backups

```text
world/
├── players/
│   ├── folesium/                    <- PLAYERS store
│   │   ├── folesium.properties      (store metadata; never edit)
│   │   ├── playerdata-0000.flog …   sharded append-only logs
│   │   ├── advancements-….flog, stats-….flog
│   │   └── *.fidx                   (index hints; safe to delete, only slow the next open)
│   └── data/ advancements/ stats/   <- vanilla files (backup after conversion)
└── dimensions/minecraft/overworld/
    ├── folesium/                    <- DIMENSION store (chunks-*, entities-*, poi-*.flog)
    └── region/ entities/ poi/       <- vanilla files (backup after conversion)
```

* **Backup** = copy the `folesium/` directories with the server stopped (or right after
  an autosave). `*.fidx` files may be omitted.
* Every record carries a CRC32C; a torn last write is detected and truncated on the
  next open (crash-safe by construction — there is no `.dat_old`-style rename dance).
* Never edit a store's `folesium.properties`.

---

## 8. Troubleshooting

| symptom | cause / fix |
|---|---|
| server still writes `.mca` | `folesium.enabled` is not set to `true` (check property spelling and config-file location) |
| `mutually exclusive` error at startup | both conversion flags were passed; use one |
| `ZSTD` store open fails | `zstd-jni` not on the classpath (only possible outside the server); use the server flags or add the dependency |
| `.mca` files still present after conversion | expected — the converter never deletes files; remove them manually once verified (§5) |
| `folesium/` still present after rollback | expected — same policy; remove manually (§6) |
| store directory keeps growing | dead records are reclaimed by compaction: the engine checks every open store at most once every 5 minutes and rewrites a shard once it passes `compactRatio` × size and `compactMinBytes`; lower those two to compact sooner |
| want to verify integrity | start with `-Dfolesium.verifyChecksums=true`, or `folesium-converter inspect <store>` |

| `*.folesium-backup-*` dirs | the converter renames old files aside before restoring fresh ones; delete after verifying (§5) |
| `*.folesium-staging-*` dirs | temporary while a data class is being written; renamed to the real name when done (§5) |
| `Loading 0 persistent chunks` at startup | normal on Folia/Canvas — no spawn-chunk preload; chunks load on demand |

Log lines to know:

```text
Folesium: opened DIMENSION store <path>     # a dimension store is active
Folesium: opened PLAYERS store <path>       # the player store is active
Folesium: no files were deleted. ...        # conversion retention note (§5/§6)
```
