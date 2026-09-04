# WASM backend: no single function body may grow without bound

Scope: the GC WASM backend (`codegen.wasm`), Preview 1 module and `--component` alike
(one `WasmLispCompiler.compile()`). `--no-gc` is unaffected
([no-gc-scalar-wasm.md](no-gc-scalar-wasm.md)).

## The invariant

**The size of the largest emitted function body decides whether a program can be run at
all.** A wasmtime cold compile (Cranelift) needs memory growing ~superlinearly (roughly
the 1.8th power) in the size of ONE function body; total module size and function count
do not matter. Scale, ci-spec corpus, wasmtime 47.0.2, 4-core Linux amd64: 9 KB body =
284 MB / 0.2 s; 261 KB = 2.7 GB / 4.5 s; 630 KB = 15.1 GB / 22 s; 850 KB = 25.8 GB / 36 s.

Bound: **256 KiB**, pinned by `WasmToplevelChunkingTest` (one case per top-level shape,
sync and async, plus the dispatch ladder) and guarded before the fact by
`CiSpecE2eTest.wasmCompileMemoryGuard`, which refuses to launch wasmtime on a module over
it.

**Two bodies grow with the program**: the top level (with source length) and the DISPATCH
LADDER (with function COUNT).

**The guard must measure the `--component` build separately.** A component is not the
Preview 1 module plus a wrapper: an async top level compiles as an entry+resume pair, so
bodies are cut differently and either build can be larger.
`WasmModuleInspector.largestFunctionBodySize` accepts a component binary and walks the
embedded core modules. Guarding only the core build once let a 650 KB component body
through while the core's largest was 214 KB.

## Why this is easy to miss

- **The wasmtime CLI compilation cache is on by default** (`~/.cache/wasmtime`); every
  run after the first costs ~0.2 s and ~60 MB, so the blow-up is invisible locally and
  always paid on cold CI. wasmtime 47 has no `--disable-cache`; remove the cache dir
  before each measurement.
- The failure mode is not an error. A 16 GB no-swap GitHub runner gets itself reclaimed:
  `##[error]The runner has received a shutdown signal.`, no stderr, no non-zero exit, all
  fail-fast peers cancelled. macOS runners survive the same corpus (compressed memory +
  swap), so it can look like a Linux/macOS `native-image` bug.
- **`ulimit -v` cannot bound this**: wasmtime reserves 9-17 GB of virtual address space
  regardless of residency. A cgroup cap works but is not portable; checking the emitted
  body size before running is the portable check.

## Keeping the top level bounded

`WasmLispCompiler.compile()` Pass 2b hands top-level forms to `WasmToplevelEmit.emit`,
which compiles them into chunk functions, closing a chunk once its body passes
`CHUNK_TARGET_BYTES` (48 KiB), and emits `ref.null eq; call <chunk>; drop` per chunk into
`_start`. Each chunk is an arity-0 callable registered as a `LambdaInfo` with a
precompiled body, called directly, never through the dispatch.

The **async top level** (`--component` with any `async-defun`/`async-lambda`/`await`,
i.e. every fetch/serve program) reaches the same chunker.
`WasmAsyncEmit.compileTopLevelChunkedProgn` outlines each await-free RUN of statements --
but **cutting at the awaits bounds nothing**, since an await-free run is as long as the
program. Runs therefore go through `WasmToplevelEmit.emit(exprs, ctx, boxedVars,
guarded=true)`, each chunk call wrapped in the resume's `$rt == 0` guard so a resume
targeting a later suspend state skips executed chunks. The async path also carries a
`boxedVars` set computed ONCE over the whole run and handed to every chunk cut from it,
so where a run is cut cannot change how a variable is stored.

Why cuts are safe:

- `let`/`let*` locals do not leak across top-level forms (`WasmLetCompiler` saves and
  restores `ctx.locals`).
