# `rontolisp:wasm-export` (host-callable Lisp functions) + `--no-wasi` reactor mode

> JVM twin: [jvm-export.md](jvm-export.md) — `rontolisp:jvm-export` + `--no-main`; shares
> `compiler/BoundaryType` and the exact-or-trap rule.

`(rontolisp:wasm-export 'name :as "alias" :params '(T...) :returns T)` exports a top-level `defun` as
a host-callable WASM function. `LispNames.WASM_EXPORT`, `rontolisp` package, not CL standard. `:as` =
`Decl.exportName()` (default the Lisp name). No-op on interpreter/JVM. Under `--component` an export
is core-exported then lifted -- sync canonLift by default (WAVE-invokable), scalars option-free,
`:string`/`:s-expr` via the canonical string ABI, an I/O-bearing body needing `:async t` or it traps;
ignored outside `--component`, rejected under `--no-gc --component` (`.kb/wasi-component.md`).

## Boundary types

`compiler/BoundaryType`, WIT spelling (`:s8`..`:u64`; `:int`/`:long` = permanent parse-time aliases
of `:s32`/`:s64`). Table + exact-or-trap range rule: `.kb/wit.md` ("The integer boundary") -- export
boundary and WIT world are one contract.

- <=32-bit integer <-> i32 via `_int_new` on GC (i31 if it fits, else boxed exact,
  `.kb/wasm-bignum.md`); `:s64`/`:u64` <-> i64 on every backend, u64 >= 2^63 traps.
- `:float` <-> `TYPE_FLOAT` f64; `:bool` <-> nil/t i32; `:string`/`:s-expr` <-> `(ptr,len)` in linear
  memory; omitted/nil/`:void` returns = void.
- `:bytes` (both directives, GC core modules only): `(unsigned-byte 8)` vector, no UTF-8,
  caller-passes-the-buffer -- trailing `(ptr,cap)`, answers the FULL length as one i32
  (`.kb/wasm-import.md`).
- **`:float` is f64 deliberately**; there is no internal f32 (`WasmLispCompiler.TYPE_FLOAT =
  struct{f64}`). Renaming it is a silent ABI break -- an f32 boundary must be additive
  (`:single`/`:f32`).
- IMPORTS are deliberately narrower: `WasmImportCompiler.KNOWN_PARAM_TYPES` =
  `{:s32, :float, :bool, :string, :s-expr, :bytes}`. Widening is its own change (`.kb/wit.md`).

## Emission

- `WasmExportCompiler` parses + emits wrappers; helpers `WasmExportRuntimeBuilder`; wiring
  `WasmLispCompiler` (Pass 1 collects `Decl`s from `topLevelExprs`, validates name-exists/arity,
  builds `ExportPlan`s).
- Memory types add `__ronto_alloc` (bump allocator) + `_string_from_mem` after lambdas at
  `FUNC_USER_BASE + numDefuns + numLambdas`, so `FUNC_*` stay fixed; a data segment seeds
  `HEAP_PTR_ADDR` so host calls work without `_start`.
- `--no-gc`: the internal integer IS i64, so `:s64` pins param/return to i64 (no
  `i64.extend_i32_s`/`i32.wrap_i64`) -- an explicit `S64` branch in
  `NoGcWasmCompiler.compileWrapperBody`, `boundaryTy` falling through to INT, `requireSupported`
  rejecting only `:s-expr`.
- **Pass-through wrapper elision** (`NoGcWasmCompiler.isPassThroughExport`): every param and the
  return crossing identically (`:s64` over inferred i64, `:float` over inferred f64 -- the only two
  needing neither range guard nor narrowing) AND `Mem.used()` false => the export names the internal
  function directly. A `:long` return over an inferred f64 body still gets a truncating wrapper.

## `--no-wasi` (reactor mode)

