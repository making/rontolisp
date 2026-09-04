# `rontolisp:wasm-export` (host-callable Lisp functions) + `--no-wasi` reactor mode

> JVM twin: [jvm-export.md](jvm-export.md) — `rontolisp:jvm-export` + `--no-main`; shares
> `compiler/BoundaryType` and the exact-or-trap rule.

`(rontolisp:wasm-export 'name :as "alias" :params '(T...) :returns T)` exports a top-level
`defun` as a host-callable WASM function. `LispNames.WASM_EXPORT`, `rontolisp` package, not CL
standard. `:as` = `Decl.exportName()` (default the Lisp name; GC backend and
`NoGcWasmCompiler`). No-op on interpreter/JVM (returns the named symbol). Under `--component`
an export is core-exported then lifted — sync canonLift by default (WAVE-invokable), scalars
option-free, `:string`/`:s-expr` via the canonical string ABI, an I/O-bearing body needing
`:async t` (stackful async export) or it traps; ignored outside `--component`, rejected under
`--no-gc --component` (`.kb/wasi-component.md`). Below = Preview 1 / `--no-gc` core modules.

## Boundary types

`compiler/BoundaryType`, WIT spelling (`:s8`..`:u64`; `:int`/`:long` = permanent parse-time
aliases of `:s32`/`:s64`). Table + exact-or-trap range rule: `.kb/wit.md` ("The integer
boundary") — export boundary and WIT world are one contract.

- <=32-bit integer <-> i32 via `_int_new` on GC (i31 if it fits, else boxed exact,
  `.kb/wasm-bignum.md`); `:s64`/`:u64` <-> i64 on every backend, u64 >= 2^63 traps.
- `:float` <-> `TYPE_FLOAT` f64; `:bool` <-> nil/t i32; `:string`/`:s-expr` <-> `(ptr,len)` in
  linear memory; omitted/nil/`:void` returns = void.
- `:bytes` (both directives, GC core modules only): `(unsigned-byte 8)` vector, no UTF-8,
  caller-passes-the-buffer — trailing `(ptr,cap)`, answers the FULL length as one i32
  (`.kb/wasm-import.md`).
- `:float` is f64 deliberately; there is no internal f32 (`WasmLispCompiler.TYPE_FLOAT =
  struct{f64}`, scalar `FLOAT -> Type.F64`). Renaming it is a silent ABI break — an f32
  boundary must be additive (`:single`/`:f32`, optional `:double`).
- IMPORTS deliberately narrower: `WasmImportCompiler.KNOWN_PARAM_TYPES` =
  `{:s32, :float, :bool, :string, :s-expr, :bytes}`. Widening is its own change (`.kb/wit.md`).

## Emission

- `WasmExportCompiler` parses + emits wrappers; helpers `WasmExportRuntimeBuilder`; wiring
  `WasmLispCompiler` (Pass 1 collects `Decl`s from `topLevelExprs`, validates
  name-exists/arity, builds `ExportPlan`s).
- Memory types add `__ronto_alloc` (bump allocator) + `_string_from_mem` after lambdas at
  `FUNC_USER_BASE + numDefuns + numLambdas`, so `FUNC_*` stay fixed; a data segment seeds
  `HEAP_PTR_ADDR` so host calls work without `_start`.
- `--no-gc`: internal integer IS i64, so `:s64` pins param/return to i64 (no
  `i64.extend_i32_s`/`i32.wrap_i64`) — explicit `S64` branch in
  `NoGcWasmCompiler.compileWrapperBody`, `boundaryTy` falls through to INT, `requireSupported`
  rejects only `:s-expr`.
- **Pass-through wrapper elision** (`NoGcWasmCompiler.isPassThroughExport`): every param and
  the return crossing identically (`:s64` over inferred i64, `:float` over inferred f64 — the
  only two needing neither range guard nor narrowing) AND `Mem.used()` false => the export names
  the internal function directly. A `:long` return over an inferred f64 body still gets a
  truncating wrapper; a memory-using module keeps its heap reset and `:string` marshalling.

## `--no-wasi` (reactor mode)

CLI `--no-wasi`; `WasmLispCompiler(dynamic, component, noWasi)`. No `wasi_snapshot_preview1`
imports, so a host instantiates with no import object. Init entry exported as `_initialize`
(reactor ABI) instead of `_start`, keyed off `noWasi`; a tree-shaker root either way.
**Index stability**: the nine WASI import slots (indices 0-8, `IMPORT_FUNC_COUNT`) are filled
with internal stubs carrying the SAME type indices, so every `FUNC_*` constant stays valid.

**THE rule** (the invariant; the slot list is its consequence): a stub may answer when the
answer is *true of this module*; it may not answer when answering invents a value the program
cannot tell from a real one. A value the HOST hands over is not an invention.

| slot | behaviour |
| --- | --- |
| `fd_write` | SINK: `*nwritten = iovs[0].len`, errno 0 |
| `environ_sizes_get` / `environ_get` | EMPTY environment: 0 vars, 0 bytes, errno 0 |
| `path_open` | errno 44 `ENOENT` |
| `fd_close` / `fd_readdir` | errno 8 `EBADF` |
| `random_get` | SplitMix64 over `RANDOM_STATE_ADDR`; a host import under `--host-random` |
| `fd_read` | `unreachable` — answering would invent input |
| `clock_time_get` | `unreachable`, BACKSTOP only — clock built-ins read `HOST_TIME_ADDR` |

- The two `unreachable` bodies are stack-polymorphic, so one shape fits every signature.
- `random` no longer CALLS its slot: the step is inlined at the draw site on every build
  (`.kb/random.md`); only `--host-random`'s seeding call reaches it.
- Bodies in `WasmIoRuntimeBuilder` (`buildNoWasiFdWriteSinkBody`,
  `buildNoWasiEnvironSizesGetBody`, `buildNoWasiRandomGetBody`, `buildNoWasiErrnoBody`); emit
  loop = the `if (this.noWasi)` block in `WasmLispCompiler`'s code section.
- **Trap**: emitters pass ONE iovec and drop the errno, so a caller looping on `*nwritten`
  needs a wider stub. The sink is what lets `clack:clackup` (`:server :rontolisp` /
  `:server :reactor`) load on a reactor (`.kb/clack.md`).
- Pinned WHOLE, all nine bodies byte for byte, by
  `WasmExportCompilerTest.noWasiStubsAnswerWhereverThereIsATrueAnswerAndTrapWhereThereIsNot`;
  also `noWasiModule*` in `WasmLispCompilerIntegrationTest`, and the `--no-wasi` leg of
  `examples/cloudflare-workers/`.

## `--component --no-wasi` = a reactor component that imports nothing

Core module keeps the Preview 1 no-WASI contract (stubs, fd_write sink,
`NoWasiFilesystemStubs`, `DATA_BASE_OFFSET` 256, no page-6 base) but declares/exports its OWN
memory, keeps `TYPE_START` at `() -> ()`, exports NO `run`/`_initialize`, and carries a core
start section (`WasmWriter.writeStartSection(FUNC_START)`, between export and code; the shaker
roots and renumbers it). **Top-level forms run at instantiation** — the one component shape
where an `--invoke`d export sees `defparameter` globals assigned.

- `WasmComponentBuilder.buildReactor`: no import block/adapter/mem module at either
  `--optimize` level; core module 0 -> core instance 0 -> per-export alias/type/lift/export via
  `appendFuncExports`, every cursor 0; string boundaries lift over the core's own memory + its
  `cabi_realloc`/`cabi_post_*`. `--emit-wit` = `WitEmitter.VARIANT_REACTOR` (`nogc` empty world).
- `Ctx.component` = `component && !noWasi`; `Ctx.noWasi` feeds reject messages (fetch/wait-for/
  tcp name the `--no-wasi` conflict); `Ctx.reactorComponent` = the raw shape `Ctx.component`
  deliberately reports as false; `asyncMode` off.
- The CLI gates the five wasi:*-binding library splices (http/wait/sockets/stdin/environment)
  as ONE decision (`spliceBackend` = `WASM_GC` for a reactor, `RontoLispCli`); the compiler
  enforces it (`componentImports` non-empty + reactor = error naming the interface; a user
  `rontolisp:wit-import` hits the same guard). serve + `--no-wasi` = ctor hard error.
- `reactor = noWasi || noGc` in the CLI, so `#+rontolisp-reactor` (the clack shim) selects the
  reactor branch under the component too.
- **Trap**: a trap in a top-level form kills INSTANTIATION; no `_initialize` exists to wrap in
  try/catch, so what a top-level form may hit is decided by the stub policy.
- Plain `--component` and plain `--no-wasi` outputs stay byte-unchanged. Pinned by
  `componentNoWasiIsAReactorThatImportsNothing`,
  `componentNoWasiReactorRecordsTheEmptyWorldWit` (both optimize levels), the
  `componentNoWasiReactor*` invoke tests; jco `--instantiation sync -b 0
  --bindgen-enable-wasm-exnref` output instantiates with `{}` on plain node
  (`examples/cloudflare-workers/httpbin-component`).

## Randomness

SplitMix64 (golden-ratio gamma + two xor-shift-multiply rounds) over the 8-byte cell at
`RANDOM_STATE_ADDR`; its zero start state is a valid seed, so no data segment seeds it. EVERY
wasm build draws from it (`.kb/random.md`); a build with a host seeds from eight `random_get`
bytes on its first draw. `--no-wasi` alone keeps the fixed start state — unseeded, every
instance walks the same sequence, which is inside the CL contract (`*random-state*` may start
fixed; `make-random-state` answers `nil`). The entropy API is deliberately NOT served from it:
`rontolisp::%random-byte` (behind `rontolisp:random-bytes`) lowers to a call-time error under
`--no-wasi` (`WasmExprCompiler`).

**`__ronto_seed_random (i64) -> ()`**: a `--no-wasi` CORE module exports a one-instruction hook
overwriting `RANDOM_STATE_ADDR`; call it before `_initialize`, early enough for a library's
LOAD-TIME `(random ...)`. An EXPORT, since a core wasm import is not optional and would break
instantiation with `{}`. Emitted when `noWasi && !component`, after the memory helpers
(`memoryHelperCount`: only COMPUTED wrapper/ABI bases shift, every fixed `FUNC_*` is under
`userFuncBase()`), `(i64)->()` type after the host-arena pair at `abiTypeBase`. NOT on the
reactor COMPONENT (top level runs at instantiation, so there is no window before the load-time
draws). **Seeding does NOT re-enable `random-bytes`** — SplitMix64 is invertible from one
output. Pinned by `noWasiCoreModuleExportsTheHostSeedHook`.

**`--host-random`** (first opt-in out of the zero-import contract):
`WasmLispCompiler(dynamic, component, noWasi, optimize, serve, simd, hostRandom)`. The
module-local generator is SEEDED from the host on its first draw; `rontolisp:random-bytes`
takes the host's bytes one at a time, the property the flag exists for. Slot `FUNC_RANDOM_GET`
becomes a forwarding body (`WasmIoRuntimeBuilder.buildNoWasiHostRandomGetBody`) over ONE
injected import `env.random_get(buf, len) -> errno` (module `env`, preview1's signature, so no
WASI function is imported and a host can forward its own); the other eight stubs untouched.

- Appended LAST in `hostImports`, reusing the fixed `TYPE_INTERN` `(i32,i32)->i32` rather than
  appending a type — program import ordinals/bytes and `abiTypeBase` (planned over
  `importSlots`) untouched.
- `__ronto_seed_random` NOT emitted (`seedRandom = noWasi && !component && !hostRandom`);
  `WasmExprCompiler`'s `RANDOM_BYTE_INTERNAL` gate becomes `ctx.noWasi && !ctx.hostRandom`.
- Rejected in the ctor without `--no-wasi` and with `--component`; `--no-gc` rejected in the CLI.
- Pinned by `hostRandomForwardsTheRandomGetSlotAndRetiresTheSeedHook`,
  `hostRandomJoinsTheOrdinalSpaceLastAndIsTheOnlyImportOnItsOwn`,
  `underHostRandomTheEntropyApiReachesTheHostAndAnUnusedImportIsStillShaken`
  (`WasmImportCompilerTest`); the last also pins the un-gated `%random-byte` — a program that
  never draws is shaken back to zero imports.

## `--host-fetch` (second opt-in): `rontolisp:fetch` over `env.fetch`

Splices a LISP lowering (`eval/HostFetchLibrary`, CLI-gated on `noWasi && hostFetch` and on the
program referencing `rontolisp:fetch`) whose `rontolisp:wasm-import`s ride the ordinary
synthetic-defun machinery, appended after the program so program import ordinals are unchanged:
`env.fetch(request-json) -> response-head-json` (`:string`->`:string`) and
`env.readResponseBody(ptr, cap) -> i32` (`:bytes`, `:async t`), the reply BODY out of band.
Envelope from `compiler/FetchResponseShape`; result is the same `(:status :headers :body)`
plist as every backend; the future is the settled `TYPE_P1_FUTURE`, settled at the reply's
HEAD, so only a failure before the headers signals at the call (`.kb/fetch-http.md`). Guards
mirror `--host-random`: requires `noWasi`, rejects `component`, CLI rejects non-wasm output and
`--no-gc`. The BUILD prints the standing host obligation (a synchronous `env.fetch` is always
valid; a `WebAssembly.Suspending` one requires `promising`-entered exports and serialised
calls). A build that never fetches gets no splice and no import (`WasmImportCompilerTest`).

## `__ronto_set_time (i64) -> ()` — the clock

Seed hook's twin on a `--no-wasi` CORE module; writes nanoseconds since the Unix epoch
(preview1's `clock_time_get` unit) into `HOST_TIME_ADDR` (cell 224, 8 bytes, under the
`DATA_BASE_OFFSET=256` headroom, no data segment — zero-initialized memory IS the unset state).
`get-universal-time` / `get-internal-real-time` / `get-internal-run-time` read THAT cell
(`WasmTimeCompiler.compileFromHostCell`) instead of the slot.

- **While the cell is zero the three built-ins SIGNAL** — a catchable condition naming the
  operator and the hook, via `callTimeUnsupportedStub` under an `i64.eqz` guard; zero is 1970,
  an answer a program could not tell from a real one.
- The clock does not advance between host calls, matching a Worker's request-frozen clock;
  every `examples/cloudflare-workers/*/src/index.js` calls the setter before `_initialize`
  (what makes `lack-middleware-session`, which timestamps while it LOADS, loadable) and once
  per request.
- Emitted when `noWasi && !component`, independently of the seed hook (so `--host-random`,
  which retires that one, leaves this one alone), appended after it, sharing its `(i64)->()`
  type. NOT on the reactor COMPONENT; the refusal MESSAGE differs there
  (`Ctx.reactorComponent`).
- **`sleep` refuses on `--no-wasi`** (`WasmExprCompiler`): Preview 1 elapses an interval by
  SPINNING on the clock, which cannot advance during a call, so it would spin forever. It
  signals instead (argument still evaluated for effect).
- **Re-evaluation trigger**: a clock that advances DURING a call would be a `--host-clock` flag
  forwarding `clock_time_get` to an `env.clock_time_get` import, as `--host-random` does — at
  the same cost to zero imports.
- Pinned by `noWasiCoreModuleExportsTheHostClockHook`,
  `noWasiModuleSignalsForTheClockAndForRealEntropy`,
  `noWasiModuleRefusesToSleepInsteadOfSpinningForever`. With the hook set, `get-universal-time`
  = `floor(Date.now()/1000) + 2208988800`, `get-internal-real-time` = `Date.now()`.

## The build names every refusal the load path can reach

`compiler/NoWasiLoadPathRefusals`. A refusal is a call-time condition, right for a call SITE;
from a TOP-LEVEL form nothing catches it and output is a sink, so the host sees a bare
`RuntimeError: unreachable` naming nobody. `WasmLispCompiler.compile` runs an AST reachability
pass under `--no-wasi` (right BEFORE `NoWasiFilesystemStubs.rewrite`), emitting ONE
`CompileWarnings.warn` line per primitive: operator, source position, the chain that reached
it, the way out where there is one. Where a hook exists (clock, entropy/`--host-random`,
`Kind.FETCH`) the line is a build-time HOST OBLIGATION, not a refusal.

Roots = every top-level form that is not a `defun`/`defmethod`/`defmacro`/`defgeneric`; edges =
OPERATOR position plus an immediately-applied `(lambda ...)`. Binding forms
(`let`/`let*`/`do`/`do*`/`flet`/`labels`/`macrolet`/`symbol-macrolet`, and slot lists) are
walked structurally so `(let ((app ...)))` forges no edge to `(defun app ...)`.
`rontolisp:async-defun` is a DEFERRED head, `async-lambda` a value like `lambda` — else every
fetch-capable handler is falsely on the load path. Five approximations:

1. A function VALUE is not an edge (`#'app` into `lack:builder`) — keeps EXPORT-only refusals
   quiet.
2. A `handler-case` protected form / `ignore-errors` body is not reported (the guard propagates
   through call edges; a definition later reached UNGUARDED is re-walked). `Kind.STDIN` reports
   even when guarded — every other refusal is catchable.
3. A `defstruct`/`defclass` slot initform counts as load-time — the one shape a name-based
   graph cannot reach (lack reaches `(get-universal-time)` in `cookie-state`'s `expires`
   default via `find-middleware`); the line names the definition.
4. A `with-open-file` BODY is not walked, mirroring the rewrite below, else clack's
   `(read in nil eof)` reports a stdin trap the module does not contain.
5. A call edge carries the SHAPES of the actual arguments (`compiler/ArgumentShapes`), bound to
   the callee's parameters; a `typecase`/`etypecase` clause (or `if`/`when`/`cond` test that is
   a `typep`) whose type a known shape cannot have is not walked. `ArgumentShapes.Shape` =
   FUNCTION (`#'f`, literal `lambda`), a literal's/quoted datum's type, `(make-instance
   'class)` as INSTANCE (disjoint from `pathname`), UNKNOWN (satisfies EVERY type, prunes
   nothing).

