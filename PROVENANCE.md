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
| 17 vanilla-like mechanics | Derived/ported | [Lophine 0048 at `f4aea025`](https://github.com/LophineCraft/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/lophine-server/minecraft-patches/features/0048-Add-Vanilla-like-experience-Config.patch) | Bacteriawa `<A3167717663@hotmail.com>` | GPL-3.0 under the Lophine repository license | Replaced Lophine TOML config access with Canvas `GlobalConfiguration`; adapted contexts to Canvas/Paper 26.2. |
| Old zombie reinforcement | Derived/ported | [Lophine 0013 at `f4aea025`](https://github.com/LophineCraft/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/lophine-server/minecraft-patches/features/0013-Old-zombie-reinforcement.patch) | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT, as explicitly listed in the [Lophine license](https://github.com/LophineCraft/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/LICENSE.md) | Added an independent Canvas YAML option under `old-feature`. |
| Old leader zombie health | Derived/ported | [Lophine 0014 at `f4aea025`](https://github.com/LophineCraft/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/lophine-server/minecraft-patches/features/0014-Old-leader-zombie-health-logic.patch) | Helvetica Volubi `<suisuroru@blue-millennium.fun>` | MIT, as explicitly listed in the [Lophine license](https://github.com/LophineCraft/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/LICENSE.md) | Added an independent Canvas YAML option under `old-feature`. |
| Five command-block gates and global-region execution route | Original to this fork | No external implementation used | wosnxn123 | GPL-3.0 | Uses Canvas `AbstractCommandExecution.executeOnGlobal` to restore command blocks without bypassing Folia ownership rules. |

The related `VanillaLikeExperience` and `OldFeature` entries in
`canvas-server/src/main/java/io/canvasmc/canvas/GlobalConfiguration.java` are
Canvas configuration adaptations for the groups above. Their relationship to
the Lophine configuration structure is part of this provenance record and must
not be removed during future patch rebuilds.

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
