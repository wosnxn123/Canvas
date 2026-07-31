# Folesium integration for this fork

> **English** | [简体中文](README.zh.md)

[Folesium](https://github.com/wosnxn123/Folesium) is a byte-oriented world storage
backend that replaces Anvil `.mca` region files **and the per-player files**
(`players/data`, `players/advancements`, `players/stats`) inside a Folia 26.2 / Canvas
server. This integration is **Folia/Canvas-only**: Paper-style checkouts are not supported
and this kit contains no Paper integration code.
The `folesium-integration/` directory is the bootstrap/documentation kit; the integration
also produces or updates fork-specific tracked artifacts described below.

> **Running a server?** The full operator's guide — enabling, every configuration
> option, migration paths, rollback, backups, troubleshooting — is in
> **[USAGE.md](USAGE.md)** ([中文](USAGE.zh.md)).

## Quick start

```bash
./folesium-integration/setup-folesium.sh
```

1. clones (or updates) Folesium into `folesium-integration/.folesium-src` (git-ignored),
2. runs `./gradlew applyAllPatches` if the decompiled sources are missing,
3. runs Folesium's `scripts/apply-integration.sh`, which
   * for Folia, regenerates the 22 tracked engine file-patches under
     `folia-server/paper-patches/files/src/main/java/dev/folesium/**` and applies the
     four vanilla-class patches to generated Minecraft sources;
   * for Canvas, refreshes the 22 tracked engine sources under
     `canvas-server/src/main/java/dev/folesium/**` and applies the same four vanilla-class
     patches to generated Minecraft sources;
   * patches four vanilla classes:

   | class | data it redirects | store |
   |---|---|---|
   | `RegionFileStorage` | chunks / entities / POI | `<dimension>/folesium` (`role=DIMENSION`) |
   | `PlayerDataStorage` | `players/data/<uuid>.dat` | `<world>/players/folesium` (`role=PLAYERS`) |
   | `PlayerAdvancements` | `players/advancements/<uuid>.json` | same store |
   | `ServerStatsCounter` | `players/stats/<uuid>.json` | same store |
   * patches `org.bukkit.craftbukkit.Main` to add the in-place conversion flags,
4. builds the paperclip jar.

Both store directories are called `folesium/`; they are told apart by the `store.role`
recorded in each store's metadata, never by their path.

Options: `--no-build`, and env vars `FOLESIUM_REPO`, `FOLESIUM_REF`, `FOLESIUM_HOME`
(point at a local Folesium checkout instead of cloning).

## Using the server

```bash
# one-shot conversion of an existing world (server exits when finished)
java -jar <paperclip>.jar --folesiumConvertToFolesium --nogui

# run on Folesium storage (opt-in; without the flag the server is stock)
java -Dfolesium.enabled=true -jar <paperclip>.jar --nogui

# rollback
java -jar <paperclip>.jar --folesiumConvertToAnvil --nogui
```

The converter **never deletes files** (cesium-fabric parity): the old data stays on
disk as a backup and the conversion prints the **absolute, normalized** paths of what
you may now remove manually. Details, all configuration keys and troubleshooting:
[USAGE.md](USAGE.md).

## Keeping the fork updatable

The kit directory is added to the fork, but the integration payload is not generated-only:
Folia tracks the 22 engine file-patches under
`folia-server/paper-patches/files/src/main/java/dev/folesium/**`; Canvas tracks the 22
vendored engine sources under `canvas-server/src/main/java/dev/folesium/**`. Paperweight's
decompiled Minecraft and `paper-server` output remain generated/ignored. Keep the tracked
artifacts synchronized from the Folesium checkout; an upstream update may require resolving
patch context before rebuilding.

* To update upstream: `git pull upstream <branch>`, regenerate/apply the fork artifacts with
  `./folesium-integration/setup-folesium.sh`, then rebuild.
* Do not hand-edit generated Minecraft output or vendored copies; Folesium remains the source
  of truth.
* If an upstream change makes one of the patches fail, apply it fuzzily only after reviewing
  the resulting generated source:
  `patch -p5 --fuzz=3 -d <fork>-server/src/minecraft/java < .folesium-src/integration/folia-26.2/patches/<name>.java.patch`.

## Reverting

Stop the server and convert any newer Folesium data back to Anvil before changing source.
Then remove both tracked integration artifacts and generated output; `git checkout .` alone
does not remove untracked generated files or tracked vendor/patch files:

```bash
# remove tracked payloads (choose the path(s) present in this checkout)
git rm -r --ignore-unmatch folia-server/paper-patches/files/src/main/java/dev/folesium
git rm -r --ignore-unmatch folia-server/minecraft-patches/sources
git rm -r --ignore-unmatch canvas-server/src/main/java/dev/folesium

# discard generated copies and patched generated Minecraft sources
rm -rf folia-server/src/main/java/dev/folesium canvas-server/src/main/java/dev/folesium
./gradlew applyAllPatches
rm -rf folesium-integration/.folesium-src
```

For a pristine checkout without committing removals, reset to the upstream branch or use a
fresh clone after removing the Folesium payload. The next `applyAllPatches` must run without
the Folesium patch/source artifacts before the fork is considered restored.

Full documentation lives in the Folesium repository:
[`docs/INTEGRATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/INTEGRATION.md),
[`docs/MIGRATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/MIGRATION.md),
[`docs/SERVER-VERIFICATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/SERVER-VERIFICATION.md).
