#!/usr/bin/env bash
# Folesium integration bootstrapper for a Folia / Canvas fork checkout.
#
# This script lives *inside the fork* and touches no upstream-tracked file, so
# `git pull upstream <branch>` keeps working without conflicts. All Folesium
# code is fetched from the Folesium repository at setup time.
#
#   ./folesium-integration/setup-folesium.sh              # fetch + apply + build
#   ./folesium-integration/setup-folesium.sh --no-build   # fetch + apply only
#
# Environment overrides:
#   FOLESIUM_REPO   git URL of the Folesium repo   (default: github.com/wosnxn123/Folesium)
#   FOLESIUM_REF    branch/tag/commit to check out (default: main)
#   FOLESIUM_HOME   use an existing local Folesium checkout instead of cloning
set -euo pipefail

FORK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FOLESIUM_REPO="${FOLESIUM_REPO:-https://github.com/wosnxn123/Folesium.git}"
FOLESIUM_REF="${FOLESIUM_REF:-main}"
BUILD=1
[ "${1:-}" = "--no-build" ] && BUILD=0

# 1. obtain Folesium sources -------------------------------------------------
if [ -n "${FOLESIUM_HOME:-}" ]; then
    SRC="$(cd "$FOLESIUM_HOME" && pwd)"
    echo "==> using local Folesium checkout: $SRC"
else
    SRC="$FORK_ROOT/folesium-integration/.folesium-src"
    if [ -d "$SRC/.git" ]; then
        echo "==> updating $SRC"
        git -C "$SRC" fetch --depth 1 origin "$FOLESIUM_REF"
        git -C "$SRC" checkout -q FETCH_HEAD
    else
        echo "==> cloning $FOLESIUM_REPO ($FOLESIUM_REF)"
        rm -rf "$SRC"
        git clone --depth 1 --branch "$FOLESIUM_REF" "$FOLESIUM_REPO" "$SRC"
    fi
fi
[ -x "$SRC/scripts/apply-integration.sh" ] || { echo "not a Folesium checkout: $SRC" >&2; exit 1; }

# 2. refresh tracked Folia file patches before decompiling -------------------
REGENERATE=0
if [ -d "$FORK_ROOT/folia-server/paper-patches/files/src/main/java" ]; then
    [ -f "$SRC/scripts/gen-folia-file-patches.sh" ] || {
        echo "Folia checkout requires scripts/gen-folia-file-patches.sh: $SRC" >&2
        exit 1
    }
    echo "==> regenerating tracked Folia Folesium file patches"
    bash "$SRC/scripts/gen-folia-file-patches.sh" "$FORK_ROOT"
    [ -n "$(find "$FORK_ROOT/folia-server/paper-patches/files/src/main/java/dev/folesium" -type f -name '*.patch' -print -quit)" ] || {
        echo "Folia Folesium file-patch generation produced no patches" >&2
        exit 1
    }
    REGENERATE=1
fi

# 3. make sure the fork sources are decompiled/patched -----------------------
if [ "$REGENERATE" = 1 ] || ! ls "$FORK_ROOT"/*-server/src/minecraft/java >/dev/null 2>&1; then
    echo "==> running ./gradlew applyAllPatches (this takes a while)"
    (cd "$FORK_ROOT" && ./gradlew applyAllPatches)
fi

# 4. vendor + patch ----------------------------------------------------------
bash "$SRC/scripts/apply-integration.sh" "$FORK_ROOT"

# 5. build -------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
    echo "==> building paperclip jar"
    (cd "$FORK_ROOT" && ./gradlew createPaperclipJar --max-workers="$(nproc)")
    echo
    echo "jar(s):"
    ls -1 "$FORK_ROOT"/*-server/build/libs/*paperclip*.jar 2>/dev/null || true
fi

cat <<EOF

==> done.
    convert an existing world : java -jar <paperclip>.jar --folesiumConvertToFolesium --nogui
    start on Folesium         : java -Dfolesium.enabled=true -jar <paperclip>.jar --nogui
    roll back to Anvil        : java -jar <paperclip>.jar --folesiumConvertToAnvil --nogui
    docs                      : $SRC/docs/INTEGRATION.md (English) / docs/zh/INTEGRATION.md (Chinese)
EOF
