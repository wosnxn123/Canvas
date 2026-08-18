## Fork 修改说明

本 fork 基于 Canvas（Folia 下游），针对上游 Canvas 的两个问题进行了修改：
1. 上游 Canvas **硬编码禁用命令方块**；
2. Paper/Folia **修改了大量原版机制**（刷线机、TNT 复制、永久破坏等）。

### 来源与许可证政策

本 fork 将补丁来源、原作者和许可证视为合并与发布的硬性条件，而不是发生争议后再补写的说明。完整规则、固定 commit 链接和当前自定义补丁来源台账见 [`PROVENANCE.md`](PROVENANCE.md)。

- 手工移植、重生成或 rebase 补丁时，不得删除或改写原作者、来源项目及许可证信息；
- 参考过其他项目的具体实现时，默认按“派生/移植”记录，不能只写“受启发”；
- 仅有 `META-INF/licenses` 中的许可证文件不足以替代逐补丁来源记录；
- 来源不明或许可证未核实的改动不得合并或发布；
- 收到来源或许可证质疑时，先核查并公布受影响文件、证据和修复，不以主观意图、代码量、沟通渠道或快速修复作为免责理由。

### 1. 重新启用命令方块（可通过配置开关）

上游 Canvas 在多处代码中硬编码禁用了命令方块。本 fork 在 feature patch `0003-Re-enable-command-blocks` 中将 5 处禁用点改为受配置控制：

- 开关：`config/canvas-server.yml` → `vanilla-like-experience.command-blocks`（默认 `true`）
- 开启时通过 ACE API 的 `io.canvasmc.canvas.threadedregions.commands.AbstractCommandExecution.executeOnGlobal` 将命令方块执行路由到 **global region 线程**，可安全执行跨区域命令（如 `/tp`、`/give`、`/scoreboard`）
- 关闭时（`command-blocks: false`）保持上游 Canvas 的禁用行为

默认 `true`（保留原版命令方块行为）。已验证：命令方块正常使用，跨区域 TP 无报错。

### 2. Vanilla-like Experience 配置（原版机制还原）

主开关：`config/canvas-server.yml` → `vanilla-like-experience.enabled`（默认 `false`）。开启后还原 Paper/Folia 修改过的原版机制（移植自 LophineLabs/Lophine 0048）：

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

### 3. Old Feature 配置（旧版机制逐项还原）

`config/canvas-server.yml` → `old-feature` 段。**独立于** `vanilla-like-experience.enabled`，逐特性单独开关，全部默认 `false`。已与 Lophine 上游 `OldFeatureConfig` 的 6 个字段完全对齐。

| 选项 | 说明 | 来源 / 许可证 |
|------|------|------|
| `old-zombie-reinforcement` | 僵尸增援固定生成普通僵尸，而非呼叫者的类型（尸壳 / 僵尸村民等） | Lophine，MIT |
| `old-leader-zombie-health` | 队长僵尸不再被立即治疗到加成后的最大生命值 | Lophine，MIT |
| `spawn-invulnerable-time` | 出生后 60 tick（3 秒）免伤；带 `BYPASSES_INVULNERABILITY` 标签的伤害（虚空、`/kill`）仍然生效 | Lophine，MIT |
| `old-explosion-damage-calculator` | 爆炸源实体处于水中时不再破坏方块类实体（船、展示框、盔甲架） | Leaves → Lophine，GPL-3.0 |
| `old-raid-behavior` | 还原 1.21 前袭击机制：`BAD_OMEN` 直接触发袭击而非在进村时转成 `RAID_OMEN`；波次生成位置改用旧版掠夺兽搜索（3 次尝试、无 96 格 Y 轴限制）；在袭击外击杀巡逻队长重新给予可叠加的 `BAD_OMEN` | Leaves → Lophine，GPL-3.0 |
| `villager-void-trade` | 村民被卸载 / 移除后交易界面仍可使用（「对虚空交易」）。**⚠ 这不是 bug 修复**：开启会关掉 Paper 的村民船 exploit 修复和「实体移除时关闭 merchant 界面」修复，并把 `MerchantMenu.stillValid` 从可达性检查降级为交易者身份比对；region threading 下持有的界面可能读到属于其他 region 或已卸载的村民。仅在明确想要这个旧 exploit 时开启 | Leaves → Lophine，GPL-3.0 |

