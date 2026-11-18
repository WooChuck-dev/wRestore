# AGENTS.md — wRestore
- Kotlin only, target `wasmWasi`, PumpkinMC. No Paper/Bukkit/Fabric/JVM plugin/Java. Package `dev.woochuck.restore`.
- Never assume APIs: verify against Pumpkin source (`crates/pumpkin-plugin-wit`). Pumpkin plugins are WASM components; see specs/001-death-snapshots/research.md.
- Decisions D-001..D-007 in specs/001-death-snapshots/spec.md are final. SQLite must not be silently replaced.
- Domain code is platform-free; storage behind `SnapshotRepository`; UTC timestamps; UUID identities.
- Never delete before the replacement is durably saved; never report success on partial work; don't log full inventories.
- No pushes/publishing without owner approval. Chat in Polish; code and docs in English.

## Hard-won rules (each cost a failed run on a real server; see specs/001-death-snapshots/spike-g1.md and research.md)
- **Allocator rule.** WASI p1 calls and the SQLite component allocate through `cabi_realloc`, which leaves Kotlin's realloc allocator open; any later `withScopedMemoryAllocator` (every `Logging.log` and every generated host call) then throws. Do all p1/SQLite work inside `WasiFs.batch{}`, allocate buffers with `WasiFs.alloc`, and never call host/Logging APIs inside a batch. Log after it returns. A `catch` that logs can re-throw.
- **Never open the production database from a test/self-test.** `Storage.use(path)` requires an explicit path; use `":memory:"` for checks.
- **Permission nodes** must start with the plugin name exactly as in `metadata()` (`wRestore:`), or the host rejects them (the Kotlin `Result` then shows `null` as the message).
- **No command suggestions.** `suggest-with-handler-id` panics the Pumpkin host on Tab (`executor.rs:149`, no Tokio reactor). Do not re-add until fixed upstream.
- **XP**: Pumpkin's "experience points" are points within the current level. Restore = set level, then points, then read back.
- **Death event**: fires before the inventory is cleared, but its `keep-inventory` field is hard-coded false; read the game rule instead.
- **Build**: `./gradlew build` (JDK 17+) composes the Kotlin component with the prebuilt SQLite component (`components/sqlite`, SHA-256 checked) and validates it. `mavenLocal()` must stay restricted to `org.pumpkinmc`. `pumpkin-api-kt` is not published; see README for the local publish step.
- **Tests**: domain logic lives in `domain/` (pure Kotlin, `./gradlew :domain:wasmWasiNodeTest`). Pumpkin-facing code (`src/wasmWasiMain`) can only be verified on a live server; say so instead of claiming it works.