CLI `--no-wasi`; `WasmLispCompiler(dynamic, component, noWasi)`. No `wasi_snapshot_preview1` imports,
so a host instantiates with no import object. Init entry exported as `_initialize` (reactor ABI)
instead of `_start`, keyed off `noWasi`; a tree-shaker root either way. **Index stability**: the nine
WASI import slots (indices 0-8, `IMPORT_FUNC_COUNT`) are filled with internal stubs carrying the SAME
type indices, so every `FUNC_*` constant stays valid.

**THE rule** (the invariant; the slot list is its consequence): a stub may answer when the answer is
*true of this module*; it may not answer when answering invents a value the program cannot tell from
a real one. A value the HOST hands over is not an invention.

| slot | behaviour |
| --- | --- |
| `fd_write` | SINK: `*nwritten = iovs[0].len`, errno 0 |
| `environ_sizes_get` / `environ_get` | EMPTY environment: 0 vars, 0 bytes, errno 0 |
| `path_open` | errno 44 `ENOENT` |
| `fd_close` / `fd_readdir` | errno 8 `EBADF` |
| `random_get` | SplitMix64 over `RANDOM_STATE_ADDR`; a host import under `--host-random` |
| `fd_read` | `unreachable` — answering would invent input |
| `clock_time_get` | `unreachable`, BACKSTOP only — clock built-ins read `HOST_TIME_ADDR` |

The two `unreachable` bodies are stack-polymorphic, so one shape fits every signature. `random` no
longer CALLS its slot (inlined at the draw site, `.kb/random.md`); only `--host-random`'s seeding
call reaches it. Bodies in `WasmIoRuntimeBuilder` (`buildNoWasiFdWriteSinkBody`,
`buildNoWasiEnvironSizesGetBody`, `buildNoWasiRandomGetBody`, `buildNoWasiErrnoBody`); the emit loop
is the `if (this.noWasi)` block in `WasmLispCompiler`'s code section. **Trap**: emitters pass ONE
iovec and drop the errno, so a caller looping on `*nwritten` needs a wider stub. The sink is what
lets `clack:clackup` load on a reactor (`.kb/clack.md`). Pinned WHOLE, all nine bodies byte for byte,
by `WasmExportCompilerTest.noWasiStubsAnswerWhereverThereIsATrueAnswerAndTrapWhereThereIsNot`; also
`noWasiModule*` in `WasmLispCompilerIntegrationTest` and the `--no-wasi` leg of
`examples/cloudflare-workers/`.

## `--component --no-wasi` = a reactor component that imports nothing

The core module keeps the Preview 1 no-WASI contract (stubs, fd_write sink,
`NoWasiFilesystemStubs`, `DATA_BASE_OFFSET` 256) but declares/exports its OWN memory, keeps
`TYPE_START` at `() -> ()`, exports NO `run`/`_initialize`, and carries a core start section
(`WasmWriter.writeStartSection(FUNC_START)`, between export and code; the shaker roots and renumbers
it). **Top-level forms run at instantiation** -- the one component shape where an `--invoke`d export
sees `defparameter` globals assigned.

- `WasmComponentBuilder.buildReactor`: no import block/adapter/mem module at either `--optimize`
  level; core module 0 -> core instance 0 -> per-export alias/type/lift/export via
  `appendFuncExports`, every cursor 0; string boundaries lift over the core's own memory + its
  `cabi_realloc`/`cabi_post_*`. `--emit-wit` = `WitEmitter.VARIANT_REACTOR` (`nogc` empty world).
- `Ctx.component` = `component && !noWasi`; `Ctx.noWasi` feeds reject messages;
  `Ctx.reactorComponent` = the raw shape `Ctx.component` deliberately reports as false; `asyncMode`
  off. The CLI gates the five wasi:*-binding library splices (http/wait/sockets/stdin/environment) as
  ONE decision (`spliceBackend` = `WASM_GC` for a reactor) and the compiler enforces it
  (`componentImports` non-empty + reactor = an error naming the interface); serve + `--no-wasi` = a
  ctor hard error. `reactor = noWasi || noGc` in the CLI, so `#+rontolisp-reactor` selects the
  reactor branch under the component too.
