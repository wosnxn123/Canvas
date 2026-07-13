## Fork 修改说明

本 fork 基于 Canvas（Folia 下游），针对上游 Canvas 的两个问题进行了修改：
1. 上游 Canvas **硬编码禁用命令方块**；
2. Paper/Folia **修改了大量原版机制**（刷线机、TNT 复制、永久破坏等）。

### 1. 重新启用命令方块（可通过配置开关）

上游 Canvas 在多处代码中硬编码禁用了命令方块。本 fork 在 feature patch 0003 中将 5 处禁用点改为受配置控制：

- 开关：`config/canvas-server.yml` → `vanilla-like-experience.command-blocks`（默认 `true`）
- 开启时通过 ACE API 的 `io.canvasmc.canvas.threadedregions.commands.AbstractCommandExecution.executeOnGlobal` 将命令方块执行路由到 **global region 线程**，可安全执行跨区域命令（如 `/tp`、`/give`、`/scoreboard`）
- 关闭时（`command-blocks: false`）保持上游 Canvas 的禁用行为

默认 `true`（保留原版命令方块行为）。已验证：命令方块正常使用，跨区域 TP 无报错。

### 2. Vanilla-like Experience 配置（原版机制还原）

主开关：`config/canvas-server.yml` → `vanilla-like-experience.enabled`（默认 `false`）。开启后还原 Paper/Folia 修改过的原版机制（移植自 LophineCraft/Lophine 0048）：

| 机制 | 说明 |
|------|------|
| 刷线机（Tripwire hook） | 跳过放置校验，启用刷线机 |
| TNT & 沙子复制 | 通过活塞 desync 实现 |
| 永久破坏 | 可破坏基岩、末地传送门框架 |
| 无头活塞 | 允许形成 |
| 原版生物刷新 | 计入全部生物（count all mobs） |
| 实体碰撞 | 无上限 |
| 玩家挤压伤害 | 启用 |
| 幻翼 / 失眠原版行为 | 还原 |
| TNT 每 tick 上限 | 移除（无上限） |
| 漏斗 / 蜜蜂 / 物品合并 / 末地传送门传送 | 还原原版行为 |

当 `enabled: false` 时，Paper/Folia 的各项 per-mechanic 配置照常生效。

### 分支

`main` 跟踪 Canvas 上游 `main`，也是服务器构建和部署使用的分支。旧的 `pre-merger/26.2` 说明已经过时，不再作为当前维护基线。

### 补丁清单与来源

**基础 patch**（`canvas-server/minecraft-patches/base/`，Canvas 上游维护，fork 不改）：
`0001-Rebrand`、`0002-Remove-Vanilla-Profiler`、`0003-Remove-Dead-Old-Watchdog-Code`、`0004-Per-world-Canvas-configs` / `0004-Fixup-Region-Threading`、`0005-Region-Threading`、`0006-Canvas-RegionizedWorldData`、`0007-Replace-Moonrise-Executor`、`0008-Purpur-Ender-Chest-6-Rows-Config`

**Feature patch**（`canvas-server/minecraft-patches/features/`）：