**A wrong prune is a missed refusal**, so a shape is read only where the site states it
syntactically: memoization keys on shapes as well as name and guard; a `flet`/`labels` body is
walked at its CALL SITES with shapes bound, plus once with nothing known if taken as `#'name`;
a name rebound outside the modelled scoping (`dolist` variable, `loop` `with`, `setq` target)
drops to UNKNOWN for the whole body; a top-level variable's shape (`(defvar *app*
(make-instance 'ningle:app))`, `(defparameter *app* (routes ...))` through a one-form `defun`'s
RETURN shape) is retracted the moment anything assigns it as a bare symbol or binds the name
anywhere.

- **Known routine false positive, filed not fixed**: `lack:builder` hands `clackup` the value
  of a `reduce` (UNKNOWN), so the `WITH-OPEN-FILE` line via `CLACK:CLACKUP -> CLACK:EVAL-FILE
  -> CLACK::%LOAD-FILE` returns for any program past one handler, while the same middleware as
  a plain call (`(wrap-json *app*)`) stays quiet. The shipped clack examples therefore hand
  `clackup` the application value or `(wrap-json #'app)`
  (`examples/cloudflare-workers/httpbin-clack/worker.lisp`, `examples/net/httpbin-clack.lisp`),
  and that example's README warns the line is a false alarm.
