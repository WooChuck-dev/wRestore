# wRestore
Death-inventory snapshots and admin restore plugin for PumpkinMC (WooChuck). Version 0.1.0-beta.1.

**Status: early development.** Verified on a real Pumpkin server: the component loads, WASI file access works and an embedded SQLite database persists across restarts. Domain logic has unit tests. Implemented: death snapshots, `/restore history|inspect|apply|confirm|cancel` with recovery points and audit (owner-reported working; edge cases still being verified). See `specs/001-death-snapshots/`.

## Build
Needs JDK 17+ (tested with JDK 26) and a Rust toolchain (to build the bindings once).
`pumpkin-api-kt` is not published yet, so publish it locally first:

```sh
git clone --recurse-submodules https://github.com/Pumpkin-MC/pumpkin-api-kt
cd pumpkin-api-kt && ./gradlew :api:publishToMavenLocal :gradle-plugin:publishToMavenLocal
```

Then in this repo: `./gradlew build` -> `build/wRestore.wasm` (Kotlin plugin + prebuilt SQLite component, validated; domain unit tests run as part of the build). Copy it to the server's `plugins` directory.

The SQLite component (`components/sqlite/sqlite-comp.wasm`, SQLite 3.54.1, checked in with a SHA-256) only needs rebuilding when its C glue changes: `components/sqlite/rebuild.sh` (needs wasi-sdk 34, ~620 MB).
Note: `mavenLocal()` is restricted to `org.pumpkinmc` on purpose; an unfiltered `~/.m2` can shadow the Kotlin wasm stdlib and break compilation.

License: MIT OR Apache-2.0, at your option (`LICENSE-MIT`, `LICENSE-APACHE`). Bundled third-party components: see `THIRD_PARTY.md`.

## Usage
Requires permission `wRestore:command.restore` (default: operators, level 3). Config: `data/config.toml` (`[storage] maxSnapshotsPerPlayer = 50`, applied after restart).
- `/restore history <player> [page]` lists death snapshots (works for offline players).
- `/restore inspect <id>` shows metadata and items.
- `/restore apply <id>` then `/restore confirm` (within 60 s) replaces the online player's inventory and XP; `/restore cancel` discards.
Health, hunger, effects and position are never restored. A recovery point of the previous state is saved first.
