# `rontolisp:wasm-import` (host functions callable from Lisp) + export `:as` aliases

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(T...) :returns T)` declares a
host function (JS import object key `module`, property `field`; wasmtime `--preload module=...`)
and makes it callable from Lisp like a top-level defun. Type designators are shared with
`WasmExportCompiler` (`:int`/`:float`/`:bool`/`:string`/`:s-expr`/`:bytes`, `:void` return; the
ACCEPTED SUBSET is per-backend -- see "Modes" below). Generic parsing:
`compiler/WasmImportDirective` (shared with the JVM backend); WASM validation/codegen:
`WasmImportCompiler` on wasm-GC, `NoGcWasmCompiler` under `--no-gc`.

## How the fixed-index invariant survives adding imports
The WASM spec puts all imported functions before all defined ones, so a new import would shift
every `FUNC_*` constant. Instead each import becomes a **synthetic defun** (Pass 1 marker body;
Pass 2a reserves a `userFunctionBodies` slot). The wrapper unboxes each argument, emits
`call (PLACEHOLDER_FUNC_BASE = 1<<27) + ordinal`, then boxes the result. Because it IS a defun,
`#'name`/`funcall`/`mapcar`/dispatch/`eval` work with no extra wiring. Host-ABI func types are
appended after the export wrapper types (`TYPE_P1_FUTURE + 1 + numExports + j`).

`am.ik.wasm.WasmImportInjector` then rewrites the finished module: prepends the import entries at
the FRONT of the import section (indices 0..K-1), remaps every `call`/`ref.func` immediate
(`>= placeholderBase -> ordinal`, else `+K`), and shifts export/start-section function indices.
Runs before `WasmTreeShaker.shake`. **The pre-injection module is invalid (calls to 2^27) --
never validate or emit it directly.**

A RUNTIME BUILDER may bind one through the same placeholder encoding: `--host-random`'s entropy
import, the `_argv` helper's `args_sizes_get`/`args_get` pair (`WasmArgvRuntimeBuilder`,
[[uiop]]) and `file-position`'s host call (`--component`'s `file_position_get`/`_set` pair, or
Preview 1's `fd_seek`, [[read-load-streams]]) are appended to `hostImports` LAST, after the
directive-declared slots, so a program that also writes `rontolisp:wasm-import` keeps its
ordinals and bytes.

**This, not a new index-pinned preview1 slot, is how a WASI function the backend did not import
before gets added.** A sixteenth slot in the fixed block shifts `IMPORT_FUNC_COUNT`,
`FUNC_START` and every emitted function index, and with them both `--component` adapter blobs
and the `--no-wasi` stub block; appending shifts nothing and costs nothing on a program that
does not reach the feature. `fd_seek` (2026-09-19, `.todo/876`) was planned as slot sixteen and
landed as an appended import instead: measured over a nine-module corpus, every module that
does not call `file-position` -- Preview 1, `--component` and `--no-wasi` alike -- is
BYTE-IDENTICAL, and the one that does grows 20334 -> 20987 bytes (+653: the two real runtime
bodies, the import, and the per-`open` flag write). An appended import needs a type entry only
when no fixed type already has its shape; `fd_seek`'s `(i32, i64, i32, i32) -> i32` is the
first one that did, so it is emitted right after the `importSlots` block and counted into
`abiTypeBase` (`--host-random` and `args_get` reuse `TYPE_INTERN` and add nothing).

## Memory-typed PARAMETERS stage on a stack; the RESULT scratch does not
A `:string`/`:s-expr` parameter crosses as `(ptr,len)` into linear memory, and N of them have
to hold N regions AT ONCE, across the host call. `WasmExportCompiler.emitStringResult` writes
at the un-advanced `HEAP_PTR` scratch DELIBERATELY -- right for its original caller, a single
RESULT the host reads the instant the wrapper returns ([[wasm-gc-strings]]) -- so reusing it
per PARAMETER aliased every one of them onto ONE base. Measured against a Node host on
`:params '(:string :string)` (2026-09-12): `arg1 ptr 33089 len 12 = "BBBB\"AAAAAAA"`,
`arg2 ptr 33089 len 4 = "BBBB"` -- the host saw the LAST argument's bytes under EVERY pointer,
with the earlier argument's length. Silent: the module validated, instantiated and ran.
`(:string :s-expr)` the same. `wasmtime --invoke` cannot read two pointers apart, which is why
it survived to be found by generating a host.

`WasmImportCompiler.emitStagedMemoryParam` stages each region instead:

- **Serialised**: the bytes go to `HEAP_PTR` as before and `HEAP_PTR` is then ADVANCED past
  them (8-aligned, like `__ronto_alloc`), making the scratch a stack. The wrapper takes ONE
  mark up front and pops the whole run after the call -- the `:bytes` discipline, now shared
  by both (`staging = bytesStaging || memStaging`, one mark, one restore).
- **`--reentrant`**: an absolute pop is what two interleaved extents cannot share, so each
  region is a park block (`_park_str_result`, the same helper an export RESULT uses), freed by
  the wrapper after the call -- and a park block is the only staging that survives the park
  itself. Two such parameters therefore pull `memoryHelpers` (hence the park allocator) on by
  themselves, even when nothing in the module RETURNS memory.
- **One memory-typed parameter keeps the non-advancing scratch**: it has nothing to collide
  with, so `stagesMemoryParams` gates the whole shape on `>= 2` and every module that was
  already correct stays BYTE-IDENTICAL. `emitStringResult` likewise keeps its one remaining
  job (results, export wrappers), so no existing module's bytes move.
- **The host must read its memory-typed arguments before it answers**: the wrapper releases
  the whole run on return, and the serialised regions are scratch anything may reuse.
- **The contract is READ-ONLY too, though nothing here enforces it.** A host that writes
  through the pointer lands on transient scratch that is already dead the moment the call
  returns -- harmless by luck, not by design. Contrast `--no-gc` (`.kb/no-gc-scalar-wasm.md`,
  "Host imports"): there the same argument shape is durable module memory (no staging at
  all), so the identical write persists and, because `StringTable` dedups identical
  spellings into one block, corrupts every other use of that literal for the life of the
  instance. Same question on both backends, different answer; both are written down
  together in `doc/*/guides/wasm-host-boundary.md`.

### A literal `:string` argument does not round-trip
A literal's bytes START in linear memory -- the interned data segment put them there -- and
the boundary WANTS bytes in linear memory. The general path nevertheless walks them
byte-by-byte into a GC array (`_str_build`) so the wrapper can walk them byte-by-byte back
out (`_str_to_mem`): a pure round trip, invisible to any optimizer because each half is an
ordinary call to a shared helper. `WasmImportCompiler.compileLiteralImportCall` lowers such
a site straight to the host call instead -- one `memory.copy` and no wrapper -- reached from
`WasmFunctionCallCompiler.compileDirectCall`, ahead of the generic user-call path.

- **It COPIES; it never hands over the data segment's own pointer.** Identical spellings are
  deduplicated into one block that also spells interned symbol names, so a host write through
  such a pointer would corrupt every other use of that spelling for the life of the instance
  -- the reason `789`'s item 2 was rejected and what `795` wrote down for `--no-gc`. **What
  this saves is the two byte loops and the GC array between them, not the copy.**
- **Destination: a RESERVED block above the static data**, sized by the WIDEST lowered site
  (`Ctx.litStageBytes`, laid out beside the intern table in `compile`), not the `HEAP_PTR`
  scratch. That is what keeps `_lit_stage` at 24 bytes: a fixed block inside the module's own
  minimum memory cannot be out of bounds, so the `emitGrowHeapTo` guard every other
  linear-memory writer carries -- two thirds of what the helper would otherwise be -- is not
  emitted. Every staged length at a site is a compile-time constant, so a `delta` per region
  replaces the wrapper's mark/restore bracket, which a call site has no i32 local to hold.
  **Same contract as the un-advanced scratch**: the host reads its memory-typed arguments
  before it answers, and a write through the pointer reaches a block the next lowered call
  overwrites.
- **Which sites**: every `:string` argument a LITERAL, every parameter in
  `{:string,:s32,:float,:bool}`, the result in `{:void,:s32,:float,:bool}`, not `--reentrant`.
  `:s-expr` has to be printed first, `:bytes` stages a runtime vector, and a
  `:string`/`:s-expr`/`:bytes` RESULT needs scratch slots (or, for `:string`, the
  `_str_from_mem` index, settled only after every user body is emitted) a call site has not
  got. Arguments AFTER the first staged region are evaluated into temps first -- the regions
  are live and un-popped, so anything allocating linear memory in between would land on them.
- **The wrapper still exists** for `#'name`/`funcall`/`mapcar`/dispatch and for a RUNTIME
  string argument; the shaker drops it when no site needs it, and with it `_str_build` and
  `_str_to_mem` when nothing else in the module builds a string.