- **Re-evaluation trigger**: remaining false positives are shapes the lattice has no point for
  (a value out of a multi-branch function, a struct slot, a list element); the answer is
  another lattice point with the same "UNKNOWN prunes nothing" discipline.
- The same predicate takes the branch OUT of the module under `--optimize`
  (`compiler/DeadTypeBranchPruner`, `.kb/optimize-dead-code-elimination.md`).
- GC backend only. Output untouched — the pass only prints. A position is missing when
  `PackageResolver` rebuilt every cons of the form (`.kb/source-positions.md`); it falls back
  to the innermost SURVIVING located ancestor.
- Pinned by `NoWasiLoadPathRefusalsTest`, notably
  `aBranchTheArgumentRulesOutIsNotOnTheLoadPath`,
  `aTopLevelVariableStatesItsShapeTooAndARebindingRetractsIt`,
  `aNameTheBodyRebindsCannotCarryTheCallersShape`. All eight `examples/cloudflare-workers/*`
  builds print no load-path line.

## Filesystem: `with-open-file` / `open` lower to call-time error stubs

`compiler/NoWasiFilesystemStubs`. `WasmLispCompiler.compile` rewrites every reachable
`(with-open-file ...)` / `(open ...)` form — quoted data excluded, `open` left alone when the
program defines its own `(defun open ...)` — into `(progn <path-expr> (error "... requires
WASI; a --no-wasi module has no filesystem"))`. It SIGNALS, it does not fabricate a stream, and
does not make `path_open`'s errno redundant: the errno backstops what the rewrite cannot see
(`probe-file`, `directory`, `load`, an `open` reached through `funcall`).

