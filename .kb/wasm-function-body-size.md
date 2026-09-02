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
| 850 KB (before chunking) | 25.8 GB | 36 s |
| **75 KB (after chunking)** | **3.7 GB** | **1.8 s** |

Peak RSS also scales with how many bodies wasmtime has in flight, which is one per
core, so the numbers above are a 4-core-runner shape. Measured on a 64-core host the
same corpus costs more in absolute terms but the ratio is what carries: the
`--component` build of the corpus went from **23.2 GB / 19.2 s** (largest body
650 KB, async top level un-chunked) to **9.0 GB / 5.1 s** (largest body 214 KB) --
the latter being exactly what the Preview 1 module of the same corpus costs.

The last row is the same corpus after `WasmToplevelEmit` split the top level: the
module is byte-for-byte equivalent in behaviour (identical 1405 lines of output on
all four backends) and costs 7x less memory and 20x less time to compile.

Pinned by `WasmToplevelChunkingTest` (bound: 256 KiB) -- one case per top-level shape,
synchronous and async, plus one for the dispatch ladder -- and guarded before the fact by
`CiSpecE2eTest.wasmCompileMemoryGuard`, which refuses to launch wasmtime on a module
over that bound.

**Two bodies grow with the program, not one.** The top level is the obvious one; the
other is the DISPATCH LADDER, and it grows with the program's function COUNT rather than
its length, which is why it was found the hard way (below).

**The guard must measure the `--component` build too, separately.** A component is not
the Preview 1 module plus a wrapper: an async top level compiles as an entry+resume
pair, so its bodies are cut differently and either build can be the larger one.
`WasmModuleInspector.largestFunctionBodySize` therefore accepts a component binary and
walks the core modules it embeds. Guarding only the core build is what let a 650 KB
component body through while the core build's largest was 214 KB -- the guard passed,
the Preview 1 leg passed, and the runner died on the component leg.

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

## How the top level is kept bounded

A program's top level is the one body that grows with the source, so it is the one
that must not be a single function. `WasmLispCompiler.compile()` Pass 2b hands the
top-level forms to **`WasmToplevelEmit.emit`**, which compiles them into chunk
functions -- closing a chunk once its body passes `CHUNK_TARGET_BYTES` (48 KiB) -- and
emits `ref.null eq; call <chunk>; drop` per chunk into `_start`. Each chunk is an
arity-0 callable registered as a `LambdaInfo` with a precompiled body, called
directly, never through the dispatch.

The **async top level** (`--component` with any `async-defun`/`async-lambda`/`await`,
i.e. every fetch/serve program and the component leg of the ci-spec corpus) reaches the
same chunker. Its resume outlines each await-free RUN of statements
(`WasmAsyncEmit.compileTopLevelChunkedProgn`) and keeps only the await statements plus
one guarded direct call per chunk -- but **cutting at the awaits bounds nothing**: an
await-free run is as long as the program. It used to outline each run *whole*, which is
how the ci-spec corpus got a 650 KB body on the component path while the synchronous
path of the identical program stayed at 214 KB. The runs now go through
`WasmToplevelEmit.emit(exprs, ctx, boxedVars, guarded=true)`, the same size-bounded
chunker, with each chunk call wrapped in the resume's `$rt == 0` guard.

Two things the async path needs that the synchronous one does not:

- Its chunk contexts carry a `boxedVars` set (a captured top-level variable must be
  boxed the same way in every chunk). It is computed ONCE over the whole run and handed
  to every chunk cut from it, so **where the run is cut cannot change how a variable is
  stored** -- the bodies are byte-identical to the single outlined run they replace.
- Each chunk call sits under `$rt == 0`, so a resume targeting a later suspend state
  skips the already-executed chunks. One guard per chunk, not per run.

Why the pieces are where they are:

- `let`/`let*` locals do not leak across top-level forms -- `WasmLetCompiler` saves and
  restores `ctx.locals` -- so a cut between forms is safe as far as `let` is concerned.
- A `setq`/`defvar` whose name has no module global falls through to `allocLocal`, i.e.
  a local of the enclosing top-level function, which a later top-level form then reads
  back. An outlined chunk cannot see another chunk's locals, so **`GlobalVarCollector`
  collects assignments nested at any depth inside a top-level form**, not only the ones
  in head position: `(print (progn (setq a 10) a))` gets a global like a head-position
  `setq` would. Every backend already let a later top-level form read such a name, so
  this aligns the implementation with the semantics all four already had. The
  collection is deliberately blind to lexical scope -- a name that is only ever a `let`
  variable gets a store it never uses, because every site resolves a lexical slot
  first. **Without this, one nested `setq` anywhere in the program disables chunking
  entirely** (`WasmToplevelEmit` stops cutting the moment a chunk allocates a named
  local, which is the correctness backstop).
- `Ctx.definedGlobals` drives `defvar`'s compile-time idempotence and is shared by
  `WasmAsyncEmit.freshCtx` with every outlined context; a per-`Ctx` copy would let two
  chunks each initialise the same name.
- Block/branch targets, `tagbody`/`go` scopes, `handler-case`/`unwind-protect` regions
  and special-binding scopes are all balanced within a single top-level form, so a cut
  between forms never splits one.