- **Trap**: a trap in a top-level form kills INSTANTIATION; no `_initialize` exists to wrap in
  try/catch, so what a top-level form may hit is decided by the stub policy.
- Plain `--component` and plain `--no-wasi` outputs stay byte-unchanged. Pinned by
  `componentNoWasiIsAReactorThatImportsNothing`, `componentNoWasiReactorRecordsTheEmptyWorldWit`
  (both optimize levels), the `componentNoWasiReactor*` invoke tests; jco `--instantiation sync -b 0
  --bindgen-enable-wasm-exnref` output instantiates with `{}` on plain node.

## Randomness, the clock, and the two host opt-ins

SplitMix64 (golden-ratio gamma + two xor-shift-multiply rounds) over the 8-byte cell at
`RANDOM_STATE_ADDR`; its zero start state is a valid seed, so no data segment seeds it. EVERY wasm
build draws from it (`.kb/random.md`); a build with a host seeds from eight `random_get` bytes on its
first draw. `--no-wasi` alone keeps the fixed start state -- unseeded, every instance walks the same
sequence, which is inside the CL contract. The entropy API is deliberately NOT served from it:
`rontolisp::%random-byte` (behind `rontolisp:random-bytes`) lowers to a call-time error under
`--no-wasi`.

- **`__ronto_seed_random (i64) -> ()`**: a `--no-wasi` CORE module exports a one-instruction hook
  overwriting `RANDOM_STATE_ADDR`; call it before `_initialize`, early enough for a library's
  LOAD-TIME `(random ...)`. An EXPORT, since a core wasm import is not optional and would break
  instantiation with `{}`. Emitted when `noWasi && !component`, after the memory helpers
  (`memoryHelperCount`: only COMPUTED wrapper/ABI bases shift), `(i64)->()` type at `abiTypeBase`.
  NOT on the reactor COMPONENT (top level runs at instantiation, so there is no window before the
  load-time draws). **Seeding does NOT re-enable `random-bytes`** -- SplitMix64 is invertible from
  one output.
- **`__ronto_set_time (i64) -> ()`**: the twin, writing nanoseconds since the Unix epoch (preview1's
  `clock_time_get` unit) into `HOST_TIME_ADDR` (cell 224, 8 bytes, under the `DATA_BASE_OFFSET=256`
  headroom, no data segment -- zero-initialized memory IS the unset state).
  `get-universal-time`/`get-internal-real-time`/`get-internal-run-time` read THAT cell
  (`WasmTimeCompiler.compileFromHostCell`). **While the cell is zero the three built-ins SIGNAL** --
  a catchable condition naming the operator and the hook, under an `i64.eqz` guard; zero is 1970, an
  answer a program could not tell from a real one. The clock does not advance between host calls,
  matching a Worker's request-frozen clock; every `examples/cloudflare-workers/*/src/index.js` calls
  the setter before `_initialize` and once per request. Emitted independently of the seed hook
  (`--host-random` retires that one and leaves this one), sharing its type; NOT on the reactor
  component (different refusal message via `Ctx.reactorComponent`). **`sleep` refuses on `--no-wasi`**
  -- Preview 1 elapses an interval by SPINNING on a clock that cannot advance during a call.
  Re-evaluation trigger: a clock that advances DURING a call would be a `--host-clock` flag
  forwarding `clock_time_get` to an `env.clock_time_get` import, at the same cost to zero imports.
- **`--host-random`** (first opt-in out of the zero-import contract): the module-local generator is
  SEEDED from the host on its first draw and `rontolisp:random-bytes` takes the host's bytes one at a
  time -- the property the flag exists for. Slot `FUNC_RANDOM_GET` becomes a forwarding body
  (`WasmIoRuntimeBuilder.buildNoWasiHostRandomGetBody`) over ONE injected import
  `env.random_get(buf, len) -> errno` (module `env`, preview1's signature, so no WASI function is
  imported); the other eight stubs are untouched. Appended LAST in `hostImports`, reusing the fixed
  `TYPE_INTERN` `(i32,i32)->i32` rather than appending a type, so program import ordinals/bytes and
  `abiTypeBase` are untouched. `seedRandom = noWasi && !component && !hostRandom`;
  `RANDOM_BYTE_INTERNAL`'s gate becomes `ctx.noWasi && !ctx.hostRandom`. Rejected without
  `--no-wasi`, with `--component`, and (in the CLI) with `--no-gc`.