**Ordering**: the rewrite runs FIRST, right after `flattenTopLevel`, so every downstream scan
(`usesRead`/`usesEval`, EH mode, the funcall-dispatch gate) reads the program actually compiled
— the `read`+`eval` inside clack's dead `%load-file` no longer drags the reader+eval runtimes
into every Worker module or holds the dispatch gate open
(`.kb/optimize-dead-code-elimination.md`). WASM `--no-wasi` only; JVM, interpreter and
WASI-carrying WASM targets keep real files.

## Host arena API on the GC backend

A memory-EXPORTING module (`memoryHelpers && !component`; `--component` uses the canonical
`cabi_realloc`/`cabi_post_*` instead) also exports `__ronto_alloc_mark () -> i32` /
`__ronto_alloc_reset (i32) -> ()`, the wasm-GC counterpart of the `--no-gc` pair
(`.kb/no-gc-scalar-wasm.md`). Host input buffers are untraced linear memory, so a resident host
allocating one per call grows memory forever.

- Bodies `WasmExportRuntimeBuilder.buildAllocMarkBody`/`buildAllocResetBody`, appended after
  `__ronto_alloc`/`_str_from_mem` (`helperFuncCount` 4: only COMPUTED wrapper/ABI bases shift,
  fixed `FUNC_*` are below `userFuncBase`), signatures at `abiTypeBase` (exclusive with the
  component string-ABI block).
