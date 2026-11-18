# Spike G1 — SQLite from a Kotlin/WASI Pumpkin plugin (2026-11-17) — PASSED

Environment: owner-run Pumpkin server (0.2.2+26.3), WIT v0.1 plugin built with pumpkin-api-kt 0.1.2; permissions `fs.read.data` + `fs.write.data` granted at the load prompt.

## Verified on the real server (owner-pasted logs)
1. WASI p1 file access from Kotlin works and persists across restart (`previous=<none>` -> `previous=run`). Preopen is `fd3='data'` (guest path `data`).
2. **SQLite 3.54.1 as a separate WASI component, composed into the plugin component, works inside Pumpkin:**
   - run 1: database `data/spike.db` created, table created, INSERT ok, a `begin; insert; rollback` left `count=1,max(id)=1` (rollback verified), `SELECT` returned `rows=1 1`, invalid SQL returned `near "this": syntax error`.
   - after server restart, run 2: `rows=2 2` (**persistence across restart verified**).
3. The host accepted a composed component importing both WASI 0.2.6 (Kotlin adapter) and 0.2.12 (wasi-sdk 34).

## Root causes found (for future agents)
- Any WASI p1 call from Kotlin runs the adapter, which calls `cabi_realloc`; Kotlin leaves its "realloc allocator" open and any later `withScopedMemoryAllocator` (all `Logging.log` calls) throws `IllegalStateException` (kotlin-stdlib `MemoryAllocation.kt`: `check(reallocAllocator == null)`). A `catch` that logs re-throws. Rule: allocate with `cabi_realloc`, do all p1 / component-import calls inside `WasiFs.batch{}` (frees via `freeAllComponentModelReallocAllocatedMemory()`), log only afterwards.
- Gradle: unfiltered `mavenLocal()` shadows the Kotlin wasm stdlib; restrict it to group `org.pumpkinmc`.
- wasi-sdk 34 layout: `share/wasi-sysroot/include/wasm32-wasip{1,2,3}`; `--target=wasm32-wasip2` emits a component directly.

## Build recipe
`components/sqlite/rebuild.sh` (builds only the SQLite component; Gradle does the composition) (wasi-sdk 34 + SQLite amalgamation 3.54.1 + `wit-bindgen c` + `wasm-tools component embed/new/compose`). SQLite flags: `THREADSAFE=0, OMIT_LOAD_EXTENSION, OMIT_SHARED_CACHE, DEFAULT_LOCKING_MODE=1 (exclusive), OMIT_WAL, OMIT_POPEN`. Sizes: sqlite component 1.48 MB, composed plugin 1.85 MB.

## Not verified / known gaps
- Real fsync durability and crash-in-transaction recovery (journal_mode=delete); only graceful restarts tested.
- No POSIX locks (exclusive locking, single plugin instance assumed); two server processes sharing one data folder are unsupported.
- Binary data: spike WIT passes text only; production needs blob columns (item data) via `list<u8>` or hex/base64.
- Integration is manual (no Gradle task); `wasm-tools compose` is deprecated -> migrate to `wac`.
- Spike code in `WRestorePlugin.onLoad`, `SqliteSpike.kt`, `WasiFs.kt` is temporary.
