# WASM backend: no single function body may grow without bound

Scope: the GC WASM backend (`codegen.wasm`), Preview 1 and `--component` alike (one
`WasmLispCompiler.compile()`). `--no-gc` is unaffected
([no-gc-scalar-wasm.md](no-gc-scalar-wasm.md)).

## The invariant
**The size of the largest emitted function body decides whether a program can be run at
all.** A wasmtime cold compile (Cranelift) needs memory growing ~superlinearly (~1.8th
power) in the size of ONE body; total module size and function count do not matter.
wasmtime 47.0.2, 4-core Linux amd64: 9 KB body = 284 MB; 261 KB = 2.7 GB; 630 KB =
15.1 GB; 850 KB = 25.8 GB / 36 s.

Bound: **256 KiB**, pinned by `WasmToplevelChunkingTest`, guarded before the fact by
`CiSpecE2eTest.wasmCompileMemoryGuard`. Raise it only with fresh cold-cache measurements
on the smallest CI runner, updating these numbers in the same change.

- **Two bodies grow with the program**: the top level (with source length) and the
  DISPATCH LADDER (with function COUNT).
- **The guard must measure the `--component` build separately** — an async top level
  compiles as an entry+resume pair, so either build can be larger.
  `WasmModuleInspector.largestFunctionBodySize` walks a component's embedded core modules.
- Easy to miss: wasmtime's compilation cache is on by default (`~/.cache/wasmtime`, no
  `--disable-cache` in 47 — delete the dir before each measurement); the failure is not an
  error but a reclaimed 16 GB runner (`The runner has received a shutdown signal.`, no
  stderr, zero exit, peers cancelled), and macOS runners survive the same corpus;
  `ulimit -v` cannot bound it (wasmtime reserves 9-17 GB of address space).

## Keeping the top level bounded
`WasmToplevelEmit.emit` (Pass 2b) closes a chunk once its body passes
`CHUNK_TARGET_BYTES` (48 KiB) and calls it from `_start`; each chunk is an arity-0
`LambdaInfo` called directly, never through the dispatch. The async top level
(`--component` with any `async-defun`/`async-lambda`/`await`) reaches the same chunker via
`WasmAsyncEmit.compileTopLevelChunkedProgn` with `guarded=true` (the resume's `$rt == 0`
guard) — **cutting at the awaits bounds nothing**, since an await-free run is as long as
the program; one `boxedVars` set is computed over the whole run so where it is cut cannot
change how a variable is stored.

Why cuts are safe:
- `WasmLetCompiler` saves and restores `ctx.locals`; block/branch targets, `tagbody`/`go`,
  `handler-case`/`unwind-protect` and special-binding scopes are balanced within one form;
  every form's value is dropped and `_start` returns nothing.
- **`GlobalVarCollector` collects assignments nested at any depth**, not only head
  position, and is deliberately blind to lexical scope. Without this, one nested `setq`
  anywhere disables chunking entirely — `WasmToplevelEmit` stops cutting the moment a
  chunk allocates a named local, the correctness backstop.
- `Ctx.definedGlobals` is SHARED by `WasmAsyncEmit.freshCtx`; a per-`Ctx` copy would let
  two chunks initialise one name.
- Chunks are registered during Pass 2b: Pass 2c picks up entries appended while it runs;
  the function section (built later) will not.

## Keeping the dispatch ladder bounded
The SPREAD dispatcher (`WasmRuntimeBuilder.buildDispatch(..., spread = true, ...)`, what
`_apply` calls) is a `br_table` over EVERY callable, ~110 bytes per case, ~410 at its
widest. Past `WasmRuntimeBuilder.DISPATCH_PAGE_BUDGET_BYTES` (64 KiB) it is emitted as a
TREE keyed on successive 8-bit digits of the funcId, ~256 cases per leaf, so **the body no
longer depends on the function count** — one extra call per level.
- **Pages are appended after EVERY other function**, so no index moves and a program
  needing none is byte-for-byte unchanged (`.kb/wasm-callable-arity.md`).
- **A page's signature is the dispatcher's own**, so no module gains a type entry: the
  page re-reads the funcId off the closure in local 0.
- **The gate is the emitted body's SIZE, not the callable count**, so the arity ladders on
  the `funcall`/`mapcar`/`sort` hot path stay one call deep.

Ask of any new outlining path what the async top level failed: *is the piece it cuts
bounded in BYTES, or only by where some syntactic marker falls?*

## Measuring
`-Drontolisp.wasm.debug-func-sizes` (any value), twin of
`rontolisp.jvm.debug-method-sizes`: one stderr line per SHIPPED function, largest first,
`[func-size] <bytes>\t<final index>\t<name>`, plus a total. Post-shake code-entry bytes;
lambdas and chunks as `_lambda_<id>`/`_toplevel_chunk_<id>`. Component path: the CORE
module. `--no-gc` not covered.
