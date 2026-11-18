#!/usr/bin/env bash
# Rebuilds components/sqlite/sqlite-comp.wasm (only needed when impl.c, sqlite.wit or SQLite changes).
# Needs: wasi-sdk 34 ($WASI_SDK), SQLite amalgamation 3.54.1 dir ($SQLITE_SRC), wit-bindgen from pumpkin-api-kt's
# build ($WIT_BINDGEN). Then commit the new .wasm and .sha256.
set -euo pipefail
: "${WASI_SDK:?} ${SQLITE_SRC:?} ${WIT_BINDGEN:?}"
HERE="$(cd "$(dirname "$0")" && pwd)"; GEN="$(mktemp -d)"
"$WIT_BINDGEN" c --world sqlite --out-dir "$GEN" "$HERE/wit"
"$WASI_SDK/bin/clang" --target=wasm32-wasip2 -O2 -I"$SQLITE_SRC" -I"$GEN" \
  -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_OMIT_SHARED_CACHE -DSQLITE_DEFAULT_LOCKING_MODE=1 \
  -DSQLITE_OMIT_WAL -DSQLITE_OMIT_POPEN -D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_GETPID \
  "$GEN/sqlite.c" "$GEN/sqlite_component_type.o" "$HERE/impl.c" "$SQLITE_SRC/sqlite3.c" \
  -o "$HERE/sqlite-comp.wasm" -lwasi-emulated-process-clocks -lwasi-emulated-getpid
(cd "$HERE" && sha256sum sqlite-comp.wasm | cut -d' ' -f1 > sqlite-comp.wasm.sha256)
echo "rebuilt: $HERE/sqlite-comp.wasm"
