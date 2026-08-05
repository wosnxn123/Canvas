# Strata 存档引擎使用教程（中文）

> 本文是 Canvas fork 内置 **Strata** 存档引擎的中文使用教程。English version: [STRATA-GUIDE.en.md](STRATA-GUIDE.en.md)。
> 引擎源码、设计文档与 CLI 工具：[wosnxn123/Strata](https://github.com/wosnxn123/Strata)。

## 什么是 Strata

Strata 是用 Rust 编写的混合双层存档引擎，替代 Anvil `.mca`：

- **热层**：段日志追加写，承载频繁读写的活跃区块；
- **冷层**：region 对齐的分块只读归档（`.varc`），块级压缩 + 块索引随机访问；
- 体积较 Anvil 降低 **45%+**（高可压缩负载实测可达 ~10%）；
- 存储内存占用**与世界大小无关**（10TB 级存档同样适用）；
- 逐条 xxhash64 校验，损坏只隔离单条记录、不传播；
- epoch 日志 + manifest 影子双副本，崩溃可恢复。

**默认关闭**。启用后接管区块 / 实体 / POI 存储。**官方 Canvas CI 构建（GitHub Actions 产物）已内嵌双平台 native**（`strata_ffi.dll` win-x64 / `strata_ffi.so` linux-x64），无需额外安装；自构建时需按 Strata 仓 BUILD_GUIDE 自备 native。native 库缺失或加载失败：该维度尚无 vstore 时**自动回退 Anvil**，服务器照常启动；**已有 vstore 时 fail-closed 拒绝启动该 level**（否则 vstore 数据不可见），见"关闭与回滚"。

## 启用

1. 在**世界根目录**（与 `level.dat` 同级）创建或编辑 `strata.properties`：

   ```properties
   strata.enabled=true
   ```

2. 启动服务器。首次启动若没有配置文件，会自动生成**带完整注释的模板**（默认 `strata.enabled=false`）。

3. 启动日志出现以下内容即接管成功：

   ```
   [Strata] [strata] native bridge loaded, version strata-ffi 0.1.0
   [Strata] [strata] virtual store online for <维度目录> (config=<世界根>, vstore=<维度目录>\vstore)
   ```

   每个维度各有一行 `virtual store online`。

> **fail-closed**：若某维度已有 vstore（`vstore/manifest.vsm` 存在）而 Strata 未接管（`strata.enabled=false`、native 失败、打开失败），**拒绝启动该 level**——静默按 Anvil 启动会让 vstore 里的数据不可见。应急逃生：`strata.force-anvil=true`（仍按 Anvil 启动，vstore 数据转回前不可见，每次启动醒目 WARN）。

## 多世界与多维度

- **多维度**：主世界、下界、末地各自拥有独立存储池 `<维度目录>/vstore`（与该维度的 `region/` 同级），互不干扰。
- **多世界**：Multiverse 等插件创建的世界就是普通世界根，各自读取自己的 `strata.properties`，自动接管。每个世界可独立配置（压缩级别、GC、线程数等）。

## 转换已有的 Anvil 世界

**先停服**。两种方式任选：

### 方式一：启动参数（服务端内置）

在启动命令中追加：

```
--strataConvertToStrata     # Anvil → Strata（全部维度）
--strataConvertToAnvil      # Strata → Anvil（回滚）
```

转换在启动前同步执行，完成后继续正常启动。**转换完成后请移除该参数**，否则下次启动会再次覆盖重建。

**转换守卫**：目标维度已有 vstore 时，这两个启动参数**默认拒绝**（既有 vstore 可能含更新的运行期数据），需追加 `--strataForce` 显式确认。`--strataConvertToAnvil` 还会提示：运行期写入的记录只存在于 vstore，不会被 Anvil 清单枚举，服务端内导出可能漏——无损全量导出请用 `strata-cli convert --to-anvil`。

### 方式二：strata-cli（离线工具，Strata 仓库构建）

```bash
strata-cli convert --to-strata <world>   # Anvil → Strata
strata-cli convert --to-anvil <world>    # Strata → Anvil
```

转换行为（Cesium 式）：

- **原地覆盖**目标存储，**绝不删除源**（`region/`、`entities/`、`poi/` 保留），验证无误后手动删除；
- **断点续传**：中断后重跑自动跳过已完成部分；
- **全维度**：自动发现主世界、`DIM-1`/`DIM1`、`dimensions/minecraft/*` 全部维度根；
- 多世界服务器：对每个世界根各执行一次。

服务端内 `/strata convert-to-strata` / `/strata convert-to-anvil` 子命令同理受守卫：目标维度已有 vstore 时需 `-f`/`--force` 确认。

## 配置参考（strata.properties）

世界根目录，Java properties 格式。完整注释模板自动生成，键值如下：

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

要点：

- **压缩级别可随时改**：每条记录自带 codec/字典槽/代际，新旧记录混存合法，读取按记录自身配置解压；
- **`strata.compression.threads` 默认 1（串行）**：游戏服 CPU 稀缺，TPS 优先；CPU 富余时设 `0`（全核）或 `N`（限流）换取 autosave/转换吞吐；
- `strata.index.cache-mb`：索引缓存预算，常驻内存上界，与世界大小无关；
- `strata.gc.min-hole-bytes`：挖洞最小洞阈值（默认 65536 字节）；
- `strata.force-anvil`：应急逃生门（见"关闭与回滚"）；
- `strata.compression.dictionary` **已停用**：旧键仍识别但 WARN 后忽略；
- 非法值会在启动时报错并指明行号，不静默回退。

## 维护命令（strata-cli）

```bash
strata-cli verify <world>       # 校验世界根下所有 vstore（逐条哈希）
strata-cli stats <world>        # 体积 / 记录数统计（按维度）
strata-cli compact <world>      # 手动 GC 压实
strata-cli recompress <world>   # 按当前配置全量重压（安全：先写 vstore.new，校验后 rename 交换）
```

在线维护：GC 与冷层迁移**随 autosave 周期在线运行**——每 `strata.tiering.stable-flushes` 次成功 flush 触发一轮（GC + flush + tier），启动时不做全量扫描。

> ⚠️ **CLI 需停服**：vstore 有独占会话锁（`vstore/.strata.lock`），对运行中服务的 vstore 跑 CLI 会报"另一个进程正在使用"。
> ⚠️ **备份注意**：挖洞产生稀疏文件，不感知稀疏的复制工具会膨胀回逻辑大小——用 `tar --sparse` 或支持稀疏的工具，或直接复制段文件；建议防病毒软件排除 vstore 目录。
> ⚠️ **NFS/网络文件系统/多机共享卷不支持**：锁与 rename/fsync/挖洞语义依赖本地文件系统。

## 关闭与回滚

> ⚠️ **风险真相**：Strata 运行期写过的 chunk，其 Anvil 主副本在写成功时即被删除（vstore 是唯一副本）。直接置 `strata.enabled=false` 不是"恢复原样"：这些 chunk 在 Anvil 侧不可见，且 fail-closed 会直接拒绝启动该 level。

1. `strata.enabled=false`（或删除配置文件）→ 若 vstore 存在，**fail-closed 拒绝启动该 level**（防止 vstore 数据被静默无视）；`strata.force-anvil=true` 才放行（应急逃生：vstore 数据转回前不可见，每次启动醒目 WARN）；
2. 彻底转回 Anvil 文件（**推荐 CLI**，无损全量）：停服后 `strata-cli convert --to-anvil <world>`；服务端 `--strataConvertToAnvil` / `/strata convert-to-anvil` 需 `--strataForce` / `-f` 显式确认，且只存在于 vstore 的记录（运行期写入）不会被 Anvil 清单枚举、可能漏出；
3. 回滚完成、验证无误后再删除 `vstore/`。

## 常见问题

- **启动日志出现 `native bridge unavailable`？** native 库未内嵌或平台不匹配，已自动回退 Anvil，服务器不受影响；使用内嵌 natives 的 paperclip 构建即可。
- **旧存档工具（Amulet/MCA Editor 等）能读 vstore 吗？** 不能——新格式非 Anvil 外壳。先用 `convert --to-anvil` 转回再编辑。
- **转换中途改了压缩配置？** 会造成混级别（合法但不一致），重跑一次转换即可统一。
- **Nether/End 没接管？** 检查启动日志是否每个维度都有 `virtual store online`；若某维度回退，日志会给出原因。
