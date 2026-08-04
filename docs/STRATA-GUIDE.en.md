# Strata Storage Engine — Usage Guide (English)

> This is the English usage guide for the **Strata** storage engine bundled with this Canvas fork. 中文版：[STRATA-GUIDE.zh.md](STRATA-GUIDE.zh.md)。
> Engine source, design docs and the CLI tool: [wosnxn123/Strata](https://github.com/wosnxn123/Strata).

## What is Strata

Strata is a Rust hybrid two-tier storage engine replacing Anvil `.mca`:

- **Hot tier**: an append-only segment log serving actively read/written chunks;
- **Cold tier**: region-aligned, read-only blocked archives (`.varc`) with block-level compression and block-index random access;
- **45%+ smaller** than Anvil (measured down to ~10% on highly compressible workloads);
- Storage memory is **independent of world size** (suitable for 10TB-class worlds);
- Per-record xxhash64 verification — corruption is isolated to the single record, never propagates;
- Epoch log + shadow dual-copy manifest — crash-recoverable.

**Disabled by default.** When enabled, it takes over chunk / entity / POI storage. If the native library is missing or fails to load: dimensions **without** a vstore **fall back to Anvil automatically** and boot normally; a dimension **with** an existing vstore **refuses to start (fail-closed)** — otherwise vstore data would be invisible. See "Disabling and rollback".

## Enabling

1. Create or edit `strata.properties` in the **world root** (next to `level.dat`):

   ```properties
   strata.enabled=true
   ```

2. Start the server. If no config file exists, a **fully commented template** is generated on first start (default `strata.enabled=false`).

3. These log lines confirm Strata took over:

   ```
   [Strata] [strata] native bridge loaded, version strata-ffi 0.1.0
   [Strata] [strata] virtual store online for <dimDir> (config=<worldRoot>, vstore=<dimDir>/vstore)
   ```

   One `virtual store online` line appears per dimension.

> **Fail-closed**: if a dimension already has a vstore (`vstore/manifest.vsm` present) but Strata is not serving it (`strata.enabled=false`, native failure, open failure), the level **refuses to start** — silently booting on Anvil would hide the vstore's data. Emergency escape: `strata.force-anvil=true` (boots on Anvil anyway; vstore data stays invisible until converted back; loud warning on every start).

## Multi-world & multi-dimension

- **Multi-dimension**: overworld, nether and end each get an isolated store at `<dimDir>/vstore` (next to that dimension's `region/`).
- **Multi-world**: worlds created by plugins (e.g. Multiverse) are ordinary world roots; each reads its own `strata.properties` and is handled automatically. Every world can be configured independently (compression levels, GC, thread count, ...).

## Converting an existing Anvil world

**Stop the server first.** Two equivalent options:

### Option 1: launch flags (built into the server)

Append to the launch command:

```
--strataConvertToStrata     # Anvil → Strata (all dimensions)
--strataConvertToAnvil      # Strata → Anvil (rollback)
```

Conversion runs synchronously before boot, then the server starts normally. **Remove the flag afterwards**, otherwise the next start overwrites again.

**Conversion guard**: when the target dimension already has a vstore, both launch flags **refuse by default** (the existing vstore may hold newer runtime data) — add `--strataForce` to confirm. `--strataConvertToAnvil` additionally warns: records written at runtime exist only in the vstore and are not enumerated by the Anvil manifest, so the in-server export can miss them — for a lossless full export use `strata-cli convert --to-anvil`.

### Option 2: strata-cli (offline tool from the Strata repo)

```bash
strata-cli convert --to-strata <world>   # Anvil → Strata
strata-cli convert --to-anvil <world>    # Strata → Anvil
```

Conversion semantics (Cesium-style):

- **Overwrites the target in place** and **never deletes the source** (`region/`, `entities/`, `poi/` are kept) — remove them manually after verification;
- **Resumable**: rerunning after an interruption skips finished work;
- **All dimensions**: discovers the overworld root, `DIM-1`/`DIM1` and `dimensions/minecraft/*` automatically;
- Multi-world servers: run once per world root.

The in-server `/strata convert-to-strata` / `/strata convert-to-anvil` subcommands are guarded the same way: `-f`/`--force` is required when the target dimension already has a vstore.

## Configuration reference (strata.properties)

Located in the world root, Java properties format. The fully commented template is generated automatically; keys and defaults:

```properties
# Strata storage configuration / Strata 存储配置
# Master switch (default off — opt in) / 总开关（默认关闭，需显式启用）
strata.enabled=false
# Cold tier (hot -> cold migration) / 冷层（热→冷迁移）
strata.tiering.enabled=true
strata.tiering.stable-flushes=30
strata.tiering.invalid-demote-ratio=0.25
# Compression / 压缩
strata.compression.hot-enabled=true
strata.compression.cold-enabled=true
strata.compression.hot=zstd-3
strata.compression.cold=zstd-9
# Batch compression workers: 0=auto(all cores) 1=serial(default, TPS-first) N>=2=capped
# 批量压缩线程：0=自动(全核) 1=串行(默认,TPS优先) N≥2=限N线程
strata.compression.threads=1
# Index memory budget (MiB) / 索引内存预算（MiB）
strata.index.cache-mb=512
# GC / 垃圾回收
strata.gc.enabled=true
strata.gc.invalid-threshold=0.6
strata.gc.budget-bytes=33554432
# Minimum hole bytes for punch / 挖洞最小洞阈值（字节）
strata.gc.min-hole-bytes=65536
# Emergency escape: boot on Anvil even when a vstore exists (data in vstore invisible until converted back)
# 应急逃生门：vstore 存在时仍按 Anvil 启动（vstore 内数据在转回前不可见）
strata.force-anvil=false
```

Notes:

- **Compression levels can change at any time**: every record carries its own codec/dictionary/generation; mixed old and new records coexist, and reads decompress per-record;
- **`strata.compression.threads` defaults to 1 (serial)**: game-server CPUs are scarce and TPS comes first. On CPU-rich machines set `0` (all cores) or `N` (capped) for faster autosave/conversion;
- `strata.index.cache-mb`: index cache budget — the resident-memory cap, independent of world size;
- `strata.gc.min-hole-bytes`: minimum hole size for hole-punching (default 65536 bytes);
- `strata.force-anvil`: the emergency escape hatch (see "Disabling and rollback");
- `strata.compression.dictionary` is **deprecated**: the old key is still recognized but warned and ignored;
- Invalid values fail startup with file and line number; nothing silently falls back.

## Maintenance commands (strata-cli)

```bash
strata-cli verify <world>       # verify every vstore under the world root (per-record hashes)
strata-cli stats <world>        # size / record statistics (per dimension)
strata-cli compact <world>      # manual GC compaction
strata-cli recompress <world>   # full recompress with current config (safe: writes vstore.new, verifies, then renames)
```

Online maintenance: GC and cold-tier migration **run online with the autosave cycle** — every `strata.tiering.stable-flushes` successful flushes trigger one pass (GC + flush + tier); startup never scans.

> ⚠️ **CLI requires the server stopped**: the vstore holds an exclusive session lock (`vstore/.strata.lock`); the CLI against a live server's vstore reports "another process is using it".
> ⚠️ **Backup note**: hole-punching creates sparse files; copy tools that ignore sparseness re-inflate them to logical size — use `tar --sparse` or a sparse-aware tool, or copy the segment files directly. Consider excluding the vstore directory from antivirus scans.
> ⚠️ **No NFS/shared volumes**: locks and rename/fsync/hole-punch semantics require a local filesystem.

## Disabling and rollback

> ⚠️ **Caveat**: chunks written while Strata was running have had their Anvil master copies deleted on successful write — the vstore holds the only copy. Setting `strata.enabled=false` is **not** "back to normal": those chunks are invisible on the Anvil side, and fail-closed refuses to start the level outright.

1. `strata.enabled=false` (or remove the file) → with a vstore present, **fail-closed refuses to start the level** (so vstore data is never silently invisible); `strata.force-anvil=true` overrides this (emergency escape: vstore data stays invisible until converted back; loud warning on every start);
2. To convert fully back to Anvil files (**CLI recommended**, lossless full export): stop the server, then `strata-cli convert --to-anvil <world>`; the server-side `--strataConvertToAnvil` / `/strata convert-to-anvil` requires explicit `--strataForce` / `-f` and can miss records that exist only in the vstore (written at runtime), which the Anvil manifest does not enumerate;
3. Delete `vstore/` only after the rollback is verified.

## FAQ

- **Log says `native bridge unavailable`?** The native library is not embedded (or the platform does not match). The server already fell back to Anvil — use a paperclip build with embedded natives.
- **Can external tools (Amulet/MCA Editor) read vstore?** No — the format is not Anvil-based. Convert back with `convert --to-anvil` first.
- **Changed compression settings mid-conversion?** Mixed levels are legal but inconsistent — rerun the conversion once to unify.
- **Nether/End not taking over?** Check that every dimension logged `virtual store online`; if a dimension fell back, the log states why.
