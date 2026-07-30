# 本 fork 的 Folesium 集成

> [English](README.md) | **简体中文**

[Folesium](https://github.com/wosnxn123/Folesium) 是面向 Folia 26.2 / Canvas 的
面向字节的世界存储后端，用于替换 Anvil `.mca` 区域文件**以及单玩家文件**
（`players/data`、`players/advancements`、`players/stats`）。本目录是 Folesium 对该 fork
添加的**全部**内容——不修改任何上游跟踪的文件，因此 `git pull upstream <分支>`
不会因为 Folesium 产生冲突。

## 快速开始

```bash
./folesium-integration/setup-folesium.sh
```

脚本会：

1. 把 Folesium 克隆（或更新）到 `folesium-integration/.folesium-src`（已 gitignore）；
2. 若反编译源码缺失，先执行 `./gradlew applyAllPatches`；
3. 执行 Folesium 的 `scripts/apply-integration.sh`：
   * 将 `dev.folesium.{core,anvil,converter,integration}` 内联进 `paper-server/src/main/java`；
   * 给四个原版类打补丁：
     | 类 | 重定向的数据 | 存储 |
     |---|---|---|
     | `RegionFileStorage` | 区块 / 实体 / POI | `<维度>/folesium`（`role=DIMENSION`） |
     | `PlayerDataStorage` | `players/data/<uuid>.dat` | `<世界>/players/folesium`（`role=PLAYERS`） |
     | `PlayerAdvancements` | `players/advancements/<uuid>.json` | 同一存储 |
     | `ServerStatsCounter` | `players/stats/<uuid>.json` | 同一存储 |
   * 给 `org.bukkit.craftbukkit.Main` 打补丁，加入原地转换启动参数；
4. 构建 paperclip jar。

两类存储目录都叫 `folesium/`，靠各自元数据里记录的 `store.role` 区分，
绝不依赖路径判断。

可选项：`--no-build`；环境变量 `FOLESIUM_REPO`、`FOLESIUM_REF`、`FOLESIUM_HOME`
（指向本地已有的 Folesium 检出，跳过克隆）。

## 使用服务端

```bash
# 一次性转换已有世界（转换完成后服务端自动退出）
java -jar <paperclip>.jar --folesiumConvertToFolesium --nogui

# 以 Folesium 存储启动（默认关闭，不加该参数时行为与原版 fork 完全一致）
java -Dfolesium.enabled=true -jar <paperclip>.jar --nogui

# 回滚
java -jar <paperclip>.jar --folesiumConvertToAnvil --nogui
```

## 保持可拉取上游更新

* 所有 Folesium 改动只作用于**生成的**源码目录（`paper-server/`、`*-server/src/minecraft/`），
  这些目录被 paperweight 忽略，不会提交到本仓库。
* 更新上游：`git pull upstream <分支> && ./gradlew applyAllPatches`，
  然后重新执行 `./folesium-integration/setup-folesium.sh`。
* 若上游改动导致某个补丁冲突，可模糊应用：
  `patch -p5 --fuzz=3 -d <fork>-server/src/minecraft/java < .folesium-src/integration/folia-26.2/patches/<名称>.java.patch`。

## 撤销集成

```bash
git -C paper-server checkout .        # 丢弃内联源码与 Main 钩子
./gradlew applyAllPatches             # 还原干净的 minecraft 源码
rm -rf folesium-integration/.folesium-src
```

完整文档见 Folesium 仓库：
[`docs/zh/INTEGRATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/zh/INTEGRATION.md)、
[`docs/zh/MIGRATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/zh/MIGRATION.md)、
[`docs/zh/SERVER-VERIFICATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/zh/SERVER-VERIFICATION.md)。