- **The pop is guarded**: `HEAP_PTR_ADDR` is a stack pointer over the PERMANENT interned-symbol
  byte pool (`.kb/wasm-gc-strings.md`), so `__ronto_alloc_reset` does
  `HEAP_PTR = max(mark, RT_INTERN_HEAP_ADDR)`; cell 172 is the pool's high-water mark, written
  by `_intern` (`WasmReadRuntimeBuilder.buildInternBody(.., recordHighWater)`, host-arena
  modules only, so every other module's `_intern` is byte-identical).
- Stateless, so it nests, and a stale mark cannot corrupt anything. The `--no-gc` automatic
  scalar-return reset is deliberately NOT ported (cons/closures/hash/global `setq` exist here).
- `examples/count-vowels/` deliberately does NOT demo this backend: inside the non-GC subset
  the wasm-GC build is strictly worse (larger, and needs a GC-capable engine).

**Re-entry guard**: a module whose host import may SUSPEND (`wasm-import :async t`, or
`--host-fetch` with fetch used) can be re-entered while a call is parked (JSPI), and the arena
bracket cannot survive that — interleaved marks are not nested marks, and a `(ptr,len)` result
sits at un-advanced `HEAP_PTR` until the host reads it. Every export wrapper of such a module
sets a guard global on entry (a second entry TRAPS) and clears it on return; a module that
cannot suspend is byte-identical. `.kb/wasm-import.md` ("The re-entry guard").

## The boundary RESOLVES a future its target answers

An export declares a scalar/string, so a target answering a FUTURE — the settled
`TYPE_P1_FUTURE` a degenerate async body produces outside asyncMode (`.kb/async-await.md`), or
the one a `wasm-import ... :async t` wrapper builds — must be resolved before the unbox, **or
the wrapper casts the struct to the declared type and traps with `illegal cast` on the very
first call**.

- `WasmExportCompiler.emitBody` calls `_p1_future_await` right after the target call; the
  `--component` wrapper's `asyncTarget` poll/`_sched_loop` branch is the same courtesy; the
  reactor transport's `rontolisp::%future-force` (`http-reactor.lisp`) is the by-hand spelling.
- **DYNAMIC, not keyed on the target being an async-defun**: a plain defun handing back someone
  else's future has the same problem, and `_p1_future_await` passes a non-future through — one
  unconditional call, no `ref.test`. Costs bytes only on a module that can hold a future at all
  (`Ctx.p1Futures`: after the async lowering, `%async-run` in the program or an `:async t`
  import), nothing elsewhere, including a `:void` export whose value is dropped.
- **asyncMode (`--component`, non-reactor) makes the SAME dynamic decision**: the gate is
  `Ctx.asyncFuncBase >= 0`, so every asyncMode export polls, and a callback-lifted export takes
  the callback driver unconditionally (callback exports exist only under asyncMode, so the old
  non-async-target EXIT tail was unreachable and is gone). An export whose target IS an
  async-defun stays byte-identical, as does every component with no async surface.
- asyncMode async-defuns are excluded from `Ctx.inlinableDefuns` (`.kb/wasm-int-fusion.md`):
  the fusion inliner splicing a one-form async-defun's raw body into a synchronous caller made
  `futurep` answer NIL and masked the trap.
- Pinned by `WasmExportCompilerTest.anExportResolvesADegenerateFutureOnlyWhereOneCanExist`,
  `WasmLispCompilerIntegrationTest.preview1ExportResolvesTheFutureItsTargetAnswers`,
  `anExportHandsBackAnAsyncImportsFutureWithoutForcingItByHand`,
  `componentExportResolvesTheFutureItsTargetAnswers`,
  `componentAsyncDefunKeepsItsFutureThroughASyncCaller`, and the ci-spec case
  `async-future-survives-a-synchronous-caller`.

## Tests

`WasmExportCompilerTest` (structural, no Docker); `WasmLispCompilerIntegrationTest`
(`wasmtime --invoke`); `WasmImportCompilerTest`; `NoWasiLoadPathRefusalsTest`. Limitations:
README "Exporting Lisp functions". Component typed exports: `.kb/wasi-component.md`.
Unfinished: memory-ABI CI.
