Strata native libraries (strata-ffi JNI layer)
================================================

These prebuilt libraries are loaded by dev.strata.bridge.StrataNative.load()
from the classpath resource /natives/<file> at server startup. They are
committed here so the Canvas CI (applyAllPatches + createPaperclipJar)
bundles them into the paperclip jar automatically — without them Strata
degrades to Anvil at runtime.

Contents:
- strata_ffi.dll  Windows x86_64 (MSVC)
- strata_ffi.so   Linux x86_64

Provenance:
- Source repo: https://github.com/wosnxn123/Strata (commit 8317697,
  master) — crates/strata-ffi built with `--features jni`.
- Built by Strata CI (actions/upload-artifact: strata-native-<os>);
  download both artifacts and drop the files here verbatim.

Rebuild requirement:
- Whenever the Strata FFI surface changes (new/changed native methods in
  dev/strata/bridge/StrataNative.java), these files MUST be refreshed from
  a matching Strata build; a mismatch fails at first native call.
