# Third-party components in build/wRestore.wasm

Not legal advice; verify before redistributing binaries.

- **pumpkin-api-kt** (https://github.com/Pumpkin-MC/pumpkin-api-kt): generated WIT bindings and runtime glue are compiled into the plugin. The repository ships `LICENSE-MIT` and `LICENSE-APACHE`. It states it is derived from Kotlin/sample-wasi-http-kotlin (Apache-2.0).
- **Pumpkin WIT** (`pumpkin:plugin@0.1.0`): MIT, Apache-2.0 and MIT-Microsoft licence files are shipped alongside the WIT.
- **SQLite 3.54.1** (https://sqlite.org): public domain. Embedded in `components/sqlite/sqlite-comp.wasm`.
- **wasi-sdk 34 / wasi-libc** (https://github.com/WebAssembly/wasi-sdk): the C runtime linked into the SQLite component; its licences (Apache-2.0 with LLVM exception, MIT and others) apply to that part. Check the wasi-sdk release notices when publishing the component.
- **Kotlin stdlib** (Apache-2.0) is compiled into the plugin.
