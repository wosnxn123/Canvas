# Folesium — Usage & Configuration Guide

> **English** | [简体中文](USAGE.zh.md)

This is the complete operator's guide for running **this server fork** (Folia 26.2 /
Canvas) with the Folesium storage backend. For the engine internals see the
[Folesium repository](https://github.com/wosnxn123/Folesium) (`docs/ARCHITECTURE.md`,
`docs/SCHEMA.md`).

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

```bash
./folesium-integration/setup-folesium.sh          # clone/update engine, patch, build
# result: <fork>-server/build/libs/<fork>-paperclip-*.jar
```

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

All keys work both as `-Dfolesium.<key>` and as `<key>` in `folesium.properties`.
Unparseable values fall back to the default and log a warning — they never abort
startup.

| key | default | meaning |
|---|---|---|
| `enabled` | **`false`** | master switch. Off = 100 % stock server. |
| `configFile` | `folesium.properties` | alternative config file path (system property only) |
| `shards` | auto (8/16/32/64/128 by CPU cores) | shard count for **newly created** stores (power of two, 1–1024; existing stores keep their on-disk value). The default is auto-tuned from CPU core count; 8–16 is fine for small servers. |
| `durability` | `BATCH` | `ALWAYS` = fsync every write; `BATCH` = background group commit; `EXPLICIT` = fsync only on flush/close |
| `batchFlushMillis` | `500` | group-commit interval for `BATCH` |
| `compression` | `ZSTD` (when zstd-jni is available) else `DEFLATE` | `NONE` / `DEFLATE` / `ZSTD`; fixed at store creation (old records stay readable) |
| `compressionLevel` | `4` | Deflate and ZSTD level 1–9. 4 ≈ vanilla zlib ratio at lower CPU. |
| `compactRatio` | `0.5` | compact a shard when dead bytes exceed this fraction of the file |
| `compactMinBytes` | `8388608` | never compact shards smaller than this (8 MiB) |
| `verifyChecksums` | `false` | re-verify record CRC32C on every read (~2× read I/O; recovery scans always verify) |

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

Example of a durability-first setup:

```properties
enabled=true
durability=ALWAYS
compression=ZSTD
verifyChecksums=true
```

---

## 5. Adopting Folesium on an existing world

Three equivalent paths — pick one. **Back up your world first** in every case.

### 5a. Lazy migration (zero downtime beyond a restart)

Just enable Folesium and start. Any chunk or player missing from the store is read from
the original `.mca`/player files on demand, and migrates into the store when saved. The
world is fully playable from the first second; the store fills up as the world is
visited.

### 5b. One-shot conversion (Cesium-style startup flags, recommended)

```bash
# server stopped:
java -jar <fork>-paperclip-*.jar --folesiumConvertToFolesium --nogui   # exits when done
java -Dfolesium.enabled=true -jar <fork>-paperclip-*.jar --nogui       # start on Folesium
```

* Converts **all dimensions** (recursively discovered, modded layouts included) **and
  the player data** in one run, multi-threaded.
* The conversion **merges**: records already in the store (e.g. written live under 5a)
  always win over the older files — safe to run after having played with 5a.
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
| store directory keeps growing | dead records are reclaimed by compaction once a shard passes `compactRatio` × size and `compactMinBytes`; lower these to compact sooner |
| want to verify integrity | start with `-Dfolesium.verifyChecksums=true`, or `folesium-converter … inspect <store>` |

Log lines to know:

```text
Folesium: opened DIMENSION store <path>     # a dimension store is active
Folesium: opened PLAYERS store <path>       # the player store is active
Folesium: no files were deleted. ...        # conversion retention note (§5/§6)
```
