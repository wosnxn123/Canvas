## Fork 修改说明

本 fork 基于 Canvas 26.2（Folia 下游），针对上游 Canvas 的两个问题进行了修改：
1. 上游 Canvas **硬编码禁用命令方块**；
2. Paper/Folia **修改了大量原版机制**（刷线机、TNT 复制、永久破坏等）。

### 1. 重新启用命令方块（始终启用）

上游 Canvas 在 6 处代码中通过 `if(true) return false`、`return false`、抛出 `UnsupportedOperationException` 等方式硬编码禁用了命令方块。本 fork：

- 删除了这 6 处禁用代码；
- 通过 ACE API 的 `io.canvasmc.canvas.threadedregions.commands.AbstractCommandExecution.executeOnGlobal` 将命令方块执行路由到 **global region 线程**，使命令方块可以在区域化多线程环境下安全执行跨区域命令（如 `/tp`、`/give`、`/scoreboard`）。

**始终启用**，无需配置。已验证：命令方块正常使用，跨区域 TP 无报错。

### 2. Vanilla-like Experience 配置（原版机制还原）

新增主开关：`config/canvas-server.yml` → `vanilla-like-experience.enabled`（默认 `false`）。开启后还原 Paper/Folia 修改过的原版机制（移植自 LuminolMC/Lophine 0048）：

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

| 分支 | 说明 |
|------|------|
| `main` | 跟踪上游 Canvas main + 本 fork 改动 |
| `pre-merger/26.2` | 26.2 线，**服务器实际运行**的分支 |

命令方块修复位于基础 patch（`pre-merger/26.2` 的 `0004-Fixup-Region-Threading` / `main` 的 `0005-Region-Threading`）；Vanilla-like Experience 位于 feature patch `0003-Vanilla-like-experience.patch`。

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

CanvasMC is a high performance fork of Folia aiming to provide a stable and consistent region threading environment, alongside tons
of optimizations and performance features for large scale servers.

---
[![bStats Graph Data](https://bstats.org/signatures/server-implementation/Canvas.svg)](https://bstats.org/plugin/server-implementation/Canvas)
---

## Features & Highlights

### Alternative Scheduler
- Canvas provides a scheduler written by the team called the `AFFINITY` scheduler, which contains configurable features that increase performance immensely.

### Optimized Chunk Generation
- Canvas replaces a rewritten chunk system pool, providing further optimizations that help with scaling and single-threaded throughput

### Extensive Configuration
- Fine-tune aspects of your server with fully documented configuration options and performance settings.

### Proper Region Profiling
- Canvas introduces a full Spark profiler that is fully compatible with region threading, replacing the limited Folia profiling engine.

### Powerful and Optimized
- By fixing **numerous** Folia bugs and crashes, Canvas delivers a high-performance, stable, and reliable experience.

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
./gradlew runDev # Starts a development server locally
```

## Documentation & Resources

* **Official Documentation**: [https://docs.canvasmc.io](https://docs.canvasmc.io)
* **Community & Support**: Join the Canvas [Discord](https://canvasmc.io/discord)
* **Issue Tracker / Contributing**: Use this GitHub repo for reporting bugs, proposing features, and submitting pull requests
* **Donations / Sponsorship**: Support development on [Ko-fi](https://ko-fi.com/dueris)

---

## Contributing

We welcome many forms of contributions:

* Code (bug fixes, features)
* Documentation improvements
* Testing & bug reporting
* Community help & support
* Donations to help support the developers

See the [Canvas Contributing Guide](https://docs.canvasmc.io/guides/developers/contributing/canvas/) for more detail.

---

## Compatibility & Notes

* Canvas is a fork of **Folia** and is *not* a drop-in replacement for Purpur, Paper, or other non-Folia forks. It is intended primarily for environments already using Folia or Folia-based forks.
* The project adheres strictly to Folia’s threading and safety rules and does *not* permit bypassing them.

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

---

## Acknowledgments & Inspiration

Canvas incorporates patches inspired by or derived from other high-performance projects (e.g. **Lithium**, **Leaf**), along with its own custom optimizations.
