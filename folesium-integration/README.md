# Folesium integration for this fork

> **English** | [简体中文](README.zh.md)

[Folesium](https://github.com/wosnxn123/Folesium) is a byte-oriented world storage
backend that replaces Anvil `.mca` region files **and the per-player files**
(`players/data`, `players/advancements`, `players/stats`) inside a Folia 26.2 / Canvas
server.
This directory is the **only** thing Folesium adds to this fork — no upstream-tracked
file is modified, so `git pull upstream <branch>` never conflicts because of Folesium.

> **Running a server?** The full operator's guide — enabling, every configuration
> option, migration paths, rollback, backups, troubleshooting — is in
> **[USAGE.md](USAGE.md)** ([中文](USAGE.zh.md)).

## Quick start

```bash
./folesium-integration/setup-folesium.sh
```

The script:

1. clones (or updates) Folesium into `folesium-integration/.folesium-src` (git-ignored),
2. runs `./gradlew applyAllPatches` if the decompiled sources are missing,
3. runs Folesium's `scripts/apply-integration.sh`, which
   * vendors `dev.folesium.{core,anvil,converter,integration}` into `paper-server/src/main/java`,
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
disk as a backup and the conversion prints what you may now remove manually. Details,
all configuration keys and troubleshooting: [USAGE.md](USAGE.md).

## Keeping the fork updatable

* All Folesium changes are applied to **generated** sources (`paper-server/`,
  `*-server/src/minecraft/`), which are git-ignored by paperweight — they are never
  committed here.
* To update upstream: `git pull upstream <branch> && ./gradlew applyAllPatches`,
  then re-run `./folesium-integration/setup-folesium.sh`.
* If an upstream change makes one of the patches fail, apply it fuzzily:
  `patch -p5 --fuzz=3 -d <fork>-server/src/minecraft/java < .folesium-src/integration/folia-26.2/patches/<name>.java.patch`.

## Reverting

```bash
git -C paper-server checkout .        # drop vendored sources + Main hook
./gradlew applyAllPatches             # restore pristine minecraft sources
rm -rf folesium-integration/.folesium-src
```

Full documentation lives in the Folesium repository:
[`docs/INTEGRATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/INTEGRATION.md),
[`docs/MIGRATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/MIGRATION.md),
[`docs/SERVER-VERIFICATION.md`](https://github.com/wosnxn123/Folesium/blob/main/docs/SERVER-VERIFICATION.md).
