# Folesium —— 使用与配置指南

> [English](USAGE.md) | **简体中文**

这是在**本服务端 fork**（Folia 26.2 / Canvas）上运行 Folesium 存储后端的完整
运维指南。当前集成**仅支持 Folia/Canvas**：不支持 Paper 风格检出，也不提供 Paper
集成代码。引擎内部原理见 [Folesium 仓库](https://github.com/wosnxn123/Folesium)
（`docs/zh/ARCHITECTURE.md`、`docs/zh/SCHEMA.md`）。

---

## 1. Folesium 替换了什么

启用 Folesium 后，服务器不再写 Anvil `.mca` 文件与原版单玩家文件，而把相同的
字节存入分片追加日志：

| 原版数据 | 原版位置（MC 26.x） | Folesium 存储 |
|---|---|---|
| 区块、实体、POI | `<维度>/region\|entities\|poi/*.mca` | `<维度>/folesium/`（`store.role=DIMENSION`） |
| 玩家 NBT | `world/players/data/<uuid>.dat` | `world/players/folesium/`（`store.role=PLAYERS`） |
| 成就 | `world/players/advancements/<uuid>.json` | 同一存储 |
| 统计 | `world/players/stats/<uuid>.json` | 同一存储 |

26 之前的世界中，玩家文件位于 `world/playerdata|advancements|stats`，玩家存储
位于 `world/folesium/`。两类存储目录都叫 `folesium/`，服务器靠各存储内部记录的
`store.role` 区分——绝不依赖路径。

载荷按**原版原始字节**存储（未压缩区块 NBT、gzip 玩家 NBT、UTF-8 JSON），
不解析、不重编码，因此 Folesium 与 Minecraft 版本无关，转换双向无损、字节级一致。

**Folesium 严格采用可选启用（opt-in）。** 不设置 `folesium.enabled=true` 时，
本服务端与原生 fork 行为完全一致，继续写 `.mca`。

---

## 2. 获取服务端 jar

本套件只能从 Folia 26.2 或 Canvas 检出中运行：

```bash
./folesium-integration/setup-folesium.sh          # 克隆/更新引擎、打补丁、构建
# 产物：<fork>-server/build/libs/<fork>-paperclip-*.jar
```

脚本会刷新 fork 中跟踪的 Folia 文件补丁或 Canvas vendor 源码，并在构建前将原版钩子
应用到生成源码。Paper 风格检出不会被检测或支持。

选项：`--no-build`（只打补丁不构建），环境变量 `FOLESIUM_REPO` /
`FOLESIUM_REF` / `FOLESIUM_HOME`（使用本地 Folesium 检出而非克隆）。

要求：JDK 25（本 fork 的构建要求），构建约需 4 GB 内存。

---

## 3. 启用 Folesium

每个选项按以下顺序解析（先命中者生效）：

1. JVM 系统属性：`-Dfolesium.<key>=<value>`
2. 服务器工作目录下的 `folesium.properties`
   （可用 `-Dfolesium.configFile=/path/to/file` 覆盖路径）
3. 内置默认值

最简启动：

```bash
java -Dfolesium.enabled=true -jar <fork>-paperclip-*.jar --nogui
```

或在 `server.jar` 旁创建 `folesium.properties`（键**不带** `folesium.` 前缀）：

```properties
enabled=true
```

然后正常启动。开服时每打开一个存储会打印一行：

```text
Folesium: opened DIMENSION store .../world/dimensions/minecraft/overworld/folesium
Folesium: opened PLAYERS store .../world/players/folesium
```

---

## 4. 配置项参考

所有由配置文件承载的键既可写成 `-Dfolesium.<key>=<value>`，也可写成
`folesium.properties` 中的 `<key>`。例外是 `logging.utf8`：它仅支持 JVM 系统属性，
从不读取 `folesium.properties`。
配置文件中无法解析**以及超出取值范围**的值都会回退到默认值并记录警告——绝不会中断启动。

「生效方式」一列说明改动如何作用到**已存在**的存储：**实时** = 数秒内生效、无需重启（见 §4.1）；
**下次启动** = 下次打开该存储时物理改写；**加载世界时** = 世界在加载时绑定其存储后端；
**启动时** = 在创建配置监视器前读取。

| 键 | 默认 | 生效方式 | 含义 |
|---|---|---|---|
| `enabled` | **`false`** | 加载世界时 | 总开关。关闭 = 100% 原生服务端。 |
| `configFile` | `folesium.properties` | 启动时 | 备用配置文件路径（仅系统属性） |
| `shards` | 自适应（按 CPU 核数取 8/16/32/64/128） | 下次启动 | 分片数（2 的幂，1–1024）。改动后，下次打开存储时会自动、崩溃安全地重分片。默认值按 CPU 核数自适应；小型服务器用 8–16 即可。 |
| `durability` | `BATCH` | 实时 | `ALWAYS` = 每次写入 fsync；`BATCH` = 后台组提交；`EXPLICIT` = 仅 flush/close 时 fsync。切入/切出 `BATCH` 会启动/停止组提交线程。 |
| `batchFlushMillis` | `500` | 实时 | `BATCH` 的组提交间隔 |
| `compression` | `ZSTD`（zstd-jni 可用时）否则 `DEFLATE` | 实时 | `NONE` / `DEFLATE` / `ZSTD`。仅作用于新写入；每条记录自带编解码标志，因此改动无需迁移，旧记录照常可读。 |
| `compressionLevel` | `4` | 实时 | Deflate 1–9，ZSTD 1–22。4 ≈ 原版 zlib 压缩率但 CPU 更低。 |
| `compactRatio` | `0.5` | 实时 | 分片死字节超过文件的该比例时触发压实 |
| `compactMinBytes` | `8388608` | 实时 | 小于该大小（8 MiB）的分片不压实 |
| `verifyChecksums` | `false` | 实时 | 每次读取重校验 CRC32C（约 2 倍读 I/O；恢复扫描始终校验） |
| `autoReload` | `true` | 启动时 | 创建 `folesium.properties` 监视器；改文件中的此键不会停止已经运行的监视器 |
| `autoReloadSeconds` | `10` | 实时 | 多久检查一次文件改动 |
| `logging.utf8` | `true` | 启动时 | 仅 JVM 系统属性控制已有 Folesium/JUL handler 的编码；不是文件键，也不覆盖完整服务端日志 |

### 4.1 给运行中的服务器重新调参

发现配置不适合这台机器时，**不需要重启**来修正。编辑并保存 `folesium.properties`，
`autoReloadSeconds` 秒内改动就会应用到所有已打开的存储，日志会写明改了什么：

```text
[INFO] Folesium: folesium.properties changed on disk, applying it to the running server
[INFO] Folesium: .../world/players/folesium: durability: BATCH -> ALWAYS, compressionLevel: 4 -> 9
```

只有 `enabled` 与 `shards` 无法完全实时生效——它们会写进日志而不是被静默丢弃，其中
`shards` 会在下次启动时通过自动重分片落地（分阶段三段式提交：崩溃后要么旧存储原封不动，
要么下次打开时续做并完成换入）。在大世界上做布局变更前，请照常先备份。

必须在启动前设置 `autoReload=false` 才能禁止创建监视器。运行中把文件改为 `false`
不会停止已经存在的监视器，因此改完后需要重启。

### 4.2 JVM/JUL 日志编码

在 JVM 默认字符集不是 UTF-8 的平台上，`-Dfolesium.logging.utf8=true`（默认）会在 Folesium
首次加载配置时，将**现有的** `java.util.logging` handler 重新编码为 UTF-8。这只覆盖
Folesium 自己的 JUL 消息；Folia/Canvas 随后会把正常服务端输出交给 ForwardLogHandler/Log4j，
因此该设置不承诺重配置完整服务端日志。用 `-Dfolesium.logging.utf8=false` 关闭；该设置
仅支持系统属性。

### 持久性选择

| 模式 | 崩溃时的数据丢失窗口 | 典型用途 |
|---|---|---|
| `ALWAYS` | 已完成写入零丢失 | 最高安全性，延迟略高 |
| `BATCH` | 最多 `batchFlushMillis` | 推荐默认（仍强于原版 Anvil：原版只在关闭区域文件时 fsync） |
| `EXPLICIT` | 直到下次 flush | 仅用于批量转换 |

集成层使用 `BATCH`，并在每次自动保存与关服时额外 flush。

### ZSTD

`compression=ZSTD` 使用 Folia/Canvas 已自带的 `zstd-jni` 原生库——服务器上无需
任何额外设置，压缩率与速度均优于 Deflate。若在缺少 `zstd-jni` 的环境（如独立
转换器）选择它，存储会以清晰的错误信息拒绝打开。

追求持久性的示例配置：

```properties
enabled=true
durability=ALWAYS
compression=ZSTD
verifyChecksums=true
```

---

## 5. 在已有世界上采用 Folesium

只有一条路径。**先备份世界。**

### 5a. 一次性转换（Cesium 式启动 flag，推荐）

```bash
# 先停服：
java -jar <fork>-paperclip-*.jar --folesiumConvertToFolesium --nogui   # 完成后退出
java -Dfolesium.enabled=true -jar <fork>-paperclip-*.jar --nogui       # 以 Folesium 启动
```

* 一次运行即转换**所有维度**（递归发现，含模组维度布局）**及玩家数据**，多线程。
* 转换是**合并模式**：已在存储中的记录永远优先于较旧的文件。
* 幂等且崩溃安全：重复运行只补空缺。
* `--folesiumWorldDir <path>` 覆盖世界位置；否则世界名取自 `server.properties`。

### 5c. 独立转换器 CLI

在 Folesium 仓库中（无需服务端 jar）：

```bash
gradle folesium-converter:installDist
folesium-converter/build/install/folesium-converter/bin/folesium-converter \
    convert /srv/world to-folesium
```

同一工具还提供 `inspect`（各键空间记录数）与 `diff`（字节级比较两个存储，
一致时输出 `STORES-EQUAL`）。

### 旧文件去哪了？

**永远不会删除任何文件**——与原版模组 cesium-fabric 的转换器同一约定。`.mca`
与玩家文件留在磁盘上作为备份，Folesium 启用期间服务器会忽略它们。验证转换后的
世界无误后，可自行删除以回收磁盘空间：各维度的 `region/`、`entities/`、`poi/`，
以及 `players/data|advancements|stats`（26.x）或 `playerdata/ advancements/
stats/`（26 之前）。

---

## 6. 回滚到 Anvil

```bash
# 先停服：
java -jar <fork>-paperclip-*.jar --folesiumConvertToAnvil --nogui      # 完成后退出
java -jar <fork>-paperclip-*.jar --nogui                               # 恢复为原生服务端
```

把每个区块和玩家记录字节级一致地还原回原版文件。同样**不删除任何文件**：
`folesium/` 存储保留为备份，转换结束时会打印它们的确切绝对（规范化）路径，例如：

```text
Folesium: no files were deleted. The now-redundant Folesium stores were kept as a backup:
    /srv/world/players/folesium
    /srv/world/dimensions/minecraft/overworld/folesium
    ...
```

确认还原后的世界无误后，请手动删除。

> **警告：** 若回滚后继续在 Anvil 上游玩，之后又要再转换回 Folesium，请**先删除
> 残留的 `folesium/` 存储**。正向转换是合并模式，过期的存储记录会压过你较新的
> Anvil 数据。

两个转换 flag 互斥；同时传入会报错退出。

---

## 7. 磁盘布局与备份

```text
world/
├── players/
│   ├── folesium/                    <- PLAYERS 存储
│   │   ├── folesium.properties      （存储元数据；切勿编辑）
│   │   ├── playerdata-0000.flog …   分片追加日志
│   │   ├── advancements-….flog、stats-….flog
│   │   └── *.fidx                   （索引提示；可删，仅让下次打开变慢）
│   └── data/ advancements/ stats/   <- 原版文件（转换后作为备份）
└── dimensions/minecraft/overworld/
    ├── folesium/                    <- DIMENSION 存储（chunks-*、entities-*、poi-*.flog）
    └── region/ entities/ poi/       <- 原版文件（转换后作为备份）
```

* **备份** = 停服后（或紧随一次自动保存后）复制 `folesium/` 目录。`*.fidx`
  可以不备份。
* 每条记录带 CRC32C；最后一次写入若被截断，下次打开时会检出并截掉
  （结构上崩溃安全——没有 `.dat_old` 式的重命名把戏）。
* 切勿编辑已有存储的 `folesium.properties`。

---

## 8. 故障排查

| 现象 | 原因 / 解决 |
|---|---|
| 服务器仍在写 `.mca` | `folesium.enabled` 没有设为 `true`（检查属性拼写与配置文件位置） |
| 启动报 `mutually exclusive` | 同时传了两个转换 flag；只用一个 |
| `ZSTD` 存储打开失败 | classpath 缺 `zstd-jni`（只会发生在服务器之外）；改用服务端 flag 或补依赖 |
| 转换后 `.mca` 还在 | 符合预期——转换器不删文件；验证后手动删除（§5） |
| 回滚后 `folesium/` 还在 | 符合预期——同一策略；手动删除（§6） |
| 存储目录持续变大 | 死记录由压实回收：引擎最多每 5 分钟检查一次每个已打开的存储，分片死字节超过 `compactRatio` × 大小且大于 `compactMinBytes` 时改写该分片；调低这两项可更早压实 |
| 想验证完整性 | 用 `-Dfolesium.verifyChecksums=true` 启动，或 `folesium-converter inspect <store>` |

需要认识的日志行：

```text
Folesium: opened DIMENSION store <path>     # 维度存储已激活
Folesium: opened PLAYERS store <path>       # 玩家存储已激活
Folesium: no files were deleted. ...        # 转换保留提示（§5/§6）
```

## 9. 运维经验与 FAQ（来自真实部署）

以下经验来自一个 196 万区块（35.8 GiB 原始 NBT、732 条玩家记录）的生产服实测。

### 修改压缩只影响新写入

`compression` / `compressionLevel` 是**逐记录**生效的：已有记录保持写入时的编码。
编辑 `folesium.properties` 会在几秒内热应用（`autoReload=true`）；只有 `enabled`
（下次世界加载）和 `shards`（下次启动自动重分片）需要重启。要让已有存储整体
换编码，需要重建：

```bash
# 先停服：
java -jar <fork>-paperclip-*.jar --folesiumConvertToAnvil --nogui   # store -> .mca
# 编辑 folesium.properties（如 compression=ZSTD、compressionLevel=9）
java -jar <fork>-paperclip-*.jar --folesiumConvertToFolesium --nogui # .mca -> store，全量新编码
```

重建过程不删除任何文件，临时 `.mca` 会保留为备份。编码选型经验：ZSTD 9 的写入
CPU 成本约等于原版 zlib 6，解压更快，压缩率再高 10-15%。

### 转换期间的目录产物（预期现象，不是错误）

* **备份目录** —— `*.folesium-backup-<uuid>` 是转换器先改名挪走的**旧版** Anvil
  文件/目录，之后才恢复新的。改名是同卷 `rename(2)`，**不产生额外 IO**。验证恢复
  无误后可删（`find <world> -name '*.folesium-backup-*' -exec rm -rf {} +`）。
* **暂存目录** —— `*.folesium-staging-<uuid>` 出现在某个维度某类数据
  （region / entities / poi）写入期间；完成后暂存目录改名为正式目录、旧的进备份。
* **进度顺序** —— 先玩家数据，再逐个维度，每个维度按 region → entities → poi
  依次处理；每个维度完成才打印该维度的统计行。
* **转换输出 "0 chunks" 是正常的** —— 当 Anvil 侧为空时：以 Folesium 模式运行过
  的世界数据在 store 里，`.mca` 文件（或目录）可能已删。转换不触碰 store 已有
  记录（不重复导入、不删除）。

### 慢存储上的 IO 预期

`--folesiumConvertToFolesium` 在共享/虚拟化存储上也很快（store 顺序追加；
196 万区块约 90 秒）。`--folesiumConvertToAnvil` 在同类存储上很慢：`.mca` 是
512 字节扇区的随机写，会表现为数千 IOPS 加数秒延迟；196 万区块全量反向约需
20-60 分钟，属一次性成本。Folesium 日常运行的 IO（顺序追加 + 压实）完全避开
这种模式。

### 多世界

`<world>/dimensions/` 下的维度会自动发现。多世界插件创建的**兄弟目录**世界
（如 `world_creative/` 与 `world/` 平级）不会被发现：用
`--folesiumWorldDir <该世界路径>` 单独转换。删除某个世界后，其
`dimensions/minecraft/<名称>/` 目录（可能含空 Folesium store）会残留——手动删除
目录及其注册（插件配置）；空 store 不包含任何数据。

### 启动显示 `Loading 0 persistent chunks`

Folia/Canvas 的正常现象：区域化调度器不预加载出生区块。区块在玩家移动时按需
从 store 读取；用游玩验证，而不是看启动行。
