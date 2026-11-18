# Plan

Layers: `domain` (model, retention, confirmation state machine, restore orchestration) -> `SnapshotRepository` interface -> storage impl -> `pumpkin` adapters (events, commands, player/inventory). Domain stays platform-free.

## Gate G0 — Kotlin to Pumpkin component ABI (RESOLVED: use pumpkin-api-kt, WIT v0.1)
Original analysis kept for history:
Pumpkin loads WASM components described by WIT; Kotlin `wasmWasi` yields a core WasmGC/WASI-p1 module and nothing upstream binds Kotlin. Options:
1. **Kotlin spike**: `wasmWasi` + handwritten canonical ABI + `wasm-tools component new` + p1 adapter; prove a no-op plugin loads in a real Pumpkin build. Needs JDK 17/21, Gradle, wasm-tools, Pumpkin binary. Risky; large manual ABI surface.
2. **Rust plugin** using official `pumpkin-plugin-api` — fully supported, violates the Kotlin constraint.
3. **TypeScript plugin** using official bindings — violates the Kotlin constraint.
Owner decision required before Phase B. Gate G1 (SQLite) follows only if option 1 proves viable.

## Phases
A research (partial: death-event timing and p1 adapter unverified) · B bootstrap · C SQLite PoC · D core · E tests.
