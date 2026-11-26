# `rontolisp:wasm-import` (host functions callable from Lisp) + export `:as` aliases

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(T...) :returns T)` declares a
host function (JS import object `module.field`; wasmtime `--preload module=...`) callable like a
top-level defun. Type designators are shared with `WasmExportCompiler`; the ACCEPTED SUBSET is
per-backend ("Modes"). Parsing: `compiler/WasmImportDirective` (shared with the JVM); codegen:
`WasmImportCompiler` on wasm-GC, `NoGcWasmCompiler` under `--no-gc`.

## How the fixed-index invariant survives adding imports
Imported functions precede defined ones, so a new import would shift every `FUNC_*` constant.
Instead each import becomes a **synthetic defun** whose wrapper unboxes, emits
`call (PLACEHOLDER_FUNC_BASE = 1<<27) + ordinal`, and boxes the result. Being a defun,
`#'name`/`funcall`/`mapcar`/dispatch/`eval` work with no wiring. Host-ABI func types are appended
after the export wrapper types.

`am.ik.wasm.WasmImportInjector` then prepends the import entries (indices 0..K-1), remaps every
`call`/`ref.func` (`>= placeholderBase -> ordinal`, else `+K`) and shifts export/start indices,
before `WasmTreeShaker.shake`. **The pre-injection module is invalid (calls to 2^27) -- never
validate or emit it directly.**

A RUNTIME BUILDER may bind one through the same encoding (`--host-random`'s entropy,
`--host-output`'s `fd_write`, `WasmArgvRuntimeBuilder`'s `args_sizes_get`/`args_get` [[uiop]],
`file-position`'s `fd_seek` / `file_position_get`/`_set` [[read-load-streams]], the
`path_filestat_get` / `path_remove_directory` / `path_readlink` / `path_filestat_set_times` /
`path_symlink` family,
`fd_fdstat_get` behind `%terminal-p`, `poll_oneoff` behind `sleep`
[[time-environment-builtins]]). These are appended to `hostImports` LAST, after directive slots,
so a program's own `rontolisp:wasm-import` keeps its ordinals and bytes.

**This, not a new index-pinned preview1 slot, is how a WASI function the backend did not import
before gets added.** A new fixed slot shifts `IMPORT_FUNC_COUNT`, `FUNC_START`, every function
index, both `--component` adapter blobs and the `--no-wasi` stub block; appending shifts nothing,
and every module not reaching the feature is BYTE-IDENTICAL (measured for `fd_seek`). An appended
import needs a type entry only when no fixed type has its shape (`fd_seek`'s
`(i32, i64, i32, i32) -> i32` did: emitted after `importSlots`, counted into `abiTypeBase`).

## Memory-typed PARAMETERS stage on a stack; the RESULT scratch does not
N `:string`/`:s-expr` parameters must hold N regions AT ONCE across the call.
`WasmExportCompiler.emitStringResult` writes at the un-advanced `HEAP_PTR` scratch DELIBERATELY
(right for a single RESULT read at once, [[wasm-gc-strings]]); reused per PARAMETER it aliased all
onto ONE base -- the host saw the last argument's bytes under every pointer, silently.
`wasmtime --invoke` cannot tell two pointers apart; only a generated host found it.

`WasmImportCompiler.emitStagedMemoryParam`:
- **Serialised**: bytes go to `HEAP_PTR`, which is ADVANCED (8-aligned) -- a stack; ONE mark up
  front, one pop after the call, shared with `:bytes` (`staging = bytesStaging || memStaging`).
- **`--reentrant`**: an absolute pop cannot serve interleaved extents, so each region is a park
  block (`_park_str_result`) freed after the call -- the only staging that survives a park. Two
  such parameters pull the park allocator on by themselves.
- **One memory-typed parameter keeps the non-advancing scratch** (`stagesMemoryParams` gates on
  `>= 2`), so every already-correct module stays BYTE-IDENTICAL.
- **The host must read its memory-typed arguments before it answers**: the run is released on
  return.
- **The contract is READ-ONLY too, though nothing enforces it.** Here a host write lands on dead
  scratch; on `--no-gc` (`.kb/no-gc-scalar-wasm.md`, "Host imports") the same argument is durable
  deduplicated module memory and a write corrupts every use of that literal. Both written down in
  `doc/*/guides/wasm-host-boundary.md`.

### A literal `:string` argument does not round-trip
A literal's bytes START in linear memory, and the boundary wants them there; the general path
walks them into a GC array (`_str_build`) and back out (`_str_to_mem`) -- a round trip no
optimizer sees through. `WasmImportCompiler.compileLiteralImportCall` (from
`WasmFunctionCallCompiler.compileDirectCall`, ahead of the generic path) lowers such a site to one
`memory.copy` and the host call, no wrapper. Also covers `:u32` arguments beside it and a literal at
an `:octets` parameter (its UTF-8; a Proxy-Wasm `(add-header :response "x" "y")` 305 -> 12
instructions). A `(format nil ...)` at a text parameter drops the mutable-result wrap
(`WasmImportCompiler.withTextArguments`): a host sees bytes, so identity is unobservable.

