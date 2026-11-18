# Research — Phase A (2026-11-17)

Legend: **V** = verified in source/doc read this session, **A** = assumption / unverified.

## PumpkinMC plugin platform
- V: Upstream https://github.com/Pumpkin-MC/Pumpkin, shallow clone at commit `c60b81fc` (2026-11-17). Plugin tracking issue #1407.
- V: Plugins are **WebAssembly Components** (component model), not plain core modules. Host: `crates/pumpkin-wasm-host`, wasmtime **50.0.1**, features `component-model`, `component-model-async`, `gc`, `gc-drc`; engine enables `wasm_gc`, `wasm_exceptions`, `wasm_function_references` (`runtime.rs:302-320`).
- V: Contract is WIT: `crates/pumpkin-plugin-wit/v0.1` (package `pumpkin:plugin@0.1.0`, WASI **p2**) and `v0.2` (`@0.2.0`, async ABI, WASI p2+p3). Host cargo feature `v0_2` is documented as "Off until the v0.2 ABI is finished". Host selects the version from component metadata (`inspect_plugin`, `runtime.rs:272`).
- V: **Kotlin bindings exist: https://github.com/Pumpkin-MC/pumpkin-api-kt** (commit `61938b04`, 2026-11-04; Kotlin 2.4.0, Gradle 9.4.1, JDK 17+; generated from WIT **v0.1** via a Kotlin-enabled wit-bindgen; Gradle plugin `org.pumpkinmc.plugin`, `apiVersion` 0.1.2 not yet published -> built locally with `publishToMavenLocal`; needs Rust host toolchain). Marked experimental.
- V: Official guest bindings: Rust (`pumpkin-plugin-api`); TypeScript (`pumpkin-api-ts`, from a search result, not inspected). **No Kotlin binding; "Kotlin" appears nowhere in the upstream tree.**
- V: Exports: `init-plugin`, `on-load`, `on-unload`, `metadata`, `handle-event`, `handle-command`, ... Imports include `player`, `inventory`, `server`, `command`, `logging`, `scheduler`.
- V: Event `player-death-event-data { player, death-message, dropped-exp, keep-inventory, cancelled }` exists in v0.1 and v0.2 `event.wit`.
- V (`pumpkin-core/src/entity/living.rs:2846,2945`, `player.rs:5740-5775`): `player-death-event` is fired (blocking) from `broadcast_death_message`, **before** `Player::handle_killed` drops/clears the main inventory. So the pre-death inventory is observable in the handler => D-007 hook viable. Caveats: `PlayerDeathEvent::new` hard-codes `keep_inventory: false` and `dropped_exp: 0`, so the event flag is NOT the effective gamerule; read the `keepInventory` game rule instead. A: armor/offhand/XP handling at death and whether `drop_equipment` touches players' armor before the event (players' equipment appears to live in the inventory; unconfirmed). XP is reset at respawn (`world/mod.rs:4659`).
- V (v0.2 `player.wit`): `get-inventory -> player-inventory`, `get-inventory-item(slot)`, `set-inventory-item`, `get/set-experience-level|progress|points`.
- V: Filesystem: with permissions `fs.read.data` / `fs.write.data` the host preopens the plugin data folder as `/data` (`runtime.rs` ~470).

## Kotlin/Wasm side
- V (pumpkin-api-kt README): the Gradle plugin downloads a pinned `wasm-tools` 1.258.0, compiles and assembles+validates the component to `build/<name>.wasm`. This supersedes the A-items below on componentization.
- V (docs): `wasmWasi` is a supported Kotlin target (`withWasmWasi()`); it emits a **core WasmGC module using WASI preview1**, not a component.
- A: No official Kotlin component-model/WIT bindgen. Third-party `uk.shusek.krwa` (https://github.com/Shusek/kotlin-runtime-web-assembly, MIT, 0.3.7, early-stage) is a JVM-hosted runtime with WIT→Kotlin contract generation; not a guest binding for Pumpkin and unproven for this use.
- A: Conceivable path: Kotlin `wasmWasi` core module → handwritten canonical ABI shims (`@WasmExport`/`@WasmImport` are limited to primitives) → `wasm-tools component new` with a p1→p2 adapter. Requires linear memory + `cabi_realloc`; resources, strings, records, variants all hand-lowered. Never attempted.

## SQLite
- A: Kotlin/Wasm has no C FFI, so SQLite cannot be linked into the module. Options, all unverified: (a) compose a separate SQLite-in-wasm component; (b) pure-Kotlin implementation (not SQLite); (c) another backend (needs owner decision per D-005).
- A: WASI file-locking behaviour untested.

## Local environment
- V: Windows 11, JDK 26, git, Rust/cargo (msvc target only), Node. **Missing**: Gradle, wasm-tools, wit-bindgen, wasmtime, Pumpkin server binary.
- A: Kotlin Gradle plugin compatibility with JDK 26 unknown; JDK 17/21 toolchain may be required.

## Gate G0 — RESOLVED by owner: use pumpkin-api-kt (build/load still to be proven).
SQLite remains the open item (Gate G1).

## Pumpkin host bug: command suggestions crash the server (2026-11-18)
- V (owner crash report + source): `crates/pumpkin-wasm-host-v0_1/src/commands/executor.rs:149` (`WasmCommandSuggestionProvider::suggest`) calls `tokio::runtime::Handle::current()` from thread `Simulation-0`, which has no Tokio runtime -> panic "there is no reactor running". Triggered by Tab-completing a command node registered with `suggest-with-handler-id`. wRestore therefore does NOT register suggestions. Re-enable only after the host is fixed (needs owner approval to report upstream).
