# WASM backend: no single function body may grow without bound

Scope: the **GC WASM backend** (`codegen.wasm`), both the Preview 1 module and the
`--component` wrapping of it -- they share one `WasmLispCompiler.compile()`. `--no-gc`
is unaffected (it emits no `_start`; see [no-gc-scalar-wasm.md](no-gc-scalar-wasm.md)).

## The invariant

**The size of the largest emitted function body decides whether a program can be run
at all.** A wasmtime cold compile (Cranelift) needs memory that grows *superlinearly*
-- measured at roughly the 1.8th power -- in the size of ONE function body. Nothing
else about the module matters: not its total size, not its function count.

Measured on the concatenated `ci-spec.yaml` corpus, cold cache, wasmtime 47.0.2,
Linux amd64:

| largest body | wasmtime peak RSS | wall |
|---|---|---|
| 9 KB | 284 MB | 0.2 s |
| 261 KB | 2.7 GB | 4.5 s |
| 437 KB | 7.4 GB | 12 s |
| 630 KB (`6645c6d`) | 15.1 GB | 22 s |
| 850 KB (`develop`, 2026-07) | 25.8 GB | 36 s |

Pinned by `WasmToplevelChunkingTest` (bound: 256 KiB) and guarded before the fact by
`CiSpecE2eTest.wasmCompileMemoryGuard`, which refuses to launch wasmtime on a module
over that bound.

## Why this is easy to miss

**The wasmtime CLI compilation cache is on by default** (`~/.cache/wasmtime`). The
first run of a given module pays the full cost; every later run of the same bytes is
a cache hit costing ~0.2 s and ~60 MB. So the blow-up is invisible on any developer
machine that has run the module once -- and *always* paid on CI, which starts cold.

There is no `--disable-cache` flag on wasmtime 47; to measure honestly, remove
`~/.cache/wasmtime` before each run.

The failure mode on a memory-bounded machine is not an error. A GitHub Actions
`ubuntu-latest` runner has 16 GB and no swap, so 25.8 GB gets the runner itself
reclaimed: `##[error]The runner has received a shutdown signal.`, no stderr, no
non-zero exit, and every other backend in the same job cancelled as a fail-fast peer.
That is what took the `native-image` job down after `f0efc2c` (`.todo/167`); the
630 KB body before it fit into 16 GB with nothing to spare, which is why the job had
been passing rather than healthy. macOS runners survived the same corpus because they
have compressed memory and swap, so the divergence looks like a Linux/macOS
`native-image` bug and is not one -- the `.wasm` emitted by the Linux native binary,
by the macOS one, and by the JVM build all behave identically.

**`ulimit -v` cannot be used to bound this.** wasmtime reserves 9-17 GB of *virtual*
address space regardless of how little it resides (linear-memory and GC-heap
reservations, worker stacks), so any `ulimit -v` low enough to catch a pathological
module also kills a trivial one. A cgroup cap (`systemd-run -p MemoryMax=`) does work,
but is not portable to every CI runner. Checking the emitted body size before running
is the portable check, and it names the actual cause.

## Where bodies get large

`WasmLispCompiler.compile()` Pass 2b builds the `_start` body by concatenating every
top-level expression into one function. A long program therefore has exactly one
pathological function, and it grows linearly with the source.

The **async top-level path already chunks** for this precise reason:
`WasmAsyncEmit.compileTopLevelChunk` outlines a run of top-level statements into a
zero-arg function registered as a `LambdaInfo`, and its doc comment records that the
un-chunked form "grows past what Cranelift compiles in sane time (superlinear; the
full corpus never finished)". The synchronous path is the one that must not regress.

Constraints any chunker has to respect (all verified against the current backend):

- `let`/`let*` locals do not leak across top-level forms -- `WasmLetCompiler` saves and
  restores `ctx.locals`, so a cut between forms is safe.
- `defvar`/`setq`/`setf` of a symbol **directly** at top level are promoted to module
  globals by `GlobalVarCollector`, visible from any function. But `GlobalVarCollector`
  only inspects each top-level form's head symbol, so a *nested* `(when x (setq new 1))`
  still allocates a `_start` local -- a cut between that form and a later reader of
  `new` breaks it.
- `ctx.definedGlobals` is per-`Ctx` and drives `defvar`'s compile-time idempotence; it
  must be hoisted to shared state before chunks get their own `Ctx`, or a name defined
  in two chunks is initialised twice.
- Block/branch targets, `tagbody`/`go` scopes, `handler-case`/`unwind-protect` regions
  and special-binding scopes are all balanced within a single top-level form.
- Every top-level form's value is dropped and `_start` returns nothing (or a literal
  `i32.const 0` under `--component`), so no value has to flow between chunks.
- Chunks must be registered during Pass 2b: Pass 2c iterates `lambdaDecls` by index and
  picks up entries appended while it runs, but the function section (built later) will
  not.

## Re-evaluation trigger

Raise the 256 KiB bound only with fresh cold-cache measurements on the smallest CI
runner in use, and update the table above in the same change. The bound is a statement
about how much memory a user needs to run their own program, not a test detail.