- **`--host-fetch`** (second opt-in): splices a LISP lowering (`eval/HostFetchLibrary`) whose
  `rontolisp:wasm-import`s ride the ordinary synthetic-defun machinery, appended after the program so
  program import ordinals are unchanged -- `env.fetch` (`:string`->`:string`) and
  `env.readResponseBody(ptr, cap) -> i32` (`:bytes`, `:async t`), the reply BODY out of band. Same
  guards as `--host-random`; the BUILD prints the standing host obligation (a synchronous `env.fetch`
  is always valid; a `WebAssembly.Suspending` one requires `promising`-entered exports and serialised
  calls). A build that never fetches gets no splice and no import. `.kb/fetch-http.md`.
- Pins: `noWasiCoreModuleExportsTheHostSeedHook`, `noWasiCoreModuleExportsTheHostClockHook`,
  `noWasiModuleSignalsForTheClockAndForRealEntropy`,
  `noWasiModuleRefusesToSleepInsteadOfSpinningForever`,
  `hostRandomForwardsTheRandomGetSlotAndRetiresTheSeedHook`,
  `hostRandomJoinsTheOrdinalSpaceLastAndIsTheOnlyImportOnItsOwn`,
  `underHostRandomTheEntropyApiReachesTheHostAndAnUnusedImportIsStillShaken`
  (`WasmImportCompilerTest`). With the hook set, `get-universal-time` =
  `floor(Date.now()/1000) + 2208988800`, `get-internal-real-time` = `Date.now()`.

## The build names every refusal the load path can reach

`compiler/NoWasiLoadPathRefusals`. A refusal is a call-time condition, right for a call SITE; from a
TOP-LEVEL form nothing catches it and output is a sink, so the host sees a bare
`RuntimeError: unreachable` naming nobody. `WasmLispCompiler.compile` runs an AST reachability pass
under `--no-wasi` (right BEFORE `NoWasiFilesystemStubs.rewrite`), emitting ONE `CompileWarnings.warn`
line per primitive: operator, source position, the chain that reached it, the way out where there is
one. Where a hook exists (clock, entropy/`--host-random`, `Kind.FETCH`) the line is a build-time HOST
OBLIGATION, not a refusal. GC backend only; output untouched, the pass only prints.

Roots = every top-level form that is not a `defun`/`defmethod`/`defmacro`/`defgeneric`; edges =
OPERATOR position plus an immediately-applied `(lambda ...)`. Binding forms
(`let`/`let*`/`do`/`do*`/`flet`/`labels`/`macrolet`/`symbol-macrolet`, and slot lists) are walked
structurally so `(let ((app ...)))` forges no edge to `(defun app ...)`. `rontolisp:async-defun` is a
DEFERRED head, `async-lambda` a value like `lambda` -- else every fetch-capable handler is falsely on
the load path. Five approximations:

1. A function VALUE is not an edge (`#'app` into `lack:builder`) -- keeps EXPORT-only refusals quiet.
2. A `handler-case` protected form / `ignore-errors` body is not reported (the guard propagates
   through call edges; a definition later reached UNGUARDED is re-walked). `Kind.STDIN` reports even
   when guarded -- every other refusal is catchable.