许可证要点：上表六项的补丁 `From:` 都是 Lophine 的 MIT opt-in 作者，但其中三个在补丁体内显式声明 `Licensed under: GPL-3.0` 且各有不同 co-author，真实来源是 LeavesMC/Leaves 的固定提交——**MIT opt-in 属于作者个人，不可传递**。逐项来源、作者与许可证见 [`PROVENANCE.md`](PROVENANCE.md)。

### 4. 新增原版机制还原（2026-08-07 移植）

以下机制位于 `vanilla-like-experience` 段，**独立于** `enabled`，逐项开关，全部默认 `false`：

| 选项 | 说明 | 来源 / 许可证 |
|------|------|------|
| `vanilla-end-portal-teleportation` | 还原原版末地传送手感：保留实体动量、平台玩家生成偏移，同步生成末地平台 | Kaiiju → Lophine 0028，GPL-3.0 |
| `use-legacy-random-source-for-players` | 实体使用 per-entity 旧版随机源，还原 Folia 前原版随机序列 | Luminol → Lophine 0034，MIT |
| `tripwire-behavior` | `OFF`/`VANILLA20`/`VANILLA21`/`MIXED`：1.20/1.21 式绊线复制与混合刷线机行为；同时调整末地平台生成避免绊线复制 | Luminol → Lophine 0045，MIT |
| `vanilla-hopper` | 还原完整原版漏斗 pull 语义（逐物品移动 + 原版事件/计数处理） | Leaves → Lophine 0065，GPL-3.0 |
| `follow-tick-sequence-merge` | 物品实体按 tick 序列而非堆叠数合并，修大合并半径下物品无法合并 | Lophine 0093，MIT |
| `catch-update-suppression` | 物理/方块更新期间的 StackOverflowError/ClassCastException/IllegalArgumentException 转为记日志的 UpdateSuppressionException，不再崩 tick 循环 | Leaves → Lophine 0117，GPL-3.0 |
| `cce-update-suppression` | 重新引入潜影盒 CCE 更新抑制向量（需 `catch-update-suppression` 才有意义） | Leaves → Lophine 0118，GPL-3.0 |
| `revert-trapdoor-changes` | 回退 Paper 对 TrapDoorBlock 的改动 | Luminol → Lophine 0121，MIT |
| `old-block-remove-behaviour` | 旧版 Block remove 行为（方块实体移除时序） | Leaves → Lophine 0124，GPL-3.0 |
| `no-ghast-block-breaking` / `no-creeper-block-breaking` / `disable-ghast-fire` / `disable-blaze-fire` | MiniTweaks 生物火焰与爆炸规则，四个独立开关 | MiniTweaks → Lophine 0125，GPL-3.0 |

更新抑制另引入 `io.canvasmc.canvas.util.UpdateSuppressionException`（改写自 Leaves，GPL-3.0，无 Leaves 事件/日志依赖）；`0122`（防止更新抑制丢失物品掉落，Leaves → Lophine，GPL-3.0）仅在 `catch-update-suppression` 生效时起作用。

### 5. 末影珍珠机制替换（2026-08-07）

Canvas 上游内置的 `canvas:pearls` 全局 SavedData 珍珠持久化已移除（`0129-Remove-Canvas-ender-pearl-persistence`，本 fork 原创），替换为 Lophine 的原版末影珍珠加载（`0130-Restore-vanilla-ender-pearl-loading`，Bacteriawa，GPL-3.0）：珍珠存入玩家数据 `ender_pearls`（vanilla 布局），珍珠在自身 region 线程更新 volatile 快照保证玩家保存不跨 region 读实体；退出登录时按 Paper 语义经 entity task scheduler 移除珍珠。**行为差异**：珍珠不再跨登停保留，仅在线时跨重启恢复；旧 `canvas:pearls` 存档数据替换后不再读取。

### 6. Linear 区域格式（2026-08-18 移植）