- Every top-level form's value is dropped and `_start` returns nothing (or a literal
  `i32.const 0` under `--component`), so no value has to flow between chunks.
- Chunks are registered during Pass 2b: Pass 2c iterates `lambdaDecls` by index and
  picks up entries appended while it runs, but the function section (built later) will
  not.
- A chunk context on the synchronous path leaves `boxedVars` at its default, which is
  what `_start` itself uses, so a chunk's body is byte-identical to the run it was cut
  from. The async path passes its run's set for the same reason (above).

## How the dispatch ladder is kept bounded

The other body that grows with the program is the SPREAD dispatcher
(`WasmRuntimeBuilder.buildDispatch(..., spread = true, ...)`), the one `_apply` calls: a
`br_table` over EVERY callable in the program with one case body each -- ~110 bytes, and
~410 at its widest, since a spread case walks its target's required parameters out of the
argument list. On 2026-09-02 the `ci-spec.yaml` corpus (about 3000 callables) stood at
**261,821 bytes of it against the 262,144 bound: three callables of headroom**, while the
next largest body in the module was a 72 KB user function. It grows with nothing a test
author can see, so the failure it produces names a body bound nobody changed -- which is
what `.todo/630` hit and worked around by rewriting its one new case with macros.

Past `WasmRuntimeBuilder.DISPATCH_PAGE_BUDGET_BYTES` (64 KiB) the ladder is emitted as a
TREE instead of one body: the fixed-index dispatcher reads the TOP 8-bit digit of the
funcId and calls a page, which reads the next digit, down to a leaf holding the ~256 cases
of one funcId page. Every node is bounded by the radix, so **the emitted body no longer
depends on how many functions the program has** -- one extra call per level buys it, and
only for a program that was near the bound anyway. Measured on the corpus that same day:
the spread dispatcher went from 258,887 bytes to a 236-byte root plus twelve pages of
15-26 KB, and the module's largest body from 258,887 to 68,677 (Preview 1) / 262,109 to
72,868 (`--component`) -- in both builds the largest is now a top-level chunk, i.e. the
size-bounded thing.

Three properties the shape depends on:

- **The pages are appended after EVERY other function**, so no existing index moves and a
  program that needs none is byte-for-byte what it was. Their count cannot be known where
  `userFuncBase()` is fixed -- lambdas and top-level chunks are still being registered
  then -- which is why they go at the end rather than into a conditional block like the
  extra arity dispatchers (`.kb/wasm-callable-arity.md`).
- **A page's signature is the dispatcher's own**, so no module gains a type entry: the
  page re-reads the funcId off the closure in local 0 rather than taking it as a
  parameter. That works because the root has already normalised a SYMBOL designator into
  a synthesized closure and handed an interpreted one (`funcId == -1`) to `_apply`.
- **The gate is the emitted body's SIZE, not the callable count.** Every dispatcher under
  the budget is emitted exactly as it was before paging existed, so the arity ladders --
  which are on the `funcall`/`mapcar`/`sort` hot path, and which the corpus keeps at
  16-43 KB by splitting its callables across eleven arities -- stay one call deep. A
  ladder is paged only when its own body says it must be.

## Re-evaluation trigger

Raise the 256 KiB bound only with fresh cold-cache measurements on the smallest CI
runner in use, and update the table above in the same change. The bound is a statement
about how much memory a user needs to run their own program, not a test detail.

A new emission path that outlines code into functions must be asked the same question
the async top level failed: *is the piece it cuts bounded in BYTES, or only by where
some syntactic marker happens to fall?* An await, a `handler-case`, a `tagbody` tag are
all syntactic markers, and none of them bound anything.

Chunking bounds the TOP LEVEL and paging bounds the DISPATCH LADDER, which are the two
bodies that grow with the program. One
enormous user `defun` is still one function and still pays the superlinear cost --
there is no way to outline it without changing what the user wrote. If that ever
becomes a real limit, the measurements above are what to reason from.

## Measuring per-function sizes: `-Drontolisp.wasm.debug-func-sizes`

The wasm twin of `rontolisp.jvm.debug-method-sizes` (todo-315): set the property (any
value) and every compile prints one stderr line per SHIPPED function, largest first --
`[func-size] <bytes>\t<final index>\t<name>` -- plus a total. Sizes are the post-shake
code-entry bytes (the artifact's actual numbers, LEB re-encoding included): the names
come from `WasmFunctionInfo.funcIndex`/`LambdaInfo` (defuns by Lisp name, lambdas and
top-level chunks by their `_lambda_<id>`/`_toplevel_chunk_<id>` synthetic names), the
`FUNC_*` runtime helpers by reflection over the constants, joined through the shaker's
index remap (`WasmTreeShaker.shakeWithRemap`, which is `shake` plus the old-to-new
function mapping). On the component path the dump covers the CORE module, before
wrapping. `--no-gc` is not covered. This is what answered todo-315's "which are the two
44.9/29.4 KB bodies" question (chipz's `%MAKE-BZIP2-STATE` BOA constructor, and a
`labels` state-machine lambda) -- reach for it before any size work on this backend.