- `_lit_stage` sits right after `_arity_chk` (`litStageFuncBase()`), so it shifts
  `userFuncBase()` and no fixed index. Its presence is decided from the DIRECTIVES in front
  of pass 2 (`canLowerLiteralCallSite`), deliberately loose in the same direction
  `emitsArityChk` is: a module whose sites all turn out not to qualify pays one unreferenced
  function, while a site emitted against an index the module never reserved cannot happen. A
  site settles its import ordinal the same way, from the declarations alone, and `compile`
  checks that answer against the core import slot list rather than trusting it.
- **Measured 2026-09-13** on the `789` reactor (`--no-wasi --optimize=size`): **1,654 ->
  1,308 bytes (-21%)**, `_str_build` 69 + `_str_to_mem` 113 + the two import wrappers gone,
  `_lit_stage` 24 in. For scale, `-Oz` over the OLD module stopped at 1,495
  ([[optimize-dead-code-elimination]], "What an external optimizer still finds"). Where the
  round trip's helpers stay alive for other reasons the lowering costs a few bytes per site
  and the helper once: `webgl-cube`/`galaxy`/`heat3d` -15 each, `platformer` +8,
  `battlefront` +9, `robot-arm` +27, `solids` +45 -- under 0.03% on every one of them, and
  every module that declares no `:string` import is byte-identical. Pins:
  `WasmImportCompilerTest.aLiteralStringArgumentCrossesWithoutTheGcRoundTrip` (the PAIR: the
  same module with the argument BUILT at runtime carries all of it) and
  `.theLiteralLoweringDeclinesTheShapesItCannotMarshal`; the CONTENT is
  `WasmStringParamBoundaryE2eTest`, whose literal exports are exactly this path.
- **`--no-gc` has the same idea and shares no code with it**
  ([[no-gc-scalar-wasm]], "A literal `:string` argument is two constants"). There a string
  value IS a `[len][bytes]` pointer, so a literal's pointer AND length are both compile-time
  constants and the site needs no staging block, no copy and no helper -- what it removes is
  the WRAPPER, and only when every reached site of that import qualifies (including the ones
  a thin forwarder hides), because that backend has no first-class functions and can
  therefore see every reference. `canLowerLiteralCallSite` is not shared either: the
  vocabularies differ (`:s32` here, the whole fixed-width family there), so the same question
  has two answers.

Pins: `WasmImportCompilerTest.twoMemoryTypedParamsStageOnDistinctRegions` (the `HEAP_PTR`
advance, once per staged parameter, absent at one -- measured on RUNTIME-built arguments,
since a literal site no longer reaches the wrapper),
`WasmReentrantCompilerTest.parkHelpersRideOnlyAReentrantModuleWithAMemoryBoundary`, and the
CONTENT against a Node host in `WasmStringParamBoundaryE2eTest` (every combination, a
runtime-built string, the flat-memory loop).