- **It COPIES; it never hands over the data segment's pointer**: identical spellings (and interned
  symbol names) share one block, so a host write would corrupt every use. What it saves is the two
  byte loops and the GC array, not the copy.
- **Destination: a RESERVED block above the static data**, sized by the widest site
  (`Ctx.litStageBytes`). Being inside the minimum memory it needs no `emitGrowHeapTo` guard, keeping
  `_lit_stage` at 24 bytes; constant lengths replace the mark/restore bracket. Same contract as the
  scratch: read before answering; the next lowered call overwrites it.
- **Which sites**: every `:string` argument a literal, parameters in `{:string,:s32,:float,:bool}`,
  result in `{:void,:s32,:float,:bool}`, not `--reentrant`. Memory RESULTS need slots a call site
  lacks. **Arguments AFTER the first staged region are evaluated into temps first**: the regions
  are live, and an allocation in between would land on them.
- **The wrapper still exists** for first-class uses and runtime arguments; the shaker drops it (and
  `_str_build`/`_str_to_mem`) when unused.
- `_lit_stage` sits after `_arity_chk`, `_arity_message`, `_undefined_function`
  (`litStageFuncBase()`), shifting only `userFuncBase()`. Its presence is decided from the
  DIRECTIVES before pass 2 (`canLowerLiteralCallSite`), loose in the safe direction (at worst one
  unreferenced function); the site's import ordinal is checked against the core import slot list.
- Measured: the reactor module shrinks by a fifth; `-Oz` over the old module does not reach it
  ([[optimize-dead-code-elimination]], "What an external optimizer still finds"). Modules with no
  `:string` import are byte-identical. Pins:
  `WasmImportCompilerTest.aLiteralStringArgumentCrossesWithoutTheGcRoundTrip` (vs the same module
  with a runtime-built argument) and `.theLiteralLoweringDeclinesTheShapesItCannotMarshal`; content
  `WasmStringParamBoundaryE2eTest`.