- A `setq`/`defvar` whose name has no module global falls through to `allocLocal`, which
  an outlined chunk cannot see. So **`GlobalVarCollector` collects assignments nested at
  any depth inside a top-level form**, not only head position: `(print (progn (setq a 10)
  a))` gets a global. Collection is deliberately blind to lexical scope (a name that is
  only ever a `let` variable gets an unused store, since every site resolves a lexical
  slot first). **Without this, one nested `setq` anywhere disables chunking entirely** --
  `WasmToplevelEmit` stops cutting the moment a chunk allocates a named local, the
  correctness backstop.
- `Ctx.definedGlobals` drives `defvar`'s compile-time idempotence and is SHARED by
  `WasmAsyncEmit.freshCtx` with every outlined context; a per-`Ctx` copy would let two
  chunks initialise the same name.
- Block/branch targets, `tagbody`/`go` scopes, `handler-case`/`unwind-protect` regions
  and special-binding scopes are balanced within one top-level form, so a cut between
  forms never splits one.
- Every top-level form's value is dropped and `_start` returns nothing (or `i32.const 0`
  under `--component`), so no value flows between chunks.
- Chunks are registered during Pass 2b: Pass 2c iterates `lambdaDecls` by index and picks
  up entries appended while it runs; the function section (built later) will not.

## Keeping the dispatch ladder bounded

The SPREAD dispatcher (`WasmRuntimeBuilder.buildDispatch(..., spread = true, ...)`, the
one `_apply` calls) is a `br_table` over EVERY callable with one case body each -- ~110
bytes, ~410 at its widest, since a spread case walks its target's required parameters out
of the argument list. It grows with nothing a test author can see, so its overflow names a
bound nobody changed.

Past `WasmRuntimeBuilder.DISPATCH_PAGE_BUDGET_BYTES` (64 KiB) the ladder is emitted as a
TREE: the fixed-index dispatcher reads the TOP 8-bit digit of the funcId and calls a page,
which reads the next digit, down to a leaf holding ~256 cases. Every node is bounded by
the radix, so **the emitted body no longer depends on the program's function count** --
one extra call per level.

Three properties the shape depends on:

- **Pages are appended after EVERY other function**, so no index moves and a program
  needing none is byte-for-byte unchanged. Their count is unknowable where
  `userFuncBase()` is fixed (lambdas and top-level chunks are still being registered),
  hence the end rather than a conditional block like the extra arity dispatchers
  (`.kb/wasm-callable-arity.md`).
- **A page's signature is the dispatcher's own**, so no module gains a type entry: the
  page re-reads the funcId off the closure in local 0 rather than taking it as a
  parameter. Valid because the root has already normalised a SYMBOL designator into a
  synthesized closure and handed an interpreted one (`funcId == -1`) to `_apply`.
- **The gate is the emitted body's SIZE, not the callable count.** Dispatchers under the
  budget are emitted exactly as before paging existed, so the arity ladders -- on the
  `funcall`/`mapcar`/`sort` hot path -- stay one call deep.

## Re-evaluation trigger

Raise the 256 KiB bound only with fresh cold-cache measurements on the smallest CI runner
in use, updating the numbers above in the same change.

Ask of any new outlining path what the async top level failed: *is the piece it cuts
bounded in BYTES, or only by where some syntactic marker falls?* An await, a
`handler-case`, a `tagbody` tag bound nothing. One enormous user `defun` is still one
function and still pays the superlinear cost.

## Measuring: `-Drontolisp.wasm.debug-func-sizes`

Wasm twin of `rontolisp.jvm.debug-method-sizes`. Set the property (any value): every
compile prints one stderr line per SHIPPED function, largest first --
`[func-size] <bytes>\t<final index>\t<name>` -- plus a total. Sizes are post-shake
code-entry bytes; names come from `WasmFunctionInfo.funcIndex`/`LambdaInfo` (lambdas and
chunks as `_lambda_<id>`/`_toplevel_chunk_<id>`) and `FUNC_*` helpers by reflection,
joined through `WasmTreeShaker.shakeWithRemap`. Component path: the CORE module, before
wrapping. `--no-gc` not covered.