## Modes, other backends, aliases
- `--component` on wasm-GC throws a clear `UnsupportedOperationException` (a GC component binds
  interfaces through `wit-import`'s `%component-import`); `--no-gc --component` TAKES it, the
  reached imports becoming the component's instance imports (`.kb/no-gc-scalar-wasm.md`, "Host
  imports" under `--no-gc --component`), which is why the directive carries `:param-names`
  (parsed generically, default `p0..`, read by that wrap alone). Interpreter and JVM
  define error-signalling stubs so shared sources load everywhere (`JvmLispCompiler` pass 1
  synthesizes `(defun name (...) (error ...))`; the directive is an `ACONST_NULL` no-op).
- **`--no-gc` takes the directive too** (`.kb/no-gc-scalar-wasm.md`, "Host imports"), through
  wrappers of its own over the unboxed value model. What is SHARED is everything above the
  codegen: `WasmImportDirective` parses it, `WasmImportCompiler.parse`/`hostParamTypes`/
  `hostResultTypes` settle the host signature, and `am.ik.wasm.WasmImportInjector` resolves the
  same `PLACEHOLDER_FUNC_BASE` encoding. **The accepted TYPE SET is per-backend and follows the
  house integer**, which is why `parse` takes it as an argument: `KNOWN_PARAM_TYPES` here
  (`:s32`, `:s64`, `:float`, `:bool`, `:string`, `:s-expr`, `:bytes`, `:extern` -- the house
  integer is `i31ref`; `:s64` crosses through the boxed exact integer, `_int_val` / `_int_new`),
  `SCALAR_PARAM_TYPES` there (every type with a WIT spelling, i.e. the whole fixed-width integer
  family plus `:float`/`:bool`/`:string` -- the house integer is `i64`, and `:s-expr`/`:bytes`
  are heap objects that model has no runtime for). `SCALAR_PARAM_TYPES` is DERIVED from
  `BoundaryType.witName() != null` so it cannot drift from what a WIT world can name.
- A `:string` result forces the `__ronto_alloc`/`_str_from_mem` pair (`memoryHelpers`); an
  `:s-expr` result forces `usesRead`. **Latent gap fixed:** `usesStrFromMem` named only `:string`,
  so an `:s-expr`-only module exported no `__ronto_alloc` (`TypeError`); pinned by
  `WasmImportCompilerTest.sexprResultExportsTheAllocatorToo`.
- **Export aliases**: `wasm-export` takes `:as "alias"`; `Decl.exportName()` defaults to the Lisp
  name. Used by the GC backend export section and `NoGcWasmCompiler`.

## `:extern` -- a host reference the module holds (2026-09-25)
An import-only boundary type (`BoundaryType.EXTERN`; every export parser refuses it, a
`--component` build too): the host's `externref`, boxed by the wrapper in a one-field
`(struct (field externref))` appended at `WasmLispCompiler.hostRefTypeIndex()` -- right after the
extra callable types, so `fixedTypeCount()` grows by one only in a module that names it and every
other module is byte-identical. A parameter unboxes with `ref.cast` + `struct.get` (nil traps). What
it is FOR: wasmtime drops an `externref`'s host data when the collector finds it dead, the only
finalizer a wasm-GC module can have -- `objc-native.lisp` releases an Objective-C object through it
([objc.md](objc.md), "--native").

## Directives inside a user `defpackage`
Unlike ordinary quoted data, the quoted NAME argument of both directives IS package-resolved
(`PackageResolver.resolveWasmDirective`), so `(wasm-import 'create-shader ...)` under
`(in-package gl)` registers `gl:create-shader`, matching what call sites canonicalize to. Only
the name argument is special. The host-facing default (`:as` omitted) is the bare MEMBER name,
never the qualified spelling (`WasmExportCompiler.unqualifiedMember`).
**Caveat**: a program that takes functions as values keeps same-arity import wrappers reachable
through the funcall dispatcher, so `--optimize` cannot shake them.

## It is also a LOWERING TARGET ([[wit]])
On Preview 1 a `rontolisp:wit-import` expands to exactly one directive per WIT function -- same
shape, same synthetic-defun mechanism, same injector -- so the module is byte-identical to the
hand-written block. `:from` defaults to the interface's bare name; the WIT label becomes the `:as`
field camelCased (`:field-style :camel`, default) or verbatim (`:kebab`). On wasm-GC only
`:int`/`:float`/`:bool`/`:string` are reachable from a WIT type, so anything outside that flat set
is a compile error naming the WIT file and line; under `--no-gc` the whole fixed-width integer
family is reachable too, each at its own width ([[wit]]). **One directive binds one interface into one
module.** GL objects cross as `type shader = s32` handle ALIASES. `webgl-common/gl-imports.js` is
GENERATED from the same gl.wit (`GlImportObjectTest` reuses
`WitImportDirective.FieldStyle.CAMEL` so JS field names cannot drift from the lowering).

## Asynchronous hosts: JSPI
An import wrapped in `WebAssembly.Suspending`, entered through `WebAssembly.promising`, parks the
wasm stack until the promise settles. workerd has it unflagged; node 24 needs
`--experimental-wasm-jspi`. **Two obligations**: (1) a suspending import may only be called on a
stack entered through `promising`, so the `_initialize` load path must never reach one;
(2) suspending RE-ENTERS the module, sharing globals and the `__ronto_alloc_mark`/`_reset`
bracket (LIFO marks that cannot nest across interleaved requests). `--host-fetch` discharges (1)
for fetch ([[fetch-http]]).

### `:async t` -- a suspending host import is a future
The call returns a FUTURE that `rontolisp:await` resolves. On this backend the future is
DELIBERATELY DEGENERATE -- JSPI blocks the wasm stack, so the wrapper wraps the boxed result in a
settled kind-2 `TYPE_P1_FUTURE` (`WasmImportCompiler.buildWrapperBody`). started == settled is the
documented CONTRACT; the option buys one source reading the same on every backend, not
concurrency. `:async` takes literal `t`/`nil` only. A `wit-import`ed `async func` lowers to
exactly this on Preview 1, which makes `futurep` agree on all four backends.

`compiler/SuspendingImports` (riding `NoWasiLoadPathRefusals.walk` with a `rootFunction` seed)
gives the build: an obligation warning naming each `module.field`; a listing of WHICH exports can
reach a suspending import (an import taken as `#'value` widens it to "ANY export"); and, under
`--no-wasi` only, an ERROR when the LOAD PATH reaches one. A WASI command module keeps the warning
and refuses nothing. Pins: `SuspendingImportsTest`, `WasmImportCompilerTest.parsesAsyncOption`,
`WitImportDirectiveTest.lowersAnAsyncFuncMemberWithAsyncTOnPreview1`, the
`asyncImportAnswersASettledFutureThatAwaitResolves` preload E2E, and ONE module driven both ways
(`WasmReactorBodyE2eTest` synchronous vs `WasmReactorStreamingHostE2eTest` suspending).

**`reaches` follows CALLS**, and the reactor hands its body bridges over as `#'name`
(`HttpReactorInliner`), so no reactor export was ever listed. The walk takes a `followValues`
flag, true ONLY for the reachability question (`#'name` and a literal lambda body count as
reached) because it is a MAY analysis and under-reporting means a missing `promising`; the
load-path report says what actually RUNS and never follows one.

## `--emit-js-glue` -- the host half is written, not described
`compiler/HostGlueEmitter` writes one ES module beside the `.wasm`. `WasmLispCompiler` builds a
`HostGlueEmitter.Surface` from facts it already settled (parsed declarations, the helper exports
the module carries, the same `SuspendingImports` answers) and `hostGlueJs(fileName)` emits from
it; nothing is re-walked or read back out of the bytes.

Emitted: the import object, the `(ptr, len)` staging of every memory-typed value in both
directions, the `__ronto_alloc_mark`/`_reset` bracket with the result decoded BEFORE the pop, the
`__ronto_seed_random`/`__ronto_set_time`/`_initialize` startup order, the `Suspending` wrappers,
the `promising` entry, and the one-call-at-a-time queue. **Left to the host**: one plain function
per import over ordinary JS values; a `:bytes` RESULT is answered with CHUNKS (`null` ends them).

**Which entries suspend is the HOST's answer, not the declaration's.** `:async t` says the module
TOLERATES a suspension there; on node 24 JSPI a synchronously-answering import behind a
`Suspending` still parks the stack. The file therefore exports `suspending(fn)`: one mark switches
it into its JSPI shape, no marks leaves the SAME file driving a synchronous host, and an unmarked
entry answering a promise is reported by name. Two facts beyond the directives: `--host-random`'s
`env.random_get` is IMPLEMENTED rather than asked for, and the promising list is
`SuspendingImports.reaches` widened to the `FETCH` kind.

### Traps the generated file must avoid (none visible from a passing build)
- **An EXPORT never becomes a local** -- entry points are PROPERTIES; the two locals are
  `entry$name`/`make$name`, since an export called `call` or `bind` would make the file a
  `SyntaxError`. A name that is not a bare JS identifier is REFUSED with the alias to change.
- **Two `wasm-import`s on one `(module, field)` are refused unless identical** -- the core module
  collapses them onto ONE slot, so only the last shape would survive and silently unpack every
  caller of both.
- **`serially` always takes the queue**, even with nothing marked suspending: the work it runs
  AWAITS, so a second request lands inside it. Only a bare entry point skips the queue.
- **A read remainder belongs to its ARGUMENTS and to the call that asked for it** -- the cursor is
  dropped at every module entry and whenever the arguments change; a host whose SOURCE moves
  INSIDE one call drops it itself with `lisp.drop(key)`.
- **`--emit-js-glue` refuses to overwrite a `.js` it did not write** (`HostGlueEmitter.MARKER`); a
  side-artifact flag without `-o` is an error, not a silent interpretation.
- **Two reachability holes, both a MISSING `promising`**: `followValues` walked `(lambda ...)` but
  not `#'(lambda ...)`, and the per-export walk is seeded from that export alone, so a function
  value handed over elsewhere was invisible. `anyTakenAsValue` now widens to "any export".
- **Divergence to know**: `:bytes` as a RESULT is DECLARED as "the host answers the value's FULL
  length, an undersized buffer is a retry"; the generated glue implements the STREAM reading of
  the same shape instead. A host wanting the retry convention writes that import by hand.

`serially(work)` exposes the critical section for host state belonging to ONE call.
`examples/cloudflare-workers/dog-fetcher` is the worked example (`src/worker.js` generated and
CHECKED IN). Gated to `--no-wasi` core modules. Pins: `HostGlueEmitterTest` (every checked-in
`src/worker.js` byte-for-byte), `RontoLispCliTest`, `WasmHostGlueE2eTest` (node 24 JSPI).

## `--host-boundary` -- WHICH boundary, and what each costs
`compiler/HostBoundary`. A MODULE decision (it changes the import list), so a flag of its own;
`--emit-js-glue` stays a boolean and REFUSES a value by name.

| | `envelope` (DEFAULT) | `streaming` |
| --- | --- | --- |
| imports | `env.fetch` under `--host-fetch`, else NOTHING | `env.readRequestBody`, `env.writeResponseBody`, + `env.readResponseBody`/`env.fetch` under `--host-fetch` |
| host state | none | one cursor per reading import |
| binary body | DESTROYED -- `ff fe 41` in, `ef bf bd ef bf bd 41` out | crosses exactly |
| large body | copied, memory proportional | linear memory flat |
| streamed upstream reply | buffered first | forwarded chunk at a time |

**The DEFAULT is `envelope`** -- a default IS the recommendation for everyone who does not read
the guide -- so every `--no-wasi` reactor rebuilt without the flag changes shape, and a BINARY
body is destroyed with `content-length` still saying three and nothing reporting it.
`--host-boundary=streaming` gets the old module back byte-for-byte. **It is not a size decision --
do not quote a bound** (within ~1% either way, sign not stable; `size-report` measures both rows).
The STATE is the point: both defects the glue review turned up were state-lifetime bugs, and an
envelope host has no cursor to outlive anything.

**What the emitter writes on BOTH boundaries**: `Surface` carries `derivedFetch` and
`envelopeExport`, and the file emits `defaultHost()` (the `env.fetch` host half, from
`FetchResponseShape` in both directions) and `worker(module, options)` (a `Request` onto
`ReactorEnvelope.REQUEST_KEYS`, a `Response` off `RESPONSE_KEYS`, instance created on the first
request and retired if a call traps). A Worker is `export default worker(module)` and nothing
else. `options.host` is laid over the derived entries one at a time. `defaultHost(lisp)` takes a
thunk answering the instance because ITS cursor is what a second fetch inside one call supersedes;
the two body imports live in `worker()` because they are per-CALL state.

**Two facts derived from the IMPORTS rather than the flag** -- a flag is a request, what the
module imports is the answer. `derivedFetch` = "`--host-fetch`, the program really calls
`rontolisp:fetch`, `env.fetch` imported, `env.readResponseBody` not"; `envelopeExport` = "an
export that is BOTH the synthesized bridge defun and the transport's own export name". Both
fingerprints need both halves. Consequence: `examples/cloudflare-workers/httpbin`, which exports
`handle-request` by HAND, gets no `worker()`.

**`ReactorEnvelope` (in `compiler`) holds the envelope's names** -- bridge defun, export name, six
request keys, three response keys, the `env` module and the two body fields -- because three
packages that may not import each other need them (`eval` synthesizes, `codegen.wasm` recognises,
`HostGlueEmitter` maps). `FetchResponseShape` gained the `env.fetch` field names for the same
reason. `ReactorEnvelopeTest` pins both key lists against `http-reactor.lisp` in both directions.
**`rontolisp-body-imports`** (`reader/Features.BODY_IMPORTS`) guards a hand-written reactor's own
body imports, present exactly where those imports exist; it replaced
`#+(and rontolisp-reactor (not rontolisp-component))`, which enumerated targets instead of naming
the imports.

### Traps in the generated `worker()`
- **The instance is bound at admission, so `poisoned` must be re-read INSIDE the critical
  section.** `live().serially(...)` evaluates `live()` before the queue admits anything. Measured:
  after a host import threw, two queued requests answered 200 with the trapped call's special
  binding still shallow bound. The module's own re-entry guard does not save them -- the EH
  landing pad CLEARS it on exactly the path that poisons the instance.
- **The WHOLE handler belongs in the try** -- `await request.arrayBuffer()` on an aborted upload
  and `new Response` on an odd status/header escaped as unhandled rejections. `poisoned` is set
  only when the call ENTERED the module.
- **`new Response("", { status: 204 })` is a TypeError** -- the envelope always carries `"body"`,
  so the mapping answers `head.body || null`.
- **The decoder must `ignoreBOM`** -- these octets are a VALUE; the default decoder deletes a
  leading U+FEFF while `content-length` still counts three.
- **An options hook is awaited** (an async `remoteAddr` crossed as `{}`).
- **The three-line sketch is only written when the file answers EVERY import** -- a `--host-fetch`
  build whose only fetch sits on the LOAD path imports `env.fetch` while no export is promising.

## The re-entry guard -- a module that can suspend refuses interleaved calls
A parked JSPI call returns control to the event loop, and NOTHING in the module owns its state per
call: `__ronto_alloc_mark`/`_reset` marks interleave, shallowly-bound specials share one
module-global cell ([[dynamic-special-variables]]), and a `(ptr,len)` result sits at un-advanced
`HEAP_PTR` scratch. Measured on node 24 JSPI, BOTH corruptions are silent wrong bytes.

So a module that CAN suspend carries a guard global -- a `mut i32`, THIRD FROM LAST so the
cached-t/raw sentinel stay last (`reentryGuardGlobalIndex`, emitted only when the module also
exports something). EVERY export wrapper checks-and-sets it on entry (`global.get; if; unreachable;
end` -- a TRAP, because at export entry no Lisp handler can be active and output on a reactor is a
sink) and CLEARS it on every return, including the hand-rolled `catch_all` landing pad in EH mode
(`WasmExportCompiler.emitReentryGuardStore`), so a host that catches a Lisp-error trap and then
calls SEQUENTIALLY is not refused. A module that cannot suspend gains no guard, no global, no
instruction. Utility exports stay unguarded. Pins:
`WasmImportCompilerTest.aSuspendingImportGuardsEveryExportAgainstReentry`,
`.hostFetchGuardsExportsExactlyWhereFetchIsUsed`, the `aGuardedExportAnswersThroughASynchronousHost`
preload E2E. The guard is the DEFAULT; `--reentrant` is the opt-in that retires it.

## `--reentrant` -- overlapped calls on ONE instance
Measured motivation: 8 concurrent 100 ms round trips took 803 ms serialised and 239 ms as
instance-per-request; `--reentrant` answers them in ~125 ms on one instance. Target workload: I/O
bound AND unable to afford an instance per request. All of it is `reentrant`-gated so every other
module stays byte-identical (`WasmReentrantCompilerTest`).
- **Per-task dynamic store** (`codegen.wasm/WasmDynVars`, [[dynamic-special-variables]]): the JVM
  `_d$` hybrid ported -- only `SpecialVarCollector.collectDynamicallyBound` names get a slot in a
  per-call TASK RECORD (a `TYPE_HASH_BUCKETS` of nullable `TYPE_CELL`s in a module global), created
  by every export wrapper on entry (and by `_start` for the load path) and restored around the ONE
  place another extent can run -- the suspending host call in the import wrapper. Reads are
  dynamic-first with the module global as default. Under-collection is a compile-time throw at the
  binding site (the JVM rule).
- **Park-block allocator** (`WasmExportRuntimeBuilder.buildParkAllocBody`, exported as
  `__ronto_park_alloc`/`__ronto_park_free`): the arena's absolute mark/restore is what two
  interleaved extents cannot share, so staging that must SURVIVE A PARK moves into first-fit
  free-list blocks carved permanently off the bump heap (never split, never coalesced). Everything
  else stays on the `HEAP_PTR` scratch stack, made park-safe by one clamp: `__ronto_alloc_reset`
  never goes below `PARK_FLOOR_ADDR`. ABI consequences: a `:string`/`:s-expr` EXPORT result crosses
  as a park block the READER frees; a `:string`/`:s-expr` IMPORT result must be park-written by the
  host and is freed by the wrapper; a `:bytes` receive buffer passed into an export must be
  park-allocated.
- **The glue** drops the queue and `serially`, keeps `suspending()` and the promising selection,
  and `worker()` calls the entry directly.
- **Refusals**: a program nothing can suspend; `--component`; `--dynamic` (the eval mirror is
  per-instance state the task record does not cover); and an ID-LESS streaming body import.

### The streaming body protocol composes, by carrying a CALL IDENTITY
Under the flag `HttpReactorInliner`/`HostFetchLibrary` synthesize every body import with a leading
`:int` id (`env.readRequestBody(id, ptr, cap)` etc.), so the no-handle argument is SCOPED rather
than relaxed: wherever the id-less protocol exists the guard or queue still holds (every serialised
build stays byte-identical), and the compiler refuses an id-less `env.*` body import under
`--reentrant`. Three identities, two mints: the REQUEST's id is minted by `worker()` per request
and rides the envelope's `"call-id"` key (`ReactorEnvelope.CALL_ID_KEY`, in `REQUEST_KEYS`), read
where the envelope is parsed and closed over the body thunks (`%http-reactor-bind-source`/`-sink`);
a fetch REPLY's id is its OWN (a second fetch inside one call must not supersede the first),
minted by `defaultHost()` and returned in the reply head's reserved `"body-id"` key
(`FetchResponseShape.HOST_BODY_ID_KEY`). The reentrant glue keys all per-call state by id, and the
`:bytes` reader's REMAINDER becomes a map keyed by the pull's arguments (the serialised single-slot
cursor dropped the other call's leftover octets on every alternation).

Pins: `WasmReentrantCompilerTest`, `HttpReactorInlinerTest`/`HostFetchLibraryTest`,
`RontoLispCliTest.theStreamingBoundaryComposesWithReentrant`; gates in `WasmReentrantE2eTest`
(node 24 JSPI). `dog-fetcher` stays streaming + serialised; the composed shape ships as
`examples/cloudflare-workers/dog-relay`. Known limit: a fetched reply relayed as-is is TEXT
(`:body` is a character stream on every backend), so a binary upstream body does not cross
byte-exact.

## `:bytes` -- the byte-TRANSFER type
An `(unsigned-byte 8)` vector (the bare `TYPE_I8ARR` array, [[packed-integer-vectors]]) crosses as
RAW bytes -- no UTF-8 in either direction, because the `:string` decoder is non-validating and
hands back garbage code points for arbitrary binary. **`:string` is a value, `:bytes` is a
transfer.**
- A PARAMETER stages as `(ptr,len)` and is bump-ALLOCATED, so several coexist -- the same
  staging a memory-typed parameter gets (above), sharing one mark and one restore with it.
- A RESULT is the `read(2)` shape: the Lisp signature gains ONE trailing parameter (the receive
  buffer; `WasmImportDirective.lispParamCount` / `WasmImportCompiler.lispArity`, from which every
  backend's stub arity derives), the host is called with a trailing `(ptr, cap)` and answers the
  value's FULL length (undersized buffer = retry, never truncation), and the wrapper copies
  `min(n,cap)` back and POPS the heap to its entry mark -- so a pull loop over one reused buffer
  keeps linear memory flat. Same convention on the export side.
- Three helpers, gated on the designator appearing (`bytesHelpersRideOnlyABytesDeclaringModule`):
  `_bytes_from_mem`, `_bytes_copy`, `_bytes_fill`, sharing one appended
  `((ref null eq),i32,i32)->i32` signature at the abiTypeBase block (`WasmExportRuntimeBuilder`).
- Modes: GC core modules only. `--component` refuses eagerly (no `list<u8>` lift yet) and `--no-gc`
  refuses (no arrays).
- E2E is a JS host on node (`WasmBytesBoundaryE2eTest`): `ff fe 41` exact in all four directions,
  full-length answer on an undersized buffer, and the flat-memory loop. The wasmtime preload leg
  pins the plumbing through the lengths.

**First consumers are the reactor's two bodies** (the third is `--host-fetch`'s reply body):
`HttpReactorInliner` synthesizes `%reactor-read-body` (`:returns :bytes :async t`) and
`%reactor-write-body` (`:params '(:bytes) :returns :void :async t`) beside the `handle-request`
export. **The two spellings are the same rule, not an asymmetry**: a chunk crossing IN is a
`:bytes` result into a caller-passed buffer, one crossing OUT is a `:bytes` parameter the wrapper
stages and pops, and both say the caller owns the memory (which is why the write import answers
nothing). [[clack]] ("The WASM boundary") has the whole shape, including why each thunk CALLS its
import instead of taking `#'name`, and why a plain WASI COMMAND module keeps the in-band bodies
(its host is `wasmtime run`, which satisfies no `env.*` import).

## The component path does NOT go through this compiler
A `rontolisp:wit-import` under `--component` lowers to `rontolisp::%component-import`, which
`WasmComponentImportCompiler` turns into canonical-ABI marshalling defuns -- a different compiler,
but the SAME synthetic-defun + `PLACEHOLDER_FUNC_BASE` + `WasmImportInjector` mechanism, sharing
one ordinal space. See [[wit]] ("Component imports").

## Tests and showcases
`WasmImportCompilerTest` (import-section order, index shift, allocator gating, mode rejection);
preload E2E in `WasmLispCompilerIntegrationTest` (`wasmtime run --preload host=... main.wasm`);
stubs in `LispEvaluatorTest`/`JvmLispCompilerTest`. Showcases: `examples/browser/webgl-triangle/`
(10 imports, no exports), `webgl-cube/`, `webgl-galaxy/` (32 imports, GLSL as `:string` params,
`:string` results); cube, galaxy, heat3d and robot-arm pull the WebGL2 boundary from
`examples/browser/webgl-common/gl.lisp`.