3. A `defstruct`/`defclass` slot initform counts as load-time -- the one shape a name-based graph
   cannot reach (lack reaches `(get-universal-time)` in `cookie-state`'s `expires` default); the line
   names the definition.
4. A `with-open-file` BODY is not walked, mirroring the rewrite below, else clack's
   `(read in nil eof)` reports a stdin trap the module does not contain.
5. A call edge carries the SHAPES of the actual arguments (`compiler/ArgumentShapes`), bound to the
   callee's parameters; a `typecase`/`etypecase` clause (or `if`/`when`/`cond` test that is a
   `typep`) whose type a known shape cannot have is not walked. `ArgumentShapes.Shape` = FUNCTION
   (`#'f`, literal `lambda`), a literal's/quoted datum's type, `(make-instance 'class)` as INSTANCE
   (disjoint from `pathname`), UNKNOWN (satisfies EVERY type, prunes nothing).

**A wrong prune is a missed refusal**, so a shape is read only where the site states it
syntactically: memoization keys on shapes as well as name and guard; a `flet`/`labels` body is walked
at its CALL SITES with shapes bound, plus once with nothing known if taken as `#'name`; a name
rebound outside the modelled scoping (`dolist` variable, `loop` `with`, `setq` target) drops to
UNKNOWN for the whole body; a top-level variable's shape is retracted the moment anything assigns it
as a bare symbol or binds the name anywhere.

- **Known routine false positive, filed not fixed**: `lack:builder` hands `clackup` the value of a
  `reduce` (UNKNOWN), so the `WITH-OPEN-FILE` line via `CLACK:CLACKUP -> CLACK:EVAL-FILE ->
  CLACK::%LOAD-FILE` returns for any program past one handler, while the same middleware as a plain
  call stays quiet. The shipped clack examples therefore hand `clackup` the application value or
  `(wrap-json #'app)`, and that example's README warns the line is a false alarm. Remaining false
  positives are shapes the lattice has no point for; the answer is another lattice point with the
  same "UNKNOWN prunes nothing" discipline.
- The same predicate takes the branch OUT of the module under `--optimize`
  (`compiler/DeadTypeBranchPruner`, `.kb/optimize-dead-code-elimination.md`). A position is missing
  when `PackageResolver` rebuilt every cons of the form (`.kb/source-positions.md`); it falls back to
  the innermost SURVIVING located ancestor.
- Pinned by `NoWasiLoadPathRefusalsTest`, notably `aBranchTheArgumentRulesOutIsNotOnTheLoadPath`,
  `aTopLevelVariableStatesItsShapeTooAndARebindingRetractsIt`,
  `aNameTheBodyRebindsCannotCarryTheCallersShape`. All eight `examples/cloudflare-workers/*` builds
  print no load-path line.

## Filesystem: `with-open-file` / `open` lower to call-time error stubs

`compiler/NoWasiFilesystemStubs`. `WasmLispCompiler.compile` rewrites every reachable
`(with-open-file ...)` / `(open ...)` form -- quoted data excluded, `open` left alone when the
program defines its own `(defun open ...)` -- into `(progn <path-expr> (error "... requires WASI; a
--no-wasi module has no filesystem"))`. It SIGNALS, it does not fabricate a stream, and does not make
`path_open`'s errno redundant: the errno backstops what the rewrite cannot see (`probe-file`,
`directory`, `load`, an `open` reached through `funcall`).

**Ordering**: the rewrite runs FIRST, right after `flattenTopLevel`, so every downstream scan
(`usesRead`/`usesEval`, EH mode, the funcall-dispatch gate) reads the program actually compiled --
the `read`+`eval` inside clack's dead `%load-file` no longer drags the reader+eval runtimes into
every Worker module (`.kb/optimize-dead-code-elimination.md`). WASM `--no-wasi` only.

## Host arena API on the GC backend

A memory-EXPORTING module (`memoryHelpers && !component`; `--component` uses the canonical
`cabi_realloc`/`cabi_post_*` instead) also exports `__ronto_alloc_mark () -> i32` /
`__ronto_alloc_reset (i32) -> ()`, the wasm-GC counterpart of the `--no-gc` pair
(`.kb/no-gc-scalar-wasm.md`). Host input buffers are untraced linear memory, so a resident host
allocating one per call grows memory forever.

