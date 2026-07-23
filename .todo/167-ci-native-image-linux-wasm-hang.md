# WASM backend emits one unbounded `_start` body; wasmtime cold compile OOM-kills CI

**Root cause found (2026-07-23, reproduced on a Linux amd64 host).** The original
hypothesis in this file -- a GraalVM Linux `native-image` codegen bug -- is **wrong**
and has been ruled out. What remains to do is the actual fix.

## What it really is

`WasmLispCompiler.compile()` Pass 2b concatenates every top-level form into a single
`_start` function body. A wasmtime cold compile (Cranelift) needs memory superlinear
-- about the 1.8th power -- in the size of ONE function body:

| largest body | wasmtime peak RSS |
|---|---|
| 9 KB | 284 MB |
| 261 KB | 2.7 GB |
| 437 KB | 7.4 GB |
| 630 KB (`6645c6d`, last green) | 15.1 GB |
| 850 KB (`develop`) | 25.8 GB |

A GitHub `ubuntu-latest` runner has 16 GB and no swap, so the last row gets the runner
itself reclaimed -- `The runner has received a shutdown signal.`, no stderr, no
non-zero exit, peers cancelled by fail-fast. `6645c6d` was passing only because 15.1 GB
happened to fit; `f0efc2c` widened the corpus's body to 850 KB and pushed it over.

Why it looked like a Linux/macOS `native-image` divergence: **the wasmtime CLI
compilation cache is on by default**, so the cost is paid once per distinct module and
never again on that host. macOS runners additionally survive it via compressed memory
and swap. The `.wasm` emitted by the Linux native binary, the macOS native binary and
the JVM build all behave identically -- verified by running all three.

Full write-up, measurements and the constraints a chunker must respect:
`.kb/wasm-function-body-size.md`.

## Already landed

- `WasmToplevelChunkingTest` -- pins the bound (256 KiB) on the largest emitted
  function body. **Currently failing on purpose**: a 12000-form top level compiles to
  one ~400 KB body. It goes green when the fix below lands.
- `CiSpecE2eTest.wasmCompileMemoryGuard` -- compiles the corpus, checks the largest
  body, and refuses to launch wasmtime when it is over the bound. Converts the
  runner-killing OOM into an ordinary named failure and lets the INTERPRETER and JVM
  legs of the same run finish.

Note the `ulimit -v` idea this file used to propose was measured and **does not work**:
wasmtime reserves 9-17 GB of virtual address space regardless of residency, so no
`ulimit -v` separates a healthy module from a pathological one.

## The fix still to do

Chunk the synchronous top level, the way the async path already does
(`WasmAsyncEmit.compileTopLevelChunk`, whose doc comment records the same superlinear
finding): outline runs of top-level forms into zero-arg functions and emit
`ref.null eq; call <chunk>; drop` into `_start`.

Two things beyond copying that function:

1. Hoist `Ctx.definedGlobals` into shared state, or a `defvar` of the same name in two
   chunks initialises twice.
2. `GlobalVarCollector` only inspects each top-level form's head symbol, so a nested
   `(when x (setq new 1))` allocates a `_start` local that a later form reads. Either
   walk nested `setq`/`defvar` when collecting, or refuse to cut across such a pair.

Then: verify on all four backends, re-measure cold-cache wasmtime RSS (delete
`~/.cache/wasmtime` first), and update the table in `.kb/wasm-function-body-size.md`.