- **`--no-gc` has the same idea and shares no code** ([[no-gc-scalar-wasm]], "A literal `:string`
  argument is two constants"): there pointer and length are constants, no copy, and it removes the
  WRAPPER when every reached site qualifies. `canLowerLiteralCallSite` is not shared: vocabularies
  differ (`:s32` here, the whole fixed-width family there).

Pins: `WasmImportCompilerTest.twoMemoryTypedParamsStageOnDistinctRegions` (runtime-built arguments,
since literals skip the wrapper),
`WasmReentrantCompilerTest.parkHelpersRideOnlyAReentrantModuleWithAMemoryBoundary`,
`WasmStringParamBoundaryE2eTest` (every combination, Node host, flat-memory loop).

## Modes, other backends, aliases
- `--component` on wasm-GC throws `UnsupportedOperationException` (GC components bind through
  `wit-import`'s `%component-import`); `--no-gc --component` TAKES it as instance imports
  (`.kb/no-gc-scalar-wasm.md`, "Host imports"), hence `:param-names` (default `p0..`, read by that
  wrap alone). Interpreter and JVM define error-signalling stubs so shared sources load everywhere.
- **`--no-gc` takes the directive** with its own wrappers; shared: `WasmImportDirective`,
  `WasmImportCompiler.parse`/`hostParamTypes`/`hostResultTypes`, `WasmImportInjector`. **The accepted
  TYPE SET is per-backend and follows the house integer** (so `parse` takes it as an argument):
  `KNOWN_PARAM_TYPES` here (`:s32`, `:s64`, `:u32`, `:float`, `:bool`, `:string`, `:s-expr`,
  `:bytes`, `:octets`, `:extern`; house integer `i31ref`, `:s64`/`:u32` via `_int_val`/`_int_new`),
  `SCALAR_PARAM_TYPES` there, DERIVED from `BoundaryType.witName() != null`.
- A `:string` result forces `memoryHelpers` (`__ronto_alloc`/`_str_from_mem`); an `:s-expr` result
  forces `usesRead` and the allocator too (`WasmImportCompilerTest.sexprResultExportsTheAllocatorToo`).
- **Export aliases**: `wasm-export :as "alias"`; `Decl.exportName()` defaults to the Lisp name.

## `:extern` -- a host reference the module holds
Import-only (`BoundaryType.EXTERN`; every export parser and `--component` refuse it): the host's
`externref` boxed in a one-field struct at `WasmLispCompiler.hostRefTypeIndex()`, so
`fixedTypeCount()` grows only in a module naming it. A parameter unboxes with `ref.cast` (nil
traps). Purpose: wasmtime drops an `externref`'s host data when the collector finds it dead -- the
only finalizer a wasm-GC module can have (`p_new_handle`, [objc.md](objc.md), "--native").

## Directives inside a user `defpackage`
The quoted NAME argument of both directives IS package-resolved
(`PackageResolver.resolveWasmDirective`), matching what call sites canonicalize to. The default
host field is the bare MEMBER name (`WasmExportCompiler.unqualifiedMember`). **Caveat**: a program
taking functions as values keeps same-arity import wrappers reachable via the funcall dispatcher,
so `--optimize` cannot shake them.

## It is also a LOWERING TARGET ([[wit]])
On Preview 1 a `rontolisp:wit-import` expands to one directive per WIT function, byte-identical to
the hand-written block. `:from` defaults to the interface name; the `:as` field is camelCased
(`:field-style :camel`) or verbatim (`:kebab`). On wasm-GC only the flat set is reachable from WIT
(beyond it: "A WIT interface beyond the flat set"); under `--no-gc` the fixed-width integer family
too. **One directive binds one interface into one module.** `webgl-common/gl-imports.js` is
GENERATED from gl.wit (`GlImportObjectTest` reuses `WitImportDirective.FieldStyle.CAMEL`).

## Asynchronous hosts: JSPI
A `WebAssembly.Suspending` import entered through `WebAssembly.promising` parks the wasm stack.
workerd has it unflagged; so has node 24.22.3 (measured 2026-11-25; the tests still pass
`--experimental-wasm-jspi`, which node 22 accepts without having `WebAssembly.Suspending`). **Two obligations**: (1) a
suspending import may only be called on a stack entered through `promising`, so the `_initialize`
load path must never reach one; (2) suspending RE-ENTERS the module, sharing globals and the
LIFO `__ronto_alloc_mark`/`_reset` bracket. `--host-fetch` discharges (1) for fetch ([[fetch-http]]).

### `:async t` -- a suspending host import is a future
The call returns a FUTURE. DELIBERATELY DEGENERATE here (JSPI blocks the stack): a settled kind-2
`TYPE_P1_FUTURE` (`WasmImportCompiler.buildWrapperBody`). started == settled is the CONTRACT; the
option buys one source reading the same everywhere, not concurrency. Literal `t`/`nil` only. A
`wit-import`ed `async func` lowers to this on Preview 1. **In a module with the state machines**
(`ctx.futureTypeIndex >= 0`, e.g. a Java program's threads) the wrapper builds a FULFILLED
`TYPE_FUTURE` instead (`emitSettledFuture`): `_future_poll` resolves that type alone, and a
degenerate future passed through `%future-force` unresolved (`.kb/java-frontend.md`, "A server as a
reactor").

`compiler/SuspendingImports` (over `NoWasiLoadPathRefusals.walk`) gives the build: an obligation
warning per `module.field`; which exports can reach a suspending import (an import taken as
`#'value` widens to ANY export); under `--no-wasi` only, an ERROR when the LOAD PATH reaches one.
Pins: `SuspendingImportsTest`, `WasmImportCompilerTest.parsesAsyncOption`,
`WitImportDirectiveTest.lowersAnAsyncFuncMemberWithAsyncTOnPreview1`,
`asyncImportAnswersASettledFutureThatAwaitResolves`, and one module driven both ways
(`WasmReactorBodyE2eTest` sync vs `WasmReactorStreamingHostE2eTest` suspending).

**`reaches` follows CALLS**, but the reactor hands body bridges over as `#'name`
(`HttpReactorInliner`). The `followValues` flag is true ONLY for the reachability question (a MAY
analysis: under-reporting means a missing `promising`); the load-path report follows what RUNS.
Under `followValues` a handler does not hide a site: a `handler-case` catches conditions, not a
suspension (a Java client's `send` wraps its fetch in one; guarded, no Java export reached it).
**Every function value widens**: `anyTakenAsValue` is ONE walk (`walkValues`) seeded with every
`#'name` and every literal `lambda`/`async-lambda` of the program, so a lambda made on the load
path and `funcall`ed by an export (each Java handler: `main` registers it) makes every export
promising. Load-path values alone would miss a value one export stores and another calls.

## `--emit-js-glue` -- the host half is written, not described
`compiler/HostGlueEmitter` writes one ES module beside the `.wasm` from a `HostGlueEmitter.Surface`
of facts the compiler already settled; nothing is re-walked, except which host hooks survived the
shake (`Surface.afterShake`).

Emitted: the import object, `(ptr, len)` staging both ways, the mark/reset bracket with the result
decoded BEFORE the pop, the `__ronto_seed_random`/`__ronto_set_time`/`_initialize` startup order,
`Suspending` wrappers, `promising` entry, the one-call-at-a-time queue. **Left to the host**: one
plain function per import; a `:bytes` RESULT answered with CHUNKS (`null` ends).

**Which entries suspend is the HOST's answer, not the declaration's**: on node 24 a synchronous
import behind `Suspending` still parks. The file exports `suspending(fn)`: marks switch it to JSPI,
no marks drives a synchronous host from the SAME file, an unmarked entry answering a promise is
reported by name. `--host-random`'s `env.random_get` and `--host-output`'s `env.fd_write` are
IMPLEMENTED by the glue; the promising list is `SuspendingImports.reaches` widened to `FETCH`.

**A marked import's settle is where the module's clock moves** (`bind`, where the module
exports `__ronto_set_time`): the wrapper writes it again in a `finally`. Frozen for a whole
call, a deadline computed from it never came (a socket's read deadline waited for ever, m44);
a fetch's caller now reads the time after the reply. **And a marked import called while the
module loads is named on `console.error` before the trap** (`loading`, not under
`--host-timer`, whose `_initialize` is promising): the trap itself is a bare
`RuntimeError: unreachable`.

### Traps the generated file must avoid (none visible from a passing build)
- **A host hook the shake dropped is not called**: the surface is narrowed to the shaken module's
  exports (`.kb/wasm-export-no-wasi.md`); calling a dropped one threw at instantiate (every Worker
  request a 500). `WasmHostGlueE2eTest.theGlueCallsOnlyTheHostHooksTheShakeKept`.
- **An EXPORT never becomes a local**: entry points are PROPERTIES (locals `entry$name`/`make$name`),
  since an export named `call`/`bind` would be a `SyntaxError`. A non-identifier name is REFUSED.
- **Two `wasm-import`s on one `(module, field)` are refused unless identical**: the core collapses
  them onto ONE slot and would silently unpack callers of both with one shape.
- **`serially` always takes the queue**, even with nothing suspending: its work AWAITS.
- **A read remainder belongs to its ARGUMENTS and to the call that asked**: dropped at every module
  entry and argument change; a host whose source moves inside a call uses `lisp.drop(key)`.
- **`--emit-js-glue` refuses to overwrite a `.js` it did not write** (`HostGlueEmitter.MARKER`); a
  side-artifact flag without `-o` is an error.
- **Reachability holes mean a MISSING `promising`**: `#'(lambda ...)` must be walked, and a function
  value (`#'name` or a literal lambda) reaching a suspending import widens to any export
  (`anyTakenAsValue`). Missed, the glue writes no `defaultHost()` fetch and `worker(module)` 500s
  every request: `host.env.fetch is missing; this module imports it`.
- **Divergence**: `:bytes` RESULT is DECLARED as full-length-answer-with-retry; the glue implements
  the STREAM reading instead. A retry host writes that import by hand.

`serially(work)` exposes the critical section for one call's host state.
`examples/cloudflare-workers/dog-fetcher` is the worked example (`src/worker.js` generated, checked
in). `--no-wasi` core modules only. Pins: `HostGlueEmitterTest` (every checked-in `src/worker.js`
byte-for-byte), `RontoLispCliTest`, `WasmHostGlueE2eTest` (node 24 JSPI).

### `--emit-worker-entry` -- the Worker's `index.js`, written once
Separate from `--emit-js-glue` because the glue also serves node and browsers, and
`import module from "./x.wasm"` is wrangler's bundler convention. Implies `--emit-js-glue`.
`HostGlueEmitter.workerEntry` writes `WORKER_ENTRY` (`index.js`) in the `-o` directory.
- **Never overwritten, its own output included**: the entry is where a Worker takes over
  (`remoteAddr`, `host`), so no `MARKER`; an existing file is reported via
  `WasmSourceCompiler.notices`.
- **The name is fixed and the flag takes no value** (the path is stated in `wrangler.jsonc`'s
  `main`); `-o index.wasm` is refused.
- **Refused before writing** when the glue has no `worker()` (`envelopeExport` null) or is not
  `selfContained` (`worker(module)` would 500).
- Examples: plain-entry Workers do not check `src/index.js` in (wrangler cannot bundle before
  `build.sh` anyway); the `httpbin-*` four keep their own. Pins: `RontoLispCliTest`
  (`compileWithJsGlueWritesTheHostGlueNextToTheWasm` and the refusals beside it).

## A WIT interface beyond the flat set: the canonical ABI on a `--no-wasi` module
**Invariant: a `wit-import` binding a member outside the Preview 1 flat set on a `--no-wasi` wasm-GC
module takes the `--component` lowering (`%component-import`); `WasmLispCompiler` compiles the same
`WasmComponentImportCompiler` wrappers and leaves their imports in the core import section (module
the canonical interface id, field the canonical-ABI name such as `[method]bucket.get`, the flat
signature), and the glue lifts/lowers the canonical ABI in JS from `CanonicalLayout` descriptors
that `codegen/wasm/WitGlueLayouts` computes with `WitCanonicalAbi` -- one layout calculator for both
halves.** So one source keeps sessions in `wasi:keyvalue` on a component and in Workers KV on a
Worker; `wasi:config`, `wasi:blobstore`, `wasi:logging` take the same path.
- **Rejected: a flatter host-shaped import adapted by the binding** (status side channels like
  `found()` / `last-error()`): only 0.4% smaller, loses error cases, needs host state between calls
  (wrong once calls interleave), breaks on a newline in a key, and must be redesigned per interface
  by every front end.
- **Decided per BOUND set** (`WitImportDirective.bindsBeyondFlat`, `WitImportDirective.CanonicalCore`):
  an interface whose bound members are all flat keeps the Preview 1 block byte for byte (WebGL demos,
  the Clojure `example:blob/blob` program). `:canonical t` forces it (the Java front end on
  `--no-wasi`, whose binding is written before the reached set is known). A WASI Preview 1 module and
  `--native` refuse: their host answers no canonical import.
- Refused on a core module where a component carries it: `async func`, stream/future
  (`refuseAsyncOnCore`) and `--reentrant` (absolute-pop staging). A canonical form binding
  nothing is dropped.
- **May suspend**: every canonical import except what the glue answers synchronously
  (`HostGlueEmitter.answersSynchronously`: a drop, the store's `open`). Hence the load-path guard
  `onLoadPathOfCanonical`, and EVERY export promising: the call walk does not follow a Java program's
  dispatch.
- **The JS side**: `lifted(what, call, params, result, retptr, raises)` per import over
  `load`/`store`/`liftFlat`/`lowerFlat`; jco's value conventions (BigInt for 64 bits, `undefined`,
  `{ tag, val }`, camelCase keys; flags an object of booleans, measured on jco 1.42.0 2026-11-26:
  every label present lifted, its key camelCased (`read-only` -> `readOnly`), a label left out,
  `null` or `undefined` lowered false, any truthy value true, an unknown key ignored; jco throws
  `TypeError` on a non-object, so does the glue); a top-level `result` error arm thrown as
  `WitError`; resources in a table per `<iface id>#<name>`. A `u64` at or past 2^63 lifts as a float (`boxI64`, as on a
  component). Pinned by
  `WasmHostGlueE2eTest.anInterfaceTheFlatSetDoesNotCarryCrossesThroughTheCanonicalAbi`.
- **The export direction** (`HostGlueEmitter.WitExport`, `WitGlueLayouts.of(WitExportSignature)`):
  a canonical `wit-export` on `--no-wasi` is the component's core export (`coreParams` /
  `coreResults`), and the glue's `exported(params, spilled, result, retptr)` lowers the caller's
  values (`lowerFlats`, a case's payload `widen`ed into the variant's joined flats; past 16
  flats one tuple through `alloc`/`store`) and lifts the answer (`liftFlat` / `load`) inside
  `call`'s mark/reset. **A `result`'s error arm is thrown AFTER `call` returns** (`settled`):
  thrown from `decode` it would skip the reset and leave the staging on the heap. Name: the
  label camelCased, flat beside the flat exports (no per-interface object as jco nests; a core
  module's `checkNames` already refuses two interfaces' one label). WASI Preview 1 keeps the
  refusal. Every glue without a canonical export is byte-identical (the canonical block is
  written for one alone: `lifted` only with a canonical import). Sizes (2026-11-26, default
  `--optimize`, raw / gzip): `examples/wit/shapes/shapes.lisp` 64,504 / 24,365 B, glue 21,024 /
  6,006; a one-function `flip` 1,336 / 827 B (`--component` 1,857), glue 16,953 / 5,034 -- the
  ABI block is written whole, not per kind used: flags' arms cost every canonical glue
  1,077 / 238-299 B (2026-11-27), the modules byte-identical. Pinned by the `mirror` leg of
  `WasmHostGlueE2eTest.anInterfaceTheFlatSetDoesNotCarryCrossesThroughTheCanonicalAbi` (every
  kind but a handle, flags flat and in a list, spilled parameters, the arena back at its mark) and
  `WitExportInlinerTest.aWorldIsRefusedAnOutputThatCannotExportIt`.
- **`wasi:keyvalue` over Workers KV** (`HostGlueEmitter.KEYVALUE`, `0.2.0-draft2`'s `store` and
  `batch`, each member only where the WIT signature matches): `defaultHost(stores)`; `worker()` maps
  `""` to binding `KEYVALUE`, other identifiers to their own name, `options.keyvalue` overrides.
  **Workers KV takes ONE write a second per key (`429` on the next)** while the session store writes
  a key several times a request, so `worker()` holds a call's writes (by store NAME), reads them back
  to the call, and in `finally` hands them over (`handOver`) to a per-key drain behind the answer
  (`ctx?.waitUntil?.(puts)`): one put at a time, a second after the last ended, newest value
  replacing the waiting one, `429` retried at 100..1600 ms, failures `console.error`ed (the response
  is gone). `atomics` and the first draft are not answered (no CAS in KV; `list-keys` cursor `u64`).
- **Answering before the write**: handler 160-207 ms -> 3-12 ms, TTFB roughly halved. The price: a
  request reaching ANOTHER isolate before the put lands reads the old value (back-to-back requests
  counted 1 2 3 4 5 4 4 4 6 5), and a first visit can lose its session. Not an option: the user asked
  for the answer first; the README states what a request waits for.
- **`wasi:logging` on the console** (`HostGlueEmitter.LOGGING_LOG`): `log` -> `console.debug`
  (trace, debug) / `info` / `warn` / `error` (error, critical), context ahead of the message.
  Synchronous, so in `answersSynchronously` and allowed while loading. Consumer: a Java program's root
  logger under `--no-wasi --wasi-logging` ([java-frontend.md](java-frontend.md), "java.util.logging",
  "The host's log"); pinned by `JavaReactorTest`.
- Every program without such an import, and its glue, is byte-identical.
- Pins: `HostGlueEmitterTest.theCheckedInSessionGlueIsWhatAKeyvalueReactorBuildWrites`,
  `WasmHostGlueE2eTest.anInterfaceTheFlatSetDoesNotCarryCrossesThroughTheCanonicalAbi`,
  `WitImportDirectiveTest.refusesATypeThePreview1BoundaryCannotMarshalNamingItsSettledRepresentation`,
  `JavaWitImportTest.theKeyvalueCounterPrintsTheLispCountersReportOnTheJdkAndEveryTarget`,
  `RontoLispCliTest` (an unanswered canonical import refuses `--emit-worker-entry`), and
  `FetchSpecE2eTest#javaHttpServer`'s `keyvalue-sessions` reactor leg (node JSPI; skipped without
  `WebAssembly.Suspending`).

## `--host-boundary` -- WHICH boundary, and what each costs
`compiler/HostBoundary`. A MODULE decision (it changes the import list), so a flag of its own;
`--emit-js-glue` stays a boolean.

| | `envelope` (DEFAULT) | `streaming` |
| --- | --- | --- |
| imports | `env.fetch` under `--host-fetch`, else NOTHING | `env.readRequestBody`, `env.writeResponseBody`, + `env.readResponseBody`/`env.fetch` under `--host-fetch` |
| host state | none | one cursor per reading import |
| binary body | DESTROYED -- `ff fe 41` in, `ef bf bd ef bf bd 41` out | crosses exactly |
| large body | copied, memory proportional | linear memory flat |
| streamed upstream reply | buffered first | forwarded chunk at a time |

**The DEFAULT is `envelope`** because a default is the recommendation for those who don't read the
guide, and an envelope host has no cursor to outlive anything (both defects found in the glue review
were state-lifetime bugs). **Not a size decision -- do not quote a bound** (within ~1%, sign
unstable; `size-report` measures both). **Trap**: an envelope BINARY body is destroyed silently,
`content-length` still saying three.

**On BOTH boundaries the emitter writes** `defaultHost()` (the `env.fetch` host half, from
`FetchResponseShape`) and `worker(module, options)` (a `Request` onto `ReactorEnvelope.REQUEST_KEYS`,
a `Response` off `RESPONSE_KEYS`, instance created on first request, retired if a call traps). A
Worker is `export default worker(module)`. `defaultHost(lisp)` takes a thunk answering the instance
because ITS cursor is what a second fetch in one call supersedes; body imports live in `worker()`
(per-CALL state).

**Two facts derived from the IMPORTS, not the flag**: `derivedFetch` (`--host-fetch`, program calls
`rontolisp:fetch`, `env.fetch` imported, `env.readResponseBody` not) and `envelopeExport` (an export
that is both the synthesized bridge and the transport's export name). So
`examples/cloudflare-workers/httpbin`, exporting `handle-request` by hand, gets no `worker()`.

**`ReactorEnvelope` (in `compiler`) holds the envelope's names** because three packages that may not
import each other need them (`eval` synthesizes, `codegen.wasm` recognises, `HostGlueEmitter` maps).
`ReactorEnvelopeTest` pins both key lists against `http-reactor.lisp`. **`rontolisp-body-imports`**
(`reader/Features.BODY_IMPORTS`) is present exactly where the body imports exist -- name the imports,
not the targets.

### Traps in the generated `worker()`
- **The instance is bound at admission, so `poisoned` must be re-read INSIDE the critical section**
  (`live().serially(...)` evaluates `live()` first): after an import threw, queued requests answered
  200 with the trapped call's special binding still shallow-bound. The module's re-entry guard does
  not save them -- the EH landing pad CLEARS it on exactly that path.
- **The WHOLE handler belongs in the try** (`request.arrayBuffer()` on an aborted upload, `new
  Response` on an odd status escaped as unhandled rejections). `poisoned` only when the call ENTERED
  the module.
- **`new Response("", { status: 204 })` is a TypeError**: answer `head.body || null`.
- **The decoder must `ignoreBOM`**: these octets are a VALUE.
- **An options hook is awaited** (an async `remoteAddr` crossed as `{}`).
- **The three-line sketch is written only when the file answers EVERY import** (a load-path-only
  fetch imports `env.fetch` while no export is promising).

## The re-entry guard -- a module that can suspend refuses interleaved calls
A parked JSPI call returns to the event loop, and nothing in the module owns state per call: arena
marks interleave, shallow-bound specials share one cell ([[dynamic-special-variables]]), a
`(ptr,len)` result sits at un-advanced scratch -- silent wrong bytes, measured.

So a module that CAN suspend carries a `mut i32` guard global, THIRD FROM LAST so the cached-t/raw
sentinel stay last (`reentryGuardGlobalIndex`; only when it exports something). EVERY export wrapper
checks-and-sets it on entry and TRAPS when set (no Lisp handler can be active at export entry, and a
reactor's output is a sink), and CLEARS it on every return including the EH `catch_all` pad
(`WasmExportCompiler.emitReentryGuardStore`), so a host that catches a trap and calls sequentially is
not refused. A non-suspending module gains nothing; utility exports stay unguarded. Pins:
`WasmImportCompilerTest.aSuspendingImportGuardsEveryExportAgainstReentry`,
`.hostFetchGuardsExportsExactlyWhereFetchIsUsed`, `aGuardedExportAnswersThroughASynchronousHost`.
The guard is the DEFAULT; `--reentrant` retires it. `--host-timer`'s `env.idle` LOWERS it for
its host call alone (`WasmImportCompiler`, keyed on `compiler/HostTimer`'s names): the one wait
the host may enter the module in, a call that holds nothing of the arena, no special bound
across it ([java-frontend.md](java-frontend.md), "A server's waits through its host").

### Under `--host-timer` the queue is a lock a call idling hands on
`HostGlueEmitter`'s `Surface.timer` (the flag's imports, which the glue implements): a promise
chain cannot let a call out and back in, so `enter`/`leave` over a FIFO of resolvers; `env.idle`
leaves, waits for its bell or its `at`, enters again, restores the call's `context` (what
`serially(work, state)` handed over: the worker's request body and chunks) and moves the clock;
`env.sleep` keeps the lock. `instantiate` is async (`_initialize` through `promising`, under the
lock); every entry is promising. Arena: popped to where the FIRST call in hand found it --
popping to one's own mark after an earlier-entered call returned moved the arena back up. A trap
retires the instance (`discard`): the bells are rung and an idle call re-entering throws.
`env.wake(at)` arms one `setTimeout` for the soonest turn asked for (`Surface.turn`); the turn
enters `__ronto_turn` through `serially` and `call` as an entry point does, and a trap in it
discards too. The timer fires after `instantiate`'s tail set the turn's runner: a wake in
`_initialize` is answered by a macrotask, after the microtasks that finish `instantiate`. Pinned
by the corpus's reactor leg and
`WasmHostGlueE2eTest.aSleepUnderHostTimerWaitsInTheHostsTimerAtTheTopLevelAndInACall`.

## `--reentrant` -- overlapped calls on ONE instance
For I/O-bound loads that cannot afford an instance per request: overlapped calls on one instance come
close to the per-request shape, far ahead of serialised. Everything is `reentrant`-gated, other
modules byte-identical (`WasmReentrantCompilerTest`).
- **Per-task dynamic store** (`codegen.wasm/WasmDynVars`, [[dynamic-special-variables]]): the JVM
  `_d$` hybrid -- only `SpecialVarCollector.collectDynamicallyBound` names get a slot in a per-call
  TASK RECORD, created by every export wrapper (and `_start`) and restored around the ONE place
  another extent can run, the suspending host call. Reads dynamic-first. Under-collection is a
  compile-time throw at the binding site.
- **Park-block allocator** (`WasmExportRuntimeBuilder.buildParkAllocBody`, exported
  `__ronto_park_alloc`/`__ronto_park_free`): staging that must SURVIVE A PARK goes into first-fit
  free-list blocks carved permanently off the bump heap (never split or coalesced); everything else
  stays on the scratch stack, park-safe because `__ronto_alloc_reset` never goes below
  `PARK_FLOOR_ADDR`. ABI: a `:string`/`:s-expr` EXPORT result is a park block the READER frees; an
  IMPORT result must be park-written by the host, freed by the wrapper; a `:bytes` receive buffer
  passed to an export must be park-allocated.
- **The glue** drops the queue and `serially`; `worker()` calls the entry directly.
- **Refusals**: nothing can suspend; `--component`; `--dynamic` (the eval mirror is per-instance
  state); an ID-LESS streaming body import.

### The streaming body protocol composes, by carrying a CALL IDENTITY
Under the flag every body import gets a leading `:int` id (`env.readRequestBody(id, ptr, cap)`), so
the no-handle rule is SCOPED: serialised builds stay byte-identical, and an id-less `env.*` body
import is refused under `--reentrant`. The REQUEST id is minted by `worker()` and rides
`ReactorEnvelope.CALL_ID_KEY` into the body thunks (`%http-reactor-bind-source`/`-sink`); a fetch
REPLY's id is its OWN (a second fetch must not supersede the first), minted by `defaultHost()` in
`FetchResponseShape.HOST_BODY_ID_KEY`. The `:bytes` reader's remainder is a map keyed by the pull's
arguments.

Pins: `WasmReentrantCompilerTest`, `HttpReactorInlinerTest`/`HostFetchLibraryTest`,
`RontoLispCliTest.theStreamingBoundaryComposesWithReentrant`, `WasmReentrantE2eTest` (node 24 JSPI).
`dog-fetcher` stays streaming + serialised; the composed shape is `examples/cloudflare-workers/dog-relay`.

## `:bytes` -- the byte-TRANSFER type
An `(unsigned-byte 8)` vector (`TYPE_I8ARR`, [[packed-integer-vectors]]) crosses as RAW bytes: the
`:string` decoder is non-validating. **`:string` is a value, `:bytes` is a transfer.**
- A PARAMETER stages as `(ptr,len)`, bump-allocated, sharing the memory-param mark/restore.
- A RESULT is the `read(2)` shape: ONE trailing Lisp parameter (the receive buffer;
  `WasmImportDirective.lispParamCount` / `WasmImportCompiler.lispArity`, from which every stub arity
  derives), host called with trailing `(ptr, cap)` answering the FULL length (undersized = retry,
  never truncation); the wrapper copies `min(n,cap)` and POPS, so a pull loop keeps memory flat. Same
  convention on exports.
- Helpers `_bytes_from_mem`, `_bytes_copy`, `_bytes_fill` gated on the designator
  (`bytesHelpersRideOnlyABytesDeclaringModule`).
- GC core modules only (`--component`: no `list<u8>` lift; `--no-gc`: no arrays).
- E2E: `WasmBytesBoundaryE2eTest` (node; `ff fe 41` exact all four directions, undersized buffer,
  flat loop).

**First consumers are the reactor's two bodies** (`HttpReactorInliner`'s `%reactor-read-body` and
`%reactor-write-body`). **The two spellings are one rule**: IN is a `:bytes` result into a caller
buffer, OUT a `:bytes` parameter staged and popped; the caller owns the memory either way. [[clack]]
("The WASM boundary") has why each thunk CALLS its import and why a WASI command module keeps
in-band bodies.

## `:octets` -- the same octets as a VALUE
What `rontolisp:wit-import :octets t` lowers a WIT `list<u8>` to on a wasm-GC core module ([[wit]],
"`:octets t`"). Import only (`BoundaryType.importOnly`); `--no-gc` refuses (no WIT-less member).
- Host ABI is `:string`'s `(ptr,len)`. A PARAMETER stages like `:bytes` (`stagesOctets`), a
  non-vector argument staging as its UTF-8 (the component's `emitStageBytesParam` rule, so both take
  the same arguments). A RESULT is host-reserved (`__ronto_alloc`, or `__ronto_park_alloc`) and lifted
  by `_bytes_from_mem`. `usesBytes` covers it.
- Pins: `WasmOctetsBoundaryE2eTest`, `WasmReentrantE2eTest.overlappedOctetsCrossThroughParkBlocks`,
  `HostGlueEmitterTest.anOctetsImportHandsTheHostAUint8ArrayAndTakesOneBack`,
  `WasmImportCompilerTest.parsesOctetsAsAValueOfTheStringShapeAndKeepsItAnImportOnlyType`.

## `"rontolisp:memory"` -- the module's own memory, as imports
For C-shaped host ABIs answering through out-pointers (Proxy-Wasm's
`proxy_get_header_map_value(..., char**, size_t*)`). `WasmMemoryIntrinsic` is the fixed field table;
a directive naming this reserved `:from` still makes the synthetic defun (so `#'name` and the stubs
come free) but its body answers from the module (`buildIntrinsicBody`; `compileIntrinsicBody` on
`--no-gc`). `hostImportOrdinal`, `importSlots`, `glueImports`, `NoGcWasmCompiler.importOrdinals`
skip it: no import entry, no glue.
- `parse` validates field and exact signature. Arena fields (`needsArena`) force the allocator; only
  `load32` carries none. `--no-gc --component` refuses it.
- Declaring the arena fields means the program owns the heap: `--no-gc` drops its scalar-return reset
  (`Mem.programOwnsHeap`, `.kb/no-gc-scalar-wasm.md`).
- `address`/`size` take a `:string` and answer its bytes: `--no-gc` only (a wasm-GC string is an
  engine object).
- `search` is the one loop over memory (`WasmMemoryIntrinsic.emitSearch`, both backends): first
  occurrence or -1, eight bytes at a time by the zero-byte test on `word ^ pattern`, `ctz` of the mask
  the first candidate. 3.4 instructions per byte vs 86 in Lisp over `load8`
  (`.kb/string-search-runtime.md`).
- Pins: `WasmOctetsBoundaryE2eTest`, `NoGcWasmImportE2eTest.theMemoryModuleReadsWhatAHostAnsweredThroughOutPointers`
  (each with `SEARCH_CHECK`), `WasmImportCompilerTest.theMemoryModuleIsTheFixedSetOfFieldsNotAHostModule`,
  `examples/proxy-wasm/check.sh` in a real Envoy (not CI: needs Docker).

**`:u32` on wasm-GC, and why `:s32` arguments did not widen instead.** An `:s32` argument is
`ref.cast i31; i31.get_s`, trapping past 2^30; a Proxy-Wasm context id reaches that eventually. Widening
`:s32` would make every WebGL-style `:int` boundary pay, so `:u32` is opt-in: one `_u32_val` call
(`FUNC_U32_VAL`, `WasmBignumRuntimeBuilder.buildU32ValBody`), exact-or-trap.

**An `:s32` RESULT boxes through `_int_new`**: `i31.new` kept 31 bits (1500000000 became
-647483648). Size cost negligible.

## The component path does NOT go through this compiler
Under `--component`, `rontolisp:wit-import` lowers to `rontolisp::%component-import`, which
`WasmComponentImportCompiler` turns into canonical-ABI defuns -- a different compiler, the SAME
synthetic-defun + `PLACEHOLDER_FUNC_BASE` + `WasmImportInjector` mechanism, one ordinal space. See
[[wit]] ("Component imports").

## Tests and showcases
`WasmImportCompilerTest` (import order, index shift, allocator gating, mode rejection); preload E2E
in `WasmLispCompilerIntegrationTest` (`wasmtime run --preload host=... main.wasm`); stubs in
`LispEvaluatorTest`/`JvmLispCompilerTest`. Showcases: `examples/browser/webgl-triangle/`,
`webgl-cube/`, `webgl-galaxy/` (the WebGL2 boundary in `examples/browser/webgl-common/gl.lisp`);
`examples/proxy-wasm/` is a whole C-shaped ABI on `rontolisp:memory`, run in a real Envoy.