- Bodies `WasmExportRuntimeBuilder.buildAllocMarkBody`/`buildAllocResetBody`, appended after
  `__ronto_alloc`/`_str_from_mem` (`helperFuncCount` 4: only COMPUTED wrapper/ABI bases shift),
  signatures at `abiTypeBase` (exclusive with the component string-ABI block).
- **The pop is guarded**: `HEAP_PTR_ADDR` is a stack pointer over the PERMANENT interned-symbol byte
  pool (`.kb/wasm-gc-strings.md`), so `__ronto_alloc_reset` does
  `HEAP_PTR = max(mark, RT_INTERN_HEAP_ADDR)`; cell 172 is the pool's high-water mark, written by
  `_intern` in host-arena modules only, so every other module's `_intern` is byte-identical.
- Stateless, so it nests, and a stale mark cannot corrupt anything. The `--no-gc` automatic
  scalar-return reset is deliberately NOT ported (cons/closures/hash/global `setq` exist here).
- **Re-entry guard**: a module whose host import may SUSPEND (`wasm-import :async t`, or
  `--host-fetch` with fetch used) can be re-entered while a call is parked (JSPI), and the arena
  bracket cannot survive that -- interleaved marks are not nested marks, and a `(ptr,len)` result
  sits at un-advanced `HEAP_PTR` until the host reads it. Every export wrapper of such a module sets
  a guard global on entry (a second entry TRAPS) and clears it on return; a module that cannot
  suspend is byte-identical. `.kb/wasm-import.md`.

## The boundary RESOLVES a future its target answers

An export declares a scalar/string, so a target answering a FUTURE -- the settled `TYPE_P1_FUTURE` a
degenerate async body produces outside asyncMode (`.kb/async-await.md`), or the one a
`wasm-import ... :async t` wrapper builds -- must be resolved before the unbox, **or the wrapper
casts the struct to the declared type and traps with `illegal cast` on the very first call**.

- `WasmExportCompiler.emitBody` calls `_p1_future_await` right after the target call; the
  `--component` wrapper's `asyncTarget` poll/`_sched_loop` branch is the same courtesy; the reactor
  transport's `rontolisp::%future-force` (`http-reactor.lisp`) is the by-hand spelling.
- **DYNAMIC, not keyed on the target being an async-defun**: a plain defun handing back someone
  else's future has the same problem, and `_p1_future_await` passes a non-future through -- one
  unconditional call, no `ref.test`. Costs bytes only on a module that can hold a future at all
  (`Ctx.p1Futures`), nothing elsewhere, including a `:void` export whose value is dropped.
- **asyncMode (`--component`, non-reactor) makes the SAME dynamic decision**: the gate is
  `Ctx.asyncFuncBase >= 0`, so every asyncMode export polls and a callback-lifted export takes the
  callback driver unconditionally. An export whose target IS an async-defun stays byte-identical.
- asyncMode async-defuns are excluded from `Ctx.inlinableDefuns` (`.kb/wasm-int-fusion.md`): the
  fusion inliner splicing a one-form async-defun's raw body into a synchronous caller made `futurep`
  answer NIL and masked the trap.
- Pinned by `WasmExportCompilerTest.anExportResolvesADegenerateFutureOnlyWhereOneCanExist`,
  `WasmLispCompilerIntegrationTest.preview1ExportResolvesTheFutureItsTargetAnswers`,
  `anExportHandsBackAnAsyncImportsFutureWithoutForcingItByHand`,
  `componentExportResolvesTheFutureItsTargetAnswers`,
  `componentAsyncDefunKeepsItsFutureThroughASyncCaller`, ci-spec
  `async-future-survives-a-synchronous-caller`.

## Tests

`WasmExportCompilerTest` (structural, no Docker); `WasmLispCompilerIntegrationTest`
(`wasmtime --invoke`); `WasmImportCompilerTest`; `NoWasiLoadPathRefusalsTest`. Limitations: README
"Exporting Lisp functions". Component typed exports: `.kb/wasi-component.md`. Unfinished: memory-ABI
CI.