| 补丁 | 来源 | 说明 |
|------|------|------|
| `0001-Purpur-Alternative-Keepalive` | Canvas 上游 | — |
| `0002-Disable-Criterion-Trigger-Config` | Canvas 上游 | — |
| `0003-Vanilla-like-experience` | **本 fork**（移植 + 原创） | 22 个 hunk：17 vanilla 机制移植自 [LophineCraft/Lophine](https://github.com/LophineCraft/Lophine) 的 `0048-Add-Vanilla-like-experience-Config.patch`（作者 Bacteriawa）；5 命令方块 gate 为本 fork 原创（用 Canvas ACE `AbstractCommandExecution.executeOnGlobal` 路由到 global region 线程） |

2026-07-14 已与 `LophineCraft/Lophine` `dev/26.2@f4aea025` 复核：0048 仍覆盖相同的 17 个原版机制；0013 和已更名的 `0014-Old-leader-zombie-health-logic.patch` 与本 fork 的两个 OldFeature 选项语义一致。

**Canvas 自有源码改动**（`canvas-server/src/main/java/io/canvasmc/canvas/GlobalConfiguration.java`，非 patch）：新增 `VanillaLikeExperience` 配置段（`enabled` + `commandBlocks` 字段）。

命令方块修复 + Vanilla-like Experience 都在 feature patch `0003-Vanilla-like-experience.patch`（一个补丁），配置都在 `config/canvas-server.yml` 的 `vanilla-like-experience` 段（`enabled` 控原版机制，`command-blocks` 控命令方块）。基础 patch（`0004`/`0005`）保持上游 Canvas 原样（命令方块禁用），由 0003 重新启用并受配置控制。

### 上游更新须知

合并上游 Canvas 后，本 fork 的 `0003` **可能需要重新适配**：
- 17 个 vanilla 机制 hunk 依赖 Paper-config 代码行（如 `allowPlayerCrammingDamage`、`maxEntityCollisions`、`allowPistonDuplication`）。上游 Paper 重构这些行 → hunk context 变 → 应用失败，需重新生成。
- 5 个命令方块 gate hunk 依赖 Canvas 上游的禁用点（`if(true) return false` 等）。上游改禁用方式 → 需重新适配。
- 命令执行包路径（`command.execution` / `threadedregions.commands`）若上游再重构，两个分支都要跟着改。
- 基础 patch + feature 0001/0002 随上游 merge 自动更新，无需手动处理。

### 配置与热重载

编辑 `config/canvas-server.yml`，然后执行 `/canvas reload` 或重启服务器使其生效。

### 兼容性

| 服务端 | 兼容性 | 说明 |
|--------|--------|------|
| Canvas 26.2 | ✅ 完全兼容 | 命令方块与 vanilla-like 均可用，本 fork 的目标环境 |
| 上游 Folia | 不适用 | 这是 Canvas fork，不是 Folia fork |
| Paper / Purpur / Spigot | 不适用 | 这是 Canvas fork，依赖 Folia 区域化线程 |

### 构建方式

```bash
./gradlew applyAllPatches
./gradlew :canvas-server:createPaperclipJar
```

**Java 版本要求**：Java 25

---

![title](./canvas_title.png)

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)  
[![GitHub stars](https://img.shields.io/github/stars/CraftCanvasMC/Canvas)](https://github.com/CraftCanvasMC/Canvas)  
[![GitHub forks](https://img.shields.io/github/forks/CraftCanvasMC/Canvas)](https://github.com/CraftCanvasMC/Canvas)  

CanvasMC is a fork of Folia introducing numerous fixes to region threading to improve stability, whilst also adding
various performance enhancements to the dedicated server

---
[![bStats Graph Data](https://bstats.org/signatures/server-implementation/Canvas.svg)](https://bstats.org/plugin/server-implementation/Canvas)
---

## Why Canvas?

- ### Improved server stability
  - SpottedLeaf authored Folia, and his code is no doubt absolutely incredible, however there are still a lot of
    unresolved bugs and issues still in Folia. Canvas comes packaged with over **80** fixes to region threading to try
    and fix these issues, and has plans to upstream its patches to Folia. At the time of writing, some of these patches
    are already in the process of being upstreamed in open PRs.
- ### Numerous optimizations
  - Canvas is not only focused on trying to complete and stabilize region threading. Canvas also comes with numerous
    performance enhancements to help ensure your server not only is stable, but also smooth and fast at high player
    counts
- ### Extensive configuration
  - Canvas has a wide array of customization and performance options you can tweak to your liking. The default
    configurations provided by Canvas are aimed for **Vanilla compatibility first** and performance **second.**
- ### Faster updates
  - While Canvas is a fork of Folia, Canvas upstreams from Paper, meaning we can update the region threading patch on
    our own without having to rely on Folia for an update first. This allows us to even update to newer Minecraft
    versions before Folia even starts updating
- ### Spark region profiling
  - Canvas includes a modified version of the Spark profiler allowing you to profile specific regions rather than the
    whole server with Spark, replacing the Folia profiler provided in LeafPile.
- ### Knowledgeable team
  - Our development team is comprised of skilled developers, dedicating hundreds of hours of our personal time and
    effort working on Canvas and other projects under our organization

## What does Canvas upstream from?

Canvas is a bit of an odd fork in the sense of where we upstream from. Yes, technically we are a Folia fork, given our
patches are infact based on Folia. However, we bundle the Folia patches in our own repository, allowing us to upstream
from **Paper**, making us sort of a mix of a Folia fork and a Paper fork. We are unsure which one we technically would
classify as, so we just call ourselves a Folia fork since that's just easier to understand.

---

## Getting Started

### Downloading & Running

1. Download the latest server JAR from the **Downloads** page on [canvasmc.io](https://canvasmc.io/downloads/canvas).  
2. Launch using Java (Java 25+ required) with your preferred arguments and configuration.

### Building from Source

**Requirements:**

- Java 25
- Git (configured with name/email)

**Common build commands:**

```bash
./gradlew applyAllPatches # Applies all patches to construct the Canvas source
./gradlew createPaperclipJar # Creates the paperclip jar
./gradlew runDevServer # Starts a development server locally
```

## Documentation & Resources

* **Official Documentation**: [https://docs.canvasmc.io](https://docs.canvasmc.io)
* **Community & Support**: Join the Canvas [Discord](https://canvasmc.io/discord)
* **Issue Tracker / Contributing**: Use this GitHub repo for reporting bugs, proposing features, and submitting
                                    pull requests

---

## Contributing

We welcome many forms of contributions:

* Code (bug fixes, features)
* Documentation improvements
* Testing & bug reporting
* Community help & support
* Donations to help support the developers

See the [Canvas Contributing Guide](https://docs.canvasmc.io/canvas/developers/contributing/canvas/) for more detail.

---

## Compatibility & Notes

* Canvas is a fork of **Folia** and is *not* a drop-in replacement for Purpur, Paper, or other non-Folia forks. It's
  intended primarily for environments already using Folia or Folia-based forks. If you need help migrating from Folia or
  from other non-Folia forks, please reach out in our discord and we will be happy to help
* The project adheres strictly to Folia’s threading and safety rules and does *not* permit bypassing them. While yes,
  this is the bare minimum, we say this as to showcase our intent for wanting to try not to permit potentially unsafe
  actions being executed in the server, say via a plugin not marked as supporting Folia for example. If you require
  this for your own use, you are advised to please search for another fork of Folia or fork the repository yourself and
  make the necessary changes you require

---

## Licenses and Acknowledgements

Canvas' license inherits from its upstream project sources. As such, Canvas is licensed under [GNU General Public
License V3](https://github.com/CraftCanvasMC/Canvas/blob/main/LICENSE) along with the patches authored by the CanvasMC
team unless stated otherwise within the patch itself.

Canvas incorporates patches inspired by or derived from other Minecraft projects (e.g. **Lithium**, **Leaf**,
**Luminol**, etc) alongside our own patch sets. Canvas includes a full set of licenses from these sources available in
the `/canvas-server/src/main/resources/META-INF/licenses/` directory of our repository.

Canvas is also dedicated to trying to showcase our contributors in our software. From a "Contributor" role on our
Discord to being mentioned by name inside our own software in our version command, we at CanvasMC are always trying to
make an effort to showcase our community and contributors since frankly, Canvas would not be what it is without them.

---

## Donating

You can donate to support Canvas' work by donating [here](https://ko-fi.com/dueris)
