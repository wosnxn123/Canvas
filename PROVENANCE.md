# Source Provenance and License Policy

This policy applies to every patch, source edit, generated patch, backport, and
documentation change maintained by this fork. Attribution is a merge
requirement, not a courtesy and not something to repair only after a complaint.

## Non-negotiable rules

1. Preserve the copyright, author, license, and source history of derived work.
2. Record provenance when the work enters the repository. A bundled license
   file by itself is not a substitute for identifying the source of a patch.
3. Treat uncertainty as a release blocker. Do not guess an author or label code
   as original while its origin is unresolved.
4. Intent, patch size, later remediation, or the absence of a private complaint
   do not change the attribution and license obligations.
5. Regenerating, rebasing, squashing, or manually applying a patch must not
   erase its provenance.

## Source classifications

- **Inherited**: arrives through a declared upstream and retains that
  upstream's history and notices.
- **Derived/ported**: code, structure, or a concrete implementation was adapted
  from another project. The exact repository, commit, path, author, license,
  and local changes must be recorded.
- **Independent**: implemented without using another project's concrete
  implementation. Keep dated evidence such as the original issue, sanitized
  crash report, design notes, and tests. If a contributor reviewed similar code
  before writing the change, classify the result as derived unless a reviewer
  can establish an independent implementation.

"Inspired by", "similar to", and "based on an idea" are not sufficient when a
concrete upstream implementation was consulted.

## Required provenance record

For a derived patch, include the following fields in the patch description or
the commit that owns a direct source edit:

```text
Upstream-Project: <project name>
Upstream-Repository: <canonical URL>
Upstream-Commit: <full immutable commit SHA>
Upstream-Path: <path to the source file or patch>
Original-Author: <name and email/handle from the source commit>
License: <SPDX identifier or exact upstream license>
Ported-By: <local adapter>
Adaptation-Notes: <what changed and why>
```

If several sources contributed to one patch, record every source separately.
Source comments such as `// Lophine` or `// Paper` must be retained where they
remain useful. Never replace a source marker with `// Canvas` merely because the
code was manually reapplied or regenerated.

## Review and release gate

A reviewer must verify the immutable source links, authorship, license
compatibility, retained notices, and adaptation notes before merge. Review the
fully applied source, not only the patch-file diff. A change with missing or
ambiguous provenance must not be merged or released.

Before release, confirm that required license notices are present in
`canvas-server/src/main/resources/META-INF/licenses/` and that this ledger still
matches the shipped patch set.

## Current fork provenance ledger

### Upstream baseline

