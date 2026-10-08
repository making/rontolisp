# e30. Emitted JS glue calls a host hook the tree shaker dropped

Difficulty: Low

`--no-wasi --optimize=<any but off> --emit-js-glue` on a program that draws no random
number: the module has no `__ronto_seed_random` export (dropped by
`WasmTreeShaker.HostCellHook`, `.kb/wasm-export-no-wasi.md` "Both hooks are DROPPED"), but
the glue still calls `exports.__ronto_seed_random(...)` unconditionally, so instantiation
throws and every request answers 500.

Measured 2026-10-08: `examples/clojure/ring-hello.clj -o src/worker.wasm --no-wasi
--optimize=size --emit-js-glue` under `wrangler dev` (4.148.0): `TypeError:
exports.__ronto_seed_random is not a function`. Module exports: `memory _initialize
__ronto_alloc __ronto_alloc_mark __ronto_alloc_reset __ronto_set_time handle-request`.
`examples/net/hello-clack.lisp` is unaffected only because clack draws a random number.
Guarding both calls by hand made the Worker serve every route.

Cause: `WasmLispCompiler` builds `HostGlueEmitter.Surface` with `seedRandom`/`setTime`
decided before `shakeCore`. The same holds for `__ronto_set_time` on a program that
reads no clock.

Fix: the surface follows the exports that SURVIVE the shake (not an `?.` guard in the
glue, which would hide a real mismatch). Failing test first: a `--no-wasi --optimize`
program with neither random nor clock, glue emitted, instantiated under node with `{}`
(`HostGlueEmitterTest` / `WasmExportCompilerTest`). Regenerate any checked-in
`examples/cloudflare-workers/*/src/worker.js` the fix changes.
