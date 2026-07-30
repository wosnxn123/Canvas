# Folesium —— 使用与配置指南

> [English](USAGE.md) | **简体中文**

这是在**本服务端 fork**（Folia 26.2 / Canvas）上运行 Folesium 存储后端的完整
运维指南。引擎内部原理见 [Folesium 仓库](https://github.com/wosnxn123/Folesium)
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

```bash
./folesium-integration/setup-folesium.sh          # 克隆/更新引擎、打补丁、构建
# 产物：<fork>-server/build/libs/<fork>-paperclip-*.jar
```

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

```
Folesium: opened DIMENSION store .../world/dimensions/minecraft/overworld/folesium
Folesium: opened PLAYERS store .../world/players/folesium
```

---

## 4. 配置项参考

所有键既可写成 `-Dfolesium.<key>`，也可写在 `folesium.properties` 里。
无法解析的值会回退到默认值并记录警告——绝不会中断启动。

| 键 | 默认 | 含义 |
|---|---|---|
| `enabled` | **`false`** | 总开关。关闭 = 100% 原生服务端。 |
| `configFile` | `folesium.properties` | 备用配置文件路径（仅系统属性） |
| `shards` | `32` | **新建**存储的分片数（2 的幂，1–1024；已有存储沿用盘上值）。32 可支撑约 64 个写线程；小型服务器用 8–16 即可。 |
| `durability` | `BATCH` | `ALWAYS` = 每次写入 fsync；`BATCH` = 后台组提交；`EXPLICIT` = 仅 flush/close 时 fsync |
| `batchFlushMillis` | `500` | `BATCH` 的组提交间隔 |
| `compression` | `DEFLATE` | `NONE` / `DEFLATE` / `ZSTD`；建店时固定（旧记录始终可读） |
| `compressionLevel` | `4` | Deflate 与 ZSTD 等级 1–9。4 ≈ 原版 zlib 压缩率但 CPU 更低。 |
| `compactRatio` | `0.5` | 分片死字节超过文件的该比例时触发压实 |
| `compactMinBytes` | `8388608` | 小于该大小（8 MiB）的分片不压实 |
| `verifyChecksums` | `false` | 每次读取重校验 CRC32C（约 2 倍读 I/O；恢复扫描始终校验） |

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

三条等价路径，任选其一。**每种情况都请先备份世界。**

### 5a. 懒迁移（除重启外零停机）

直接启用 Folesium 并启动。存储中缺失的区块或玩家会按需从原始 `.mca`/玩家文件
读取，并在保存时迁入存储。世界从第一秒起即完全可玩；存储随游玩逐步填充。

### 5b. 一次性转换（Cesium 式启动 flag，推荐）

```bash
# 先停服：
java -jar <fork>-paperclip-*.jar --folesiumConvertToFolesium --nogui   # 完成后退出
java -Dfolesium.enabled=true -jar <fork>-paperclip-*.jar --nogui       # 以 Folesium 启动
```

* 一次运行即转换**所有维度**（递归发现，含模组维度布局）**及玩家数据**，多线程。
* 转换是**合并模式**：已在存储中的记录（例如 5a 期间实时写入的）永远优先于
  较旧的文件——在玩过 5a 之后运行也是安全的。
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

```
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

```
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
| 存储目录持续变大 | 分片死字节超过 `compactRatio` × 大小且大于 `compactMinBytes` 时压实回收；调低这两项可更早压实 |
| 想验证完整性 | 用 `-Dfolesium.verifyChecksums=true` 启动，或 `folesium-converter … inspect <store>` |

需要认识的日志行：

```
Folesium: opened DIMENSION store <path>     # 维度存储已激活
Folesium: opened PLAYERS store <path>       # 玩家存储已激活
Folesium: no files were deleted. ...        # 转换保留提示（§5/§6）
```