This fork tracks [CraftCanvasMC/Canvas](https://github.com/CraftCanvasMC/Canvas)
`main`. Canvas in turn inherits code and licensing from Folia, Paper, and their
upstreams. Git history is the authoritative record for the moving upstream
baseline. Existing upstream patch authorship and source comments must be
preserved during merges.

### Feature patch set (split 2026-08-07)

Until 2026-08-07 the groups below shipped as a single
`0003-Vanilla-like-experience.patch`. They now ship as per-feature patches under
`canvas-server/minecraft-patches/features/`, numbered to match Lophine
`ver/26.2-hardfork@0724ba3f` (the pinned port source): `0003` command blocks
(original to this fork), `0128` vanilla-like config, `0094`/`0095`/`0096`/`0098`/
`0101`/`0104` old-feature ports, and the eleven new ports recorded in the
2026-08-07 section below. A full-chain replay (base then features in file
order) reproduces the pre-split applied tree byte-identically, so no mechanic
changed during the split.

The ledger rows below cover the original 0003 groups and the new ports:

| Local work | Classification | Immutable source | Original author | License | Local adaptation |
| --- | --- | --- | --- | --- | --- |
| 17 vanilla-like mechanics | Derived/ported | [Lophine 0048 at `f4aea025`](https://github.com/LophineLabs/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/lophine-server/minecraft-patches/features/0048-Add-Vanilla-like-experience-Config.patch) | Bacteriawa `<A3167717663@hotmail.com>` | GPL-3.0 under the Lophine repository license | Replaced Lophine TOML config access with Canvas `GlobalConfiguration`; adapted contexts to Canvas/Paper 26.2. |
| Old zombie reinforcement | Derived/ported | [Lophine 0013 at `f4aea025`](https://github.com/LophineLabs/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/lophine-server/minecraft-patches/features/0013-Old-zombie-reinforcement.patch) | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT, as explicitly listed in the [Lophine license](https://github.com/LophineLabs/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/LICENSE.md) | Added an independent Canvas YAML option under `old-feature`. |
| Old leader zombie health | Derived/ported | [Lophine 0014 at `f4aea025`](https://github.com/LophineLabs/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/lophine-server/minecraft-patches/features/0014-Old-leader-zombie-health-logic.patch) | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT, as explicitly listed in the [Lophine license](https://github.com/LophineLabs/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/LICENSE.md) | Added an independent Canvas YAML option under `old-feature`. |
| Six command-block gates, global-region execution route, and owning-region output hop | Original to this fork | No external implementation used | wosnxn123 | GPL-3.0 | Uses Canvas `AbstractCommandExecution.executeOnGlobal` to restore command blocks without bypassing Folia ownership rules, and hops command output back to the command block's owning region via `RegionizedTaskQueue.queueOrExecuteTickTask`. |
| Spawn invulnerable time | Derived/ported | [Lophine `Spawn-invulnerable-time` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0096-Spawn-invulnerable-time.patch) | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT, as explicitly listed in the [Lophine license](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/LICENSE.md) | Replaced the Lophine TOML config read with Canvas `GlobalConfiguration.oldFeature.spawnInvulnerableTime`; hunk contexts unchanged. |
| Old explosion damage calculator | Derived/ported | [LeavesMC/Leaves `0134-Old-wet-tnt-explode-behavior.patch` at `3e96b237`](https://github.com/LeavesMC/Leaves/blob/3e96b237749a960f297f211d439ffc9ea7fd2381/leaves-server/minecraft-patches/features/0134-Old-wet-tnt-explode-behavior.patch), reached via [Lophine `Leaves-Old-Explosion-Damage-Calculator` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0104-Leaves-Old-Explosion-Damage-Calculator.patch) | MC_XiaoHei `<xor7xiaohei@gmail.com>`, relayed by Helvetica Volubi `<suisuroru@blue-millennium.fun>` | **GPL-3.0**, as declared in the patch body — the Lophine MIT opt-in does **not** apply to this patch | Replaced the Lophine TOML config read with Canvas `GlobalConfiguration.oldFeature.oldExplosionDamageCalculator`; retained the `// Leaves` source marker. |
| Old raid behavior | Derived/ported | [LeavesMC/Leaves `0114-Old-raid-behavior.patch` at `bda7e406`](https://github.com/LeavesMC/Leaves/blob/bda7e406b995290234e33283a181e33467ceda38/leaves-server/minecraft-patches/features/0114-Old-raid-behavior.patch), reached via [Lophine `Leaves-Old-raid-behavior` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0098-Leaves-Old-raid-behavior.patch) | huanli233 `<392352840@qq.com>`, relayed by Helvetica Volubi `<suisuroru@blue-millennium.fun>` | **GPL-3.0**, as declared in the patch body — the Lophine MIT opt-in does **not** apply to this patch | Replaced four TOML config reads with `GlobalConfiguration.oldFeature.oldRaidBehavior`; used Canvas's existing `RAVAGER_SPAWN_PLACEMENT_TYPE` constant instead of re-resolving `SpawnPlacements.getPlacementType` inside the added `getRavagerSpawnLocation`; retained all `// Leaves` source markers. |
| Villager void trade | Derived/ported | [LeavesMC/Leaves `0088-Configurable-trading-with-the-void.patch` at `9d2bd3f7`](https://github.com/LeavesMC/Leaves/blob/9d2bd3f7b0a48f00df7bc8c74292338ed9c3a458/leaves-server/minecraft-patches/features/0088-Configurable-trading-with-the-void.patch), reached via [Lophine `Leaves-Configurable-trading-with-the-void` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0101-Leaves-Configurable-trading-with-the-void.patch) | violetc `<58360096+s-yh-china@users.noreply.github.com>`, relayed by Helvetica Volubi `<suisuroru@blue-millennium.fun>` | **GPL-3.0**, as declared in the patch body — the Lophine MIT opt-in does **not** apply to this patch | Replaced three TOML config reads with `GlobalConfiguration.oldFeature.villagerVoidTrade`; retained the `// Leaves` source markers. See the risk note below. |
| Vanilla end portal teleportation | Derived/ported | [Lophine `0028-Kaiiju-Vanilla-end-portal-teleportation` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0028-Kaiiju-Vanilla-end-portal-teleportation.patch), origin KaiijuMC/Kaiiju | MrHua269 `<mrhua269@gmail.com>`, co-authored Sofiane H. Djerbi `<46628754+kugge@users.noreply.github.com>` | **GPL-3.0**, per the Kaiiju license pinned in the patch body | Gated by `vanillaLikeExperience.vanillaEndPortalTeleportation`; end-platform creation captures a final local (`finalDestination`) because Canvas reassigns `destination`. |
| Vanilla random for players | Derived/ported | [Lophine `0034-Add-config-for-vanilla-random` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0034-Add-config-for-vanilla-random.patch), origin Luminol | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT opt-in | Gated by `useLegacyRandomSourceForPlayers`. |
| Tripwire behavior config | Derived/ported | [Lophine `0045-Add-config-to-modify-tripwire-behavior` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0045-Add-config-to-modify-tripwire-behavior.patch), origin Luminol | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT opt-in | Gated by `tripwireBehavior` enum; the `TripwireBehavior` enum lives at `GlobalConfiguration` level so NMS patches can reference it. |
| Vanilla hopper | Derived/ported | [Lophine `0065-Leaves-Vanilla-Hopper` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0065-Leaves-Vanilla-Hopper.patch), origin LeavesMC/Leaves | MrHua269 `<mrhua269@gmail.com>` | **GPL-3.0** (Leaves repository license; no MIT opt-in applies) | Gated by `vanillaHopper`. |
| Tick-sequence item merge | Derived/ported | [Lophine `0093-Modify-merge-ItemEntity-logic` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0093-Modify-merge-ItemEntity-logic.patch) | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT opt-in | Gated by `followTickSequenceMerge`. |
| Catch update suppression crash | Derived/ported | [Lophine `0117-Leaves-Catch-update-suppression-crash` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0117-Leaves-Catch-update-suppression-crash.patch), origin LeavesMC/Leaves | Bacteriawa `<A3167717663@hotmail.com>`, co-authored violetc `<58360096+s-yh-china@users.noreply.github.com>` | **GPL-3.0**, as declared in the patch body | Adapted to `io.canvasmc.canvas.util.UpdateSuppressionException` (no Leaves event/logger dependencies); gated by `catchUpdateSuppression`. |
| CCE update suppression | Derived/ported | [Lophine `0118-Leaves-CCE-update-suppression` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0118-Leaves-CCE-update-suppression.patch), origin LeavesMC/Leaves | Bacteriawa `<A3167717663@hotmail.com>`, co-authored violetc | **GPL-3.0**, as declared in the patch body | Same adaptation; gated by `cceUpdateSuppression`. |
| TrapDoorBlock Paper revert | Derived/ported | [Lophine `0121-Revert-TrapDoorBlock-changes-form-Paper` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0121-Revert-TrapDoorBlock-changes-form-Paper.patch), origin Luminol | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT opt-in | Gated by `revertTrapdoorChanges`. |
| Prevent item-drop loss on suppression | Derived/ported | [Lophine `0122-Leaves-Prevent-loss-of-item-drops-due-to-update-supp` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0122-Leaves-Prevent-loss-of-item-drops-due-to-update-supp.patch), origin LeavesMC/Leaves | Helvetica Volubi, co-authored violetc | **GPL-3.0**, as declared in the patch body | Only effective when `catchUpdateSuppression` converts the crash. |
| Old block remove behaviour | Derived/ported | [Lophine `0124-Leaves-Old-Block-remove-behaviour` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0124-Leaves-Old-Block-remove-behaviour.patch), origin LeavesMC/Leaves | Helvetica Volubi, co-authored violetc | **GPL-3.0**, as declared in the patch body | Gated by `oldBlockRemoveBehaviour`. |
| Mob fire and explosion rules | Derived/ported | [Lophine `0125-MiniTweaks-mob-fire-and-explosion-rules` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0125-MiniTweaks-mob-fire-and-explosion-rules.patch), origin MiniTweaks | Bacteriawa `<A3167717663@hotmail.com>` | **GPL-3.0** (Lophine repository license; no MIT opt-in for this author) | Split into four flags: `noGhastBlockBreaking`, `noCreeperBlockBreaking`, `disableGhastFire`, `disableBlazeFire`. |
| Vanilla ender pearl loading | Derived/ported | [Lophine `0130-Restore-vanilla-ender-pearl-loading` at `0724ba3f`](https://github.com/LophineLabs/Lophine/blob/0724ba3fa9bec83d2dc4b8a68d576a187f7d0067/lophine-server/minecraft-patches/features/0130-Restore-vanilla-ender-pearl-loading.patch) (`0049` on `ver/26.2@fc3415e6`) | Bacteriawa `<A3167717663@hotmail.com>` | **GPL-3.0** (Lophine repository license) | Replaces the Canvas upstream `canvas:pearls` SavedData persistence removed by fork-original `0129`; pearls save into player data (vanilla `ender_pearls` layout) via a region-owned volatile snapshot updated on the pearl's region thread. |
| Region format framework + Buffered Linear | Derived/ported | [Leaf `0107-Luminol-Configurable-region-format-framework` at `a05f8902`](https://github.com/Winds-Studio/Leaf/blob/a05f8902de772298bbdf28142eef6cef003ea5c7/leaf-server/minecraft-patches/features/0107-Luminol-Configurable-region-format-framework.patch), origin [LuminolMC/Luminol](https://github.com/LuminolMC/Luminol) (framework + B_LINEAR) | Helvetica Volubi relay; framework by LuminolMC | **GPL-3.0-only**, as declared in the patch body | Packages relocated `abomination.*`/`me.earthme.luminol.*` → `io.canvasmc.canvas.regionformat`; Leaf TOML config replaced by `GlobalConfiguration.RegionFormat` with `initFormat()` in `postLoad`. |
| Linear V2 region format | Derived/ported | [Leaf `abomination/LinearRegionFile.java` at `a05f8902`](https://github.com/Winds-Studio/Leaf/blob/a05f8902de772298bbdf28142eef6cef003ea5c7/leaf-server/src/main/java/abomination/LinearRegionFile.java), origin [xymb-endcrystalme/Abomination](https://github.com/xymb-endcrystalme/Abomination) | Xymb `<xymb@endcrystal.me>` | **GPL-3.0-only** (Abomination) | Repackaged to `io.canvasmc.canvas.regionformat.LinearRegionFile`; upstream marks it unstable (data-loss warning) — B_LINEAR is the recommended Linear variant. |
| Plugin API compat (NMS) | Derived/ported | [Lecithin `minecraft-patches/features` at `fd9e884`](https://github.com/LophineLabs/Lecithin/tree/fd9e884/lecithin-server/minecraft-patches/features) (patches 0001-0006) | TinyYana `<yanasakuranight@gmail.com>` | **GPL-3.0** (Lecithin/Lophine/Folia inheritance) | Consolidated into fork `0140-Lecithin-plugin-API-compat.patch`; config gates → `GlobalConfiguration.pluginCompat`; unload-lock read-guard bridged to Canvas's native world-stage protection via `LevelUnloadStateLockAdapter` (Luminol's `SimpleReferenceRWLock` subsystem deliberately NOT ported); D-40 diagnostics re-hooked at Canvas's `TeleportValidationException` sites; event hooks re-anchored after Canvas's own `EntityTeleportAsyncEvent`. |
| Plugin API compat (paper layer) | Derived/ported | [Lecithin `paper-patches/features` at `fd9e884`](https://github.com/LophineLabs/Lecithin/tree/fd9e884/lecithin-server/paper-patches/features) (0002-0012, 0014) | TinyYana `<yanasakuranight@gmail.com>` | **GPL-3.0** (inheritance) | Consolidated into fork `paper-patches/features/0001-Lecithin-plugin-API-compat.patch` (first fork patch in the Canvas paper layer); Lecithin 0013 (scoreboard) NOT ported — Canvas upstream already opens those APIs behind `ensureMainThread` guards, an equivalent implementation. 0009/0010 re-anchored onto Canvas's `PluginTeleportAsyncState` teleport flow. |

### MIT opt-in is personal, not transitive

Four of the seven derived groups above name Helvetica Volubi
`<suisuroru@blue-millennium.fun>` in their `From:` header, and that author is the
only MIT opt-in listed in the Lophine license. **Three of them are still
GPL-3.0.** `Leaves-Old-Explosion-Damage-Calculator`,
`Leaves-Old-raid-behavior`, and `Leaves-Configurable-trading-with-the-void` each
carry a `Co-authored by:` line naming a different author and an explicit
`Licensed under: GPL-3.0 (https://www.gnu.org/licenses/gpl-3.0.html)` line in the
patch body, and each points at a pinned LeavesMC/Leaves commit as its real
origin. The author of a relaying commit cannot relicense a co-author's work, so
the declared GPL-3.0 governs and the immutable source recorded above is the
Leaves commit, not the Lophine one.

Only `Spawn-invulnerable-time` carries no Leaves attribution and no in-body
license declaration, so the MIT opt-in applies to it.

### Command-block output must be applied on the owning region

Recorded 2026-07-26 after fixing a defect in this fork's own command-block work.

The gates route command execution through
`AbstractCommandExecution.executeOnGlobal`, which runs the command inline only
when the caller already is the global tick thread. A command block is ticked by
the region that owns its chunk, so in practice the command is queued and runs on
a later global tick. That has two consequences any future change here must
preserve:

1. **The command source outlives `performCommand`.** It must not be closed in a
   `try`-with-resources block. `CloseableCommandBlockSource#closed` gates
   `acceptsSuccess`, `acceptsFailure`, `shouldInformAdmins` and the
   `sendSystemMessage` body, so closing it before the queued command runs
   discards all output silently.
2. **Output application is region-owned work.** `sendSystemMessage` writes
   `lastOutput` and calls `onUpdated`, which reads the block state and re-sends a
   block update. Both `CommandBlockEntity` and `MinecartCommandBlock` override
   `threadCheck()` with `TickThread.ensureTickThread`, so doing this from the
   global tick thread would throw. It is therefore wrapped in
   `BaseCommandBlock#runOnOwningRegion`, which each subclass implements with
   `RegionizedTaskQueue#queueOrExecuteTickTask` against its own chunk. That call
   runs inline when the thread already owns the chunk, so a command that stays
   within one region keeps vanilla's same-tick behaviour.

`successCount` and `lastOutput` are `volatile` because they are written from the
region that ran the command and read by comparators and the block GUI.
`successCount` is reset to 0 before dispatch and incremented inside the hop, so
for a command whose execution is genuinely deferred, comparators and conditional
chain blocks observe the result one tick late. That latency is inherent to
running cross-region commands off the ticking region and cannot be removed
without blocking the region thread.

### `villager-void-trade` risk note

This option is not a bug fix. Enabling it deliberately disables two Paper
security fixes — the villager boat exploit fix in `PlayerList` and the
"merchant inventory not closing on entity removal" fix in `ServerLevel` — and
relaxes `MerchantMenu.stillValid` from a reach check to an identity comparison
on the trading player. Under Canvas region threading the retained menu can read
a villager owned by a different region, or one already unloaded. It defaults to
`false` and must stay opt-in.

The related `VanillaLikeExperience` and `OldFeature` entries in
`canvas-server/src/main/java/io/canvasmc/canvas/GlobalConfiguration.java` are
Canvas configuration adaptations for the groups above. Their relationship to
the Lophine configuration structure is part of this provenance record and must
not be removed during future patch rebuilds.

#### Source repository moved: `LophineCraft` → `LophineLabs`

Recorded 2026-07-26. The links above are pinned to the immutable commit
`f4aea025c11c598f285d3c47198c62397a0daba8`, which is the revision this fork
actually ported from, so they remain the authoritative provenance record. Only
the organisation segment of each URL was rewritten to the current name; the
commit, paths, content, authors, and licenses are unchanged.

Two upstream changes affect how a reviewer locates these sources today:

1. **Organisation rename.** `LophineCraft/Lophine` is now
   [`LophineLabs/Lophine`](https://github.com/LophineLabs/Lophine). GitHub still
   serves the old URLs via HTTP 301, and `f4aea025` remains reachable under the
   new organisation. Old links in git history must not be rewritten.
2. **Hard fork and patch renumbering.** On 2026-07-17 Lophine hard-forked from
   Luminol. The default branch became `ver/26.2-hardfork` (renamed upstream to `ver/26.2-hardfork` on 2026-08-20; our pin `ba77795f` remains a direct ancestor, verified via merge-base) and the feature patch
   set grew from 49 to 130 files, which renumbered all three sources. The patch
   contents at the new numbers are byte-identical to the revisions ported here
   (verified 2026-07-26 against `ver/26.2-hardfork@0724ba3f`):

   | Ported source at `f4aea025` | Same patch on `ver/26.2-hardfork` | Content delta |
   | --- | --- | --- |
   | `0048-Add-Vanilla-like-experience-Config.patch` | `0129-Add-Vanilla-like-experience-Config.patch` | none (identical) |
   | `0013-Old-zombie-reinforcement.patch` | `0094-Old-zombie-reinforcement.patch` | none (identical) |
   | `0014-Old-leader-zombie-health-logic.patch` | `0095-Old-leader-zombie-health-logic.patch` | none (identical) |

Because Lophine renumbers patches when its patch set changes, a future
re-verification must locate these sources **by file-name keyword**
(`Vanilla-like-experience-Config`, `Old-zombie-reinforcement`,
`Old-leader-zombie-health-logic`) rather than by patch number.

The MIT opt-in for Helvetica Volubi `<suisuroru@blue-millennium.fun>` is still
listed in the current
[`LICENSE.md`](https://github.com/LophineLabs/Lophine/blob/ver/26.2-hardfork/LICENSE.md),
whose full license texts now live in
[`licenses/GPL.md`](https://github.com/LophineLabs/Lophine/blob/ver/26.2-hardfork/licenses/GPL.md)
and
[`licenses/MIT.md`](https://github.com/LophineLabs/Lophine/blob/ver/26.2-hardfork/licenses/MIT.md).

### Leaves sources re-verified 2026-08-04

The three GPL-3.0 groups are pinned at the LeavesMC/Leaves commits recorded in
the ledger above (`3e96b237`, `bda7e406`, `9d2bd3f7`). On Leaves `master` the
same patches now live at `0125-Old-wet-tnt-explode-behavior.patch`,
`0101-Old-raid-behavior.patch`, and `0005-Configurable-void-trade.patch`. The
last commit touching any of the three files is the 1.21.11 rebase `22a763cb`
(2026-04-28); nothing changed after it. Normalized diffs (index lines and
`From:` hashes stripped) against the pinned versions show only rebase-context
adaptation — variable renames such as `player` → `serverPlayer` and
`attempt` → `i`, hunk offsets, and one `mutable.immutable()` → `mutable`
return-site change with identical semantics. No behavioral fix and no added or
removed mechanic exists upstream, so 0003 needs no re-port. As with Lophine,
locate these patches by file-name keyword, never by number.

### Patch split and new ports 2026-08-07

The single `0003-Vanilla-like-experience.patch` was split into per-feature
patches and eleven further mechanics were ported (ledger rows above). Local
numbers align with Lophine `ver/26.2-hardfork@0724ba3f`. Lophine's current
`ver/26.2@fc3415e6` renumbered the set again (49 patches); the mapping for
re-verification is:

| Local | Lophine `ver/26.2@fc3415e6` |
| --- | --- |
| `0093-Modify-merge-ItemEntity-logic` | `0012` |
| `0094-Old-zombie-reinforcement` | `0013` |
| `0095-Old-leader-zombie-health-logic` | `0014` |
| `0096-Spawn-invulnerable-time` | `0015` |
| `0098-Leaves-Old-raid-behavior` | `0017` |
| `0101-Leaves-Configurable-trading-with-the-void` | `0020` |
| `0104-Leaves-Old-Explosion-Damage-Calculator` | `0023` |
| `0117-Leaves-Catch-update-suppression-crash` | `0036` |
| `0118-Leaves-CCE-update-suppression` | `0037` |
| `0121-Revert-TrapDoorBlock-changes-form-Paper` | `0040` |
| `0122-Leaves-Prevent-loss-of-item-drops-due-to-update-supp` | `0041` |
| `0124-Leaves-Old-Block-remove-behaviour` | `0043` |
| `0125-MiniTweaks-mob-fire-and-explosion-rules` | `0044` |
| `0128-Add-Vanilla-like-experience-Config` | `0048` |

`0028` (Kaiiju end portal), `0034` (vanilla random), `0045` (tripwire
behavior), and `0065` (vanilla hopper) are **absent** from
`ver/26.2@fc3415e6` — Lophine dropped them in the 26.2 rebuild. The pinned
`0724ba3f` copies remain the authoritative source; do not re-port from
`ver/26.2` for these four.

### Ender pearl mechanism swap 2026-08-07

The Canvas upstream global `canvas:pearls` SavedData persistence
(`io.canvasmc.canvas.threadedregions.entities.EnderPearls`, the
`savePearls`/`spawnPearls` hooks, the `RegionShutdownThread` shutdown
collection, and the `RegionizedServer` autosave entry) is removed by
fork-original `0129-Remove-Canvas-ender-pearl-persistence.patch` and replaced
by `0130-Restore-vanilla-ender-pearl-loading.patch` (ledger row above). The
earlier 2026-08-07 decision to **not** port the Lophine pearl patch (recorded
in the project context on the grounds that Canvas base already persisted
pearls) was reversed at the user's request in favour of vanilla save-layout
semantics. Behavioural difference versus the removed mechanism: pearls no
longer survive player logout (Paper semantics); they survive restarts only
while the owner is online. Pre-existing `canvas:pearls` save data is ignored
after the swap.

### Linear region formats ported 2026-08-18

`0107-Luminol-Configurable-region-format-framework.patch` plus the
`io.canvasmc.canvas.regionformat` package (7 Java files, ~90KB) port the
pluggable region format framework from Winds-Studio/Leaf
`ver/26.2@a05f8902` (pinned). Formats: `MCA` (default, vanilla), `LINEAR_V2`
(`.linear`, Abomination, unstable — author warns of data loss), `B_LINEAR`
(`.b_linear`, Luminol buffered). Selected via `canvas-server.yml`
`region-format.*`; `initFormat()` runs from `GlobalConfiguration.postLoad`.
Dependencies added: `com.github.luben:zstd-jni:1.5.7-9`,
`net.openhft:zero-allocation-hashing:2026.0`.

Operational invariants a future change must preserve:

1. **No mixed formats.** A world whose on-disk region extension mismatches the
   configured format delays a crash by design (`RegionFileStorage.createNew`);
   converting an existing world requires an external converter. Do not "fix"
   this into silent fallback.
2. **The Lophine `0007` variant of this framework was NOT used**: it is
   anonymised (original commit hidden) and carries no immutable source link,
   failing this ledger's requirements. Leaf `a05f8902` is the pinned source.
3. **Format init must precede world load** — currently guaranteed by
   `postLoad`; if config loading moves, re-verify spawn-region files are
   created in the configured format on a fresh world.

Verified locally 2026-08-18: fresh-world boots create the correct extension
for all three formats; B_LINEAR and LINEAR_V2 worlds restart and re-read
cleanly (Done 21s / 48s, no ERROR).

### Plugin API compatibility layer ported 2026-08-18

Lecithin (`dev/26.2@fd9e884`, pinned) restores Folia-broken Bukkit/Paper
plugin APIs. Ported as: `0140-Lecithin-plugin-API-compat.patch` (NMS, 6
upstream patches), `paper-patches/features/0001` (12 upstream paper patches,
including 0014's PaperEventManager provenance dispatch), 17
`io.canvasmc.canvas.compat.Lecithin*` helper classes, and the
`PluginCompat` config section (24 gated flags).

Re-ported 2026-08-19 from `fd9e884` ("generalise the legacy sync scheduler
shim to execution provenance"): the jar-SHA rule table
(`LecithinPluginSchedulerDispatch`, 652 lines) is gone — one whitelist per
plugin jar that silently broke on any EssentialsX rebuild — replaced by
`LecithinExecutionProvenance` (REGION/GLOBAL/ENTITY owner derived from
`TickRegionScheduler` / global tick / startup thread / async `PlayerEvent`
dispatch), `LecithinDispatchedTasks` (makes `BukkitTask#cancel` and
scheduler `cancelTask(s)` actually cancel redispatched tasks), and a
`PaperEventManager` hook that establishes the async event's owner for the
whole dispatch. Economy guard re-keyed on the Vault service interface
instead of jar hashes. Config: `-plugin-scheduler-dispatch`,
`+async-event-provenance`, `+async-platform-event-global-scope`.

Decisions a future change must preserve:

1. **The unload-lock bridge is adapter-only.** Lecithin's teleport-event
   patches depend on Lophine 0026's `levelUnloadStateLock`
   (`SimpleReferenceRWLock`). Canvas owns world unloading itself
   (`canvas$worldStageLock` + `canvas$unloadTicket` + `canvas$joiningPlayers`,
   `WorldShutdownThread`), so porting Luminol's lock subsystem would create two
   uncoordinated protection systems. `LevelUnloadStateLockAdapter` maps
   `acquireRead`/`releaseRead` onto Canvas's joining-players reference set.
   Do not port Lophine 0026 wholesale.
2. **Lecithin 0013 (scoreboard) is intentionally absent** — Canvas upstream
   already opens those five methods behind `ensureMainThread`. Porting it
   would double-cover the same surface.
3. **Canvas diverges at the same hooks**: Canvas's `EntityTeleportAsyncEvent`
   (own event, setTo-capable) fires before the ported Bukkit events;
   Canvas throws `TeleportValidationException` where Folia silently returns
   false (D-40 diagnostics hook the throw sites instead). Upstream Canvas
   changes to either site require re-anchoring 0140/paper-0001.

Verified 2026-08-18: applyAllPatches + compile + paperclip green on CI;
local boot smoke (Done 21.4s, no ERROR) with the `plugin-compat` config
section generated. Pre-port backup branch: `backup/pre-lecithin-2026-08-18`.

Verified 2026-08-19 (fd9e884 re-port): full NMS 23-patch chain replay +
round-trip (`ROUNDTRIP_OK`), clean paper-baseline apply of the regenerated
paper-0001 (`PAPER_ROUNDTRIP_OK`, all 11 files), config option/field
closure check exact. Patches carry replay-computed blob indexes. CI green
(`7c70bf6d`; first attempt failed compile - the CompatConfig adaptation
dropped the nested `LecithinExecutionProvenance.Owner` import). Local
35-plugin smoke (fresh world, Done 130.1s, clean stop, zero compat-layer
errors): config migration confirmed, Vault-interface economy guard hooked
EssentialsX, PaperLib adapter active on Multiverse/QuickShop.

Follow-up review 2026-08-20 of Lecithin `dev/26.2` `fd9e8840..35e9ad78` (19
commits). Nothing follows up the execution-provenance refactor itself -
`LecithinExecutionProvenance`, `LecithinDispatchedTasks` and
`LecithinCallerContextDispatch` are untouched upstream, and our ported
paper-patches 0002-0014 do not appear in the changed-file set at all. Three
items were ported:

1. **`c63f47f4` end-gateway parity (bugfix).** `LecithinTeleportEvents` now
   fires `PlayerTeleportEndGatewayEvent` / `EntityTeleportEndGatewayEvent` with
   a `CraftEndGateway` for `END_GATEWAY`. The subclasses share the parent's
   `HandlerList`, so parent listeners always fired, but a listener declared on
   the subclass - the only way to reach `getGateway()` - never did. The new
   `gatewayAt(Entity)` resolves the block entity through
   `entity.portalProcess` + `isSamePortal`, and adds a `TickThread` ownership
   check this platform needs and Paper does not, so a non-owned block-entity
   read falls back to the plain event instead of throwing out of an event hook.
2. **`f908451a` unload-lock half ONLY (bugfix).**
   `readByLoadingDuringStartup` now holds `level.levelUnloadStateLock` across
   `syncLoadNonFull`. The other half of that commit - letting region threads
   reach the load path - is deliberately NOT ported: our
   `getCurrentRegion() != null` refusal is precisely what keeps this fork clear
   of the permanent `NON_FULL_CHUNK_LOAD` ticket leak upstream had to add
   `LecithinForeignWorldTicketUpdates` + nms-0012 to fix (`syncLoadNonFull`
   has no `finally`, so the "World check failed" throw escapes before
   `removeTicketAtLevel`, pinning a chunk holder and a region section for the
   life of the server). Taking the widening without that bundle would import
   the leak; a future port must take all three together or none.
3. **`d0872f23` kill-switch text (doc correctness, adapted).** All 18 mentions
   across 13 compat classes named `-Dlecithin.compat.*` JVM properties that no
   code reads - four of them in operator-facing runtime log lines. Rewritten to
   the real `canvas-server.yml` keys (`plugin-compat.<kebab-case>`), each
   verified against `GlobalConfiguration.PluginCompat`. Runtime-verified in the
   2026-08-20 smoke: the PaperLib line now prints
   `disable with plugin-compat.paper-lib-environment: false`.

Everything else in that range is new feature scope beyond our ported patches
(end-gateway exit adjustment, move rollback, respawn events,
`PlayerSpawnLocationEvent` in the config phase, `getTPS`/`getRespawnLocation`
off-region, vanilla portal events, a `/lecithin` command tree) or rebase/docs
noise: `fb7561f4` shifted the index lines of all six ported nms patches by
+1/-1 with no content change.

Lophine renamed `dev/26.2-hardfork` to `ver/26.2-hardfork` on 2026-08-20; our
pin `ba77795f` is still a direct ancestor (merge-base verified) and the patch
directory had zero changes in that window, so all 21 ported items remain
byte-identical. Links in this file were updated because GitHub does not
redirect renamed branches. Leaves `master` and Leaf `ver/26.2` are
byte-identical to their pins (0 commits).

### License notices

- Repository license: [`LICENSE`](LICENSE)
- Bundled upstream notices:
  [`canvas-server/src/main/resources/META-INF/licenses/`](canvas-server/src/main/resources/META-INF/licenses/)
- Lophine notice:
  [`LICENSE_LOPHINE`](canvas-server/src/main/resources/META-INF/licenses/LICENSE_LOPHINE)

## Handling a provenance or license report

1. Preserve the report, relevant commits, and evidence; do not rewrite history.
2. Pause release of the disputed change while its origin and license are
   checked.
3. Publish a factual scope: affected files, verified source, license, timeline,
   and remediation commit.
4. Acknowledge a confirmed omission directly. Do not use intent, the amount of
   copied code, quick remediation, or the reporting channel to minimize it.
5. Do not require private contact before accepting a public report. Offer both
   public and private channels, especially for sensitive evidence.
6. Keep personal wellbeing discussions separate from the technical and license
   response; never use project deletion or maintainer departure to discourage
   scrutiny.
7. Correct the source record, notices, and review process, then audit adjacent
   patches for the same failure mode.
