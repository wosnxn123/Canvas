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

### `0003-Vanilla-like-experience.patch`

Local path:
`canvas-server/minecraft-patches/features/0003-Vanilla-like-experience.patch`

The patch contains three separately attributed groups:

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
   Luminol. The default branch became `dev/26.2-hardfork` and the feature patch
   set grew from 49 to 130 files, which renumbered all three sources. The patch
   contents at the new numbers are byte-identical to the revisions ported here
   (verified 2026-07-26 against `dev/26.2-hardfork@0724ba3f`):

   | Ported source at `f4aea025` | Same patch on `dev/26.2-hardfork` | Content delta |
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
[`LICENSE.md`](https://github.com/LophineLabs/Lophine/blob/dev/26.2-hardfork/LICENSE.md),
whose full license texts now live in
[`licenses/GPL.md`](https://github.com/LophineLabs/Lophine/blob/dev/26.2-hardfork/licenses/GPL.md)
and
[`licenses/MIT.md`](https://github.com/LophineLabs/Lophine/blob/dev/26.2-hardfork/licenses/MIT.md).

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
