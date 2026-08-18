# Canvas Fork（wosnxn123）

基于 [CraftCanvasMC/Canvas](https://github.com/CraftCanvasMC/Canvas)（Folia 下游）的维护 fork，MC 26.2。

**所有 fork 特性、补丁清单、配置说明、维护流程见 [`FORK.md`](FORK.md)**（中文）。补丁来源与许可证台账见 [`PROVENANCE.md`](PROVENANCE.md)。

## Fork 能力速览

| 能力 | 入口 |
|------|------|
| 命令方块重新启用（配置门控） | `FORK.md` §1 |
| Vanilla-like 原版机制还原（17+11 项） | `FORK.md` §2/§4 |
| Old Feature 旧版机制（6 项） | `FORK.md` §3 |
| 原版末影珍珠（替换 Canvas 内置） | `FORK.md` §5 |
| Linear 区域格式（LINEAR_V2/B_LINEAR，省 ~50% 磁盘） | `FORK.md` §6 |
| 插件 API 兼容层（Lecithin：调度器/传送/记分板等 18 项恢复） | `FORK.md` §7 |

## 来源政策

补丁来源、原作者和许可证是合并与发布的硬性条件。规则、固定 commit 链接与逐补丁台账见 [`PROVENANCE.md`](PROVENANCE.md)；Lophine 匿名化补丁因无不可变来源链接被排除。

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

This fork does not claim authorship over inherited or derived work merely
because a patch was manually reapplied, regenerated, or adapted. Its mandatory
per-patch source ledger and review requirements are documented in
[`PROVENANCE.md`](PROVENANCE.md). The ledger includes immutable source commits,
original authors, licenses, and local adaptation notes for this fork's custom
patches.

Canvas is also dedicated to trying to showcase our contributors in our software. From a "Contributor" role on our
Discord to being mentioned by name inside our own software in our version command, we at CanvasMC are always trying to
make an effort to showcase our community and contributors since frankly, Canvas would not be what it is without them.

---

## Donating

You can donate to support Canvas' work by donating [here](https://ko-fi.com/dueris)