移植自 [Winds-Studio/Leaf](https://github.com/Winds-Studio/Leaf) `ver/26.2@a05f8902`（Luminol 框架 + Abomination Linear V2，均 GPL-3.0-only）：区块存储格式可插拔，支持 **zstd+LZ4 压缩**，官方数据约省 50% 磁盘。

- 配置：`config/canvas-server.yml` → `region-format.format-name`（默认 `MCA`）
- 可选格式：`MCA`（原版）/ `LINEAR_V2`（`.linear`，**作者自认不稳定、有丢数据风险，仅建议配合备份使用**）/ `B_LINEAR`（`.b_linear`，缓冲写，相对稳定的 Linear 变体）
- 附带参数：`compression-level`（zstd 1-22，默认 6）、`io-thread-count`、`io-flush-delay`、`linear-use-virtual-thread`
- **不支持混合格式**：磁盘扩展名与配置不一致会按设计延迟崩溃；已有世界换格式需外部转换器，服务器不做运行时迁移
- LINEAR_V2/B_LINEAR 文件与原版/Amulet/mcaselector 等工具链不兼容



### 7. 插件 API 兼容层（2026-08-18 移植，Lecithin）

移植自 [LophineLabs/Lecithin](https://github.com/LophineLabs/Lecithin) `dev/26.2@586cd088`（TinyYana，GPL-3.0 继承链）：修复 Folia 化后失效的 paper/spigot/bukkit 插件 API。全部经 `plugin-compat.*` 配置门控（默认 `true`，诊断类默认 `false`）。

**NMS 层（feature patch `0140-Lecithin-plugin-API-compat`）**：

| 机制 | 说明 |
|------|------|
| Bukkit 传送事件 | `teleportAsync` 补发 `PlayerTeleportEvent`/`EntityTeleportEvent`（支持 setTo 重定向）；完成回调补发 `PlayerChangedWorldEvent` |
| 乘客传送事件 | 被传送载具的乘客也收到传送事件 |
| 可归因的越界读 | 跨 region 读方块失败时给出带线程/世界/坐标的异常（替代裸 NPE） |
| D-40 诊断 | 同 tick 二次传送拒绝原因打印（默认关） |
| `currentTick` 恢复 | FAWE 反射读取所需（否则 WorldEdit 全崩） |
| 启动线程放行 | bootstrap 线程满足 `ensureGlobalTickThread` |

**paper 层（`paper-patches/features/0001`，12 个补丁合并）**：恢复 Bukkit 异步调度器（Folia throw → 恢复 + 诊断守卫，global tick 驱动心跳）、`Entity#teleport` API 边界、调度器重派规则表（GriefPrevention/Shopkeepers）、经济序列化、PaperLib 环境、权限管理器补锁、骑乘目标先下座、传送 handover 等待（D-40）、async 上下文继承、控制台命令转 global region、跨 region 方块读常驻 chunk 应答。

**注意**：Lecithin 0013（记分板开放）**未移植**——Canvas 上游已用 `ensureMainThread` 守卫等效开放。

**适配要点**：Luminol 卸载锁子系统未移植（Canvas 有自有 `canvas$worldStageLock`/`unloadTicket` 机制），以 `LevelUnloadStateLockAdapter` 桥接读引用语义；D-40 诊断挂在 Canvas 的 `TeleportValidationException` 站点；16 个 Lophinya 辅助类重定位到 `io.canvasmc.canvas.compat`。


### 分支

`main` 跟踪 Canvas 上游 `main`，也是服务器构建和部署使用的分支。旧的 `pre-merger/26.2` 说明已经过时，不再作为当前维护基线。

### 补丁清单与来源

**基础 patch**（`canvas-server/minecraft-patches/base/`）：Canvas 上游维护，fork 不改。数量和编号会随上游合并变化，以目录实际内容为准，此处不硬编码。

**Feature patch**（`canvas-server/minecraft-patches/features/`）：

| 补丁 | 来源 | 说明 |
|------|------|------|
| `0001-Purpur-Alternative-Keepalive` | Canvas 上游 | — |
| `0002-Disable-Criterion-Trigger-Config` | Canvas 上游 | — |
| `0003-Re-enable-command-blocks` | **本 fork 原创** | 6 个命令方块 gate + global region 执行路由 + owning-region 输出 hop |
| `0128-Add-Vanilla-like-experience-Config` | **本 fork**（移植） | 17 项 vanilla 机制 + `enabled` 主开关，移植自 [Lophine 0048 固定版本](https://github.com/LophineLabs/Lophine/blob/f4aea025c11c598f285d3c47198c62397a0daba8/lophine-server/minecraft-patches/features/0048-Add-Vanilla-like-experience-Config.patch)（作者 Bacteriawa，GPL-3.0） |
| `0094`/`0095`/`0096`/`0098`/`0101`/`0104` | **本 fork**（移植） | old-feature 六项，与 Lophine `OldFeatureConfig` 对齐，见 §3 |
| `0028`/`0034`/`0045`/`0065`/`0093`/`0117`/`0118`/`0121`/`0122`/`0124`/`0125` | **本 fork**（移植） | 2026-08-07 新增移植，见 §4；编号对齐 Lophine `dev/26.2-hardfork@0724ba3f` |
| `0129-Remove-Canvas-ender-pearl-persistence` | **本 fork 原创** | 移除 Canvas 内置 `canvas:pearls` SavedData 珍珠持久化，恢复 Paper 保存/退出路径 |
| `0107-Luminol-Configurable-region-format-framework` | **本 fork**（移植） | Leaf `0107`@`a05f8902`（Luminol 框架 GPL-3.0 + Abomination Linear V2 GPL-3.0）：区域格式可插拔（MCA/LINEAR_V2/B_LINEAR），见 §6 |
| `0130-Restore-vanilla-ender-pearl-loading` | **本 fork**（移植） | Lophine `0130`@`0724ba3f`（Bacteriawa，GPL-3.0）：原版珍珠加载，region 安全快照 |
| `0140-Lecithin-plugin-API-compat` | **本 fork**（移植） | Lecithin `dev/26.2@586cd088` NMS 0001-0006（TinyYana，GPL-3.0）：插件 API 兼容 NMS 层，见 §7 |
| paper `0001-Lecithin-plugin-API-compat` | **本 fork**（移植） | Lecithin paper 0002-0012/0014 合并（TinyYana，GPL-3.0）：Bukkit 边界 API 恢复，见 §7 |
2026-08-18（Lecithin）：移植 Lecithin 插件 API 兼容全家桶（NMS 0140 + paper 0001 + compat 16 类 + PluginCompat 23 门控配置）。卸载锁经 `LevelUnloadStateLockAdapter` 桥接 Canvas 自有机制；0013 记分板因 Canvas 上游已等效开放而跳过。CI 全绿 + 本地启动 smoke（Done 21.4s，plugin-compat 配置段生成）。备份分支 `backup/pre-lecithin-2026-08-18`。
2026-08-18（Linear）：从 Leaf `ver/26.2@a05f8902` 固定提交移植 Linear 区域格式全家桶（0107 框架补丁 + `io.canvasmc.canvas.regionformat` 包 7 文件 + zstd-jni/zero-allocation-hashing 依赖 + `GlobalConfiguration.RegionFormat` 配置段）。本地验证：MCA/LINEAR_V2/B_LINEAR 三格式新世界启动均生成正确扩展名，B_LINEAR 与 LINEAR_V2 世界重启读取无错。Lophine 的匿名化版本（`0007`）因无不可变来源链接被排除，来源政策见 PROVENANCE。

2026-07-14 已与 `LophineCraft/Lophine` `dev/26.2@f4aea025` 复核：0048 仍覆盖相同的 17 个原版机制；0013 和已更名的 `0014-Old-leader-zombie-health-logic.patch` 与本 fork 的两个 OldFeature 选项语义一致。

2026-07-26 复核（来源仓库已改组 + 硬分叉）：组织更名为 `LophineLabs/Lophine`（旧地址 301 重定向，`f4aea025` 仍可达）；2026-07-17 从 Luminol 硬分叉，默认分支变为 `dev/26.2-hardfork`，feature 补丁由 49 个增至 130 个，三个来源补丁因此被重新编号为 `0129` / `0094` / `0095`——但**内容与 `f4aea025` 逐字节相同**，本 fork 的 0003 无需重新移植。因 Lophine 会随补丁集变动重新编号，后续复核请按文件名关键词定位来源，不要按编号。详见 [`PROVENANCE.md`](PROVENANCE.md)。

同日补齐 `old-feature` 段剩余 4 个字段（`spawn-invulnerable-time` / `old-explosion-damage-calculator` / `old-raid-behavior` / `villager-void-trade`），至此与 Lophine `OldFeatureConfig` 的 6 字段完全对齐；0003 由 23 个文件扩到 28 个。同时复核确认 0129 未把任何机制拆分到硬分叉新增的其他补丁里——0129 触碰的 17 个文件全部仍在 0003 覆盖范围内，本 fork 没有漏移植。

2026-08-04 复核（Leaves 三项 GPL 来源）：`LeavesMC/Leaves` `master` 上 `0125-Old-wet-tnt-explode-behavior`、`0101-Old-raid-behavior`、`0005-Configurable-void-trade` 三个补丁的最后一次修改均为 1.21.11 rebase `22a763cb`（2026-04-28），此后无改动；与本 fork 固定版本（`3e96b237` / `bda7e406` / `9d2bd3f7`）的差异全部是 rebase 上下文适配（变量重命名、行号偏移），无行为修复，0003 无需重新移植。同日合并上游 Canvas `main`（至 `2c97cca1`），0003 应用无需适配。
2026-08-07：原单一 `0003-Vanilla-like-experience.patch` 按功能拆为 `0003`（命令方块）/`0128`（vanilla-like 主）/old-feature 六个补丁，并新增移植 11 个机制（§4）。全链回放验证：19 个补丁按序应用逐字节复现拆分前最终态；GitHub Actions（applyAllPatches + compileJava + paperclip）全绿，CNB 远程启动 smoke test 通过（全部新配置项带 docs 生成）。Lophine `ver/26.2@fc3415e6` 已将补丁集重编号为 49 个，且删除了 `0028`/`0034`/`0045`/`0065` 四个来源——移植与复核仍以固定提交 `0724ba3f` 为准，按文件名关键词定位。
2026-08-09 复核：Lophine `dev/26.2-hardfork` 推进至 `c619486d`，7 个来源补丁（0028/0034/0117/pearl/vanilla-like/0096/0101）仅有 rebase 上下文/hunk 形状变化，+/- 代码行集合与固定版本相同，无行为修复，不需重移植；pearl、vanilla-like 重编号（0130→0129、0129→0128）。`ver/26.2` 与 Leaves `master` 无变化。
2026-08-18：合并上游 23 提交（`2499b002..a1943fae`）后 sources 补丁变化导致 feature 补丁 blob index 失效，整套 21 个补丁在干净基线（上游补丁全量应用后的 dump 树）上重新生成：0028 适配 teleport 状态重构，0129 删除 hunk 重生成，0130 补 EnderpearlItem 钩子，0065 同时吸收 Lophine 0066 vanilla hopper 重写（`movedItemCount==1` 快路径 + `removeOriginalItem` 实例比较 + 部分移动计数语义，hardfork `2459ff2e`）。全链重放逐字节验证 + Actions 全绿 + 本地启动 smoke（Done 44.7s）。同日复核：Lophine hardfork 其余 6 个来源补丁仅重编号无行为变化；Leaves 三个 GPL 来源仅变量重命名。上游同时引入 `0003-Leaf-Flush-location-while-knockback`（上游维护，与本 fork 补丁无文件冲突，已在补丁清单但归 Canvas 上游）。

**Canvas 自有源码改动**（非 patch）：`canvas-server/src/main/java/io/canvasmc/canvas/GlobalConfiguration.java` 新增 `VanillaLikeExperience` 配置段（`enabled`、`commandBlocks` + 11 个新机制字段 + `TripwireBehavior` 枚举，枚举位于 `GlobalConfiguration` 顶层供 NMS patch 引用）；新增 `util/UpdateSuppressionException.java`。

命令方块修复在 `0003-Re-enable-command-blocks`，vanilla-like 主机制在 `0128`，old-feature 与新增机制各自独立补丁（见上表）；配置都在 `config/canvas-server.yml`（`vanilla-like-experience` 段控原版机制与新增机制，`old-feature` 段控旧版六项）。基础 patch 保持上游 Canvas 原样（命令方块禁用），由 0003 重新启用并受配置控制。

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

