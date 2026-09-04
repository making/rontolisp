# `rontolisp:wasm-import` (host functions callable from Lisp) + export `:as` aliases

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(T...) :returns T)`
declares a host function (JS import object key `module`, property `field`; wasmtime
`--preload module=...`) and makes it callable from Lisp like a top-level defun. Type
designators are shared with `WasmExportCompiler`
(`:int`/`:float`/`:bool`/`:string`/`:s-expr`/`:bytes`, `:void` return). Generic parsing:
`compiler/WasmImportDirective` (shared with the JVM backend); WASM validation/codegen:
`WasmImportCompiler`.

## How the fixed-index invariant survives adding imports
The WASM spec puts all imported functions before all defined ones, so a new import would
shift every `FUNC_*` constant. Instead each import becomes a **synthetic defun** (registered
in Pass 1 with a marker body; Pass 2a reserves a `userFunctionBodies` slot, filled after the
lambda pass once `_str_from_mem`'s index is known). The wrapper unboxes each arg
(`castI31GetS`, `castFloatGetF64`, string ptr/len via `WasmExportCompiler.emitStringResult`),
emits `call (PLACEHOLDER_FUNC_BASE = 1<<27) + ordinal` (writeUnsignedLeb128), then boxes the
result. Because it IS a defun, `#'name`/`funcall`/`mapcar`/dispatch/`eval` work with no extra
wiring. Host-ABI func types are appended after the export wrapper types
(`TYPE_P1_FUTURE + 1 + numExports + j`).

`am.ik.wasm.WasmImportInjector` (reusing `WasmTreeShaker`'s package-private scanners) then
rewrites the finished module: prepends the import entries at the FRONT of the import section
(indices 0..K-1; creates the section before the function section under `--no-wasi`), remaps
every `call`/`ref.func` immediate (`>= placeholderBase -> ordinal`, else `+K`), and shifts
export/start-section function indices. Runs before `WasmTreeShaker.shake`. **The
pre-injection module is invalid (calls to 2^27) -- never validate or emit it directly.**

A RUNTIME BUILDER may bind one through the same placeholder encoding: `--host-random`'s
entropy import (`buildNoWasiHostRandomGetBody`) and the `_argv` helper's
`args_sizes_get`/`args_get` pair (`WasmArgvRuntimeBuilder`, `%host-argv` on Preview 1,
`.kb/uiop.md`) are appended to `hostImports` LAST, after the directive-declared slots, so a
program that also writes `rontolisp:wasm-import` keeps its ordinals and bytes -- which is what
lets a WASI function be added without touching the eleven index-pinned preview1 import slots.

## Modes and other backends
`--component` and `--no-gc` throw a clear `UnsupportedOperationException`. Interpreter
(`Environment`) and JVM (`JvmLispCompiler` pass 1 synthesizes a `(defun name (...) (error ...))`
stub via `WasmImportDirective`; the directive itself is an `ACONST_NULL` no-op in
`JvmExprCompiler`) define error-signalling stubs so shared sources load everywhere. An import
with a `:string` result forces the `__ronto_alloc`/`_str_from_mem` helper pair on (flag
`memoryHelpers`); an `:s-expr` result forces `usesRead`.

**Export aliases**: `wasm-export` takes `:as "alias"` (string or quoted symbol);
`Decl.exportName()` defaults to the Lisp name. Used by the GC backend export section and
`NoGcWasmCompiler`.

## Directives inside a user `defpackage`
Unlike ordinary quoted data (which `PackageResolver` leaves untouched), the quoted NAME
argument of both directives IS package-resolved (`PackageResolver.resolveWasmDirective`), so
`(rontolisp:wasm-import 'create-shader ...)` under `(in-package gl)` registers the synthetic
defun as `gl:create-shader` (or `gl::name` unexported), matching what call sites canonicalize
to; an explicitly qualified name is a fixed point. Only the name argument is special --
`:params` and a lenient quoted-symbol `:as` stay under the quote exemption. The host-facing
default (`:as` omitted) is the bare MEMBER name, never the qualified spelling
(`WasmImportDirective`/`WasmExportCompiler.unqualifiedMember`).

`examples/browser/webgl-common/gl.lisp` holds the hand-written `defpackage gl` (enum constants
+ `gl:make-shader`/`gl:build-program`), spliced into each webgl demo by a compile-time
`(require :gl "../webgl-common/gl.lisp")`; `--optimize` shakes entries a demo never calls.
**Caveat**: a program that takes functions as values (e.g. via the spliced linalg library)
keeps same-arity import wrappers reachable through the funcall dispatcher -- webgl-heat3d's
module still imports `disable`/`depthMask` it never calls.

## It is also a LOWERING TARGET (`.kb/wit.md`)
On Preview 1 a `(rontolisp:wit-import "gl.wit" :interface "local:webgl/gl" :package gl)`
expands to exactly one directive per WIT function -- same `:from`/`:as`/`:params`/`:returns`
shape, same synthetic-defun mechanism, same injector -- so the module is byte-identical to the
hand-written block. `:from` defaults to the interface's bare name; the WIT label becomes the
`:as` field camelCased (`:field-style :camel`, the default) or verbatim (`:kebab`). Only
`:int`/`:float`/`:bool`/`:string` are reachable from a WIT type (nothing maps to `:s-expr`), so
a WIT type outside that flat set is a compile error naming the WIT file and line -- the
interpreter/JVM lowering has no such limit (there a `wit-import` lowers to a provider call).

The WebGL demos took that path: `examples/browser/webgl-common/gl.wit` is a `local:webgl`
package with `interface gl` (29 WebGL2 entries) and `interface ui` (the one `fail` the shader
helpers report through -- **one directive binds one interface into one module**, so the `ui`
module import needs its own interface). Four functions whose Lisp name used DIFFERENT WORDS
from the host field were renamed to the WIT label (a label is one name serving as both):
`get-shader-parameter`, `get-shader-info-log`, `get-program-parameter`,
`get-program-info-log`. GL objects cross as `type shader = s32` handle ALIASES -- structurally
plain s32, but the name tells a reader and the JS generator which integers are table handles.
The pages' import object is GENERATED from the same gl.wit (`gl-imports.js`, pinned by
`GlImportObjectTest`, which reuses `WitImportDirective.FieldStyle.CAMEL` so the JS field names
cannot drift from the lowering).

## Asynchronous hosts: JSPI
An async host function fits behind this synchronous boundary: an import wrapped in
`WebAssembly.Suspending` with the module entered through `WebAssembly.promising` parks the wasm
stack until the promise settles. workerd has it unflagged (`wrangler dev` and deployed); node
24 needs `--experimental-wasm-jspi`. **Two obligations on a host of a suspending import**:
1. a suspending import may only be called on a stack entered through `promising`, so the
   `_initialize` load path must never reach one;
2. suspending RE-ENTERS the module -- a second request can call in while the first is parked,
   sharing globals and the `__ronto_alloc_mark`/`_reset` bracket (whose marks are LIFO and
   cannot nest across interleaved requests).

`--host-fetch` discharges (1) for fetch by lowering `rontolisp:fetch` onto an injected
`env.fetch` import with a DERIVED envelope (`.kb/fetch-http.md`).

### `:async t` -- a suspending host import is a future
`(rontolisp:wasm-import 'host-fetch ... :async t)` declares the host may SUSPEND; the call then
returns a FUTURE that `rontolisp:await` resolves. The word matches export-side `:async` (WIT
spells both directions `async func`; the directive carries the direction). On this backend the
future is DELIBERATELY DEGENERATE -- JSPI blocks the wasm stack, so the wrapper wraps the boxed
result in a settled kind-2 `TYPE_P1_FUTURE` (the `%async-run` struct;
`WasmImportCompiler.buildWrapperBody` pushes the kind under the unboxed args). started ==
settled is the documented CONTRACT here, like `--host-fetch`'s fetch future settling at the
reply's HEAD; the option buys one source reading the same on every backend, not concurrency.
`:async` takes literal `t`/`nil` only, so interpreter/JVM stubs load it unchanged. A
`wit-import`ed `async func` member lowers to exactly this on Preview 1
(`WitImportDirective.wasmImportForm`), which makes `futurep` agree on all four backends.

What the declaration buys the BUILD (`compiler/SuspendingImports`, riding
`NoWasiLoadPathRefusals.walk` with a `rootFunction` seed):
- a standing obligation warning naming each `module.field` (Suspending wrap + promising entry
  + serialised calls; a synchronous host is equally valid);
- a listing of WHICH exports can reach a suspending import (per-export walk; an import taken as
  `#'value` widens it to "ANY export" -- the walk follows calls, not values);
- under `--no-wasi` only, an ERROR when the LOAD PATH reaches one: `_initialize` runs on a
  stack no `promising` entered, and a suspension there is a TRAP no handler covers. A WASI
  command module keeps the warning and refuses nothing.

Pins: `SuspendingImportsTest`, `WasmImportCompilerTest.parsesAsyncOption`,
`WitImportDirectiveTest.lowersAnAsyncFuncMemberWithAsyncTOnPreview1`, the
`asyncImportAnswersASettledFutureThatAwaitResolves` preload E2E. "A synchronous host is equally
valid" is pinned as ONE MODULE driven both ways: `WasmReactorBodyE2eTest` (synchronous) and
`WasmReactorStreamingHostE2eTest` (`WebAssembly.Suspending` over a `ReadableStream`,
`promising` entry, chunks on the macrotask queue) put the same `rontolisp:http-handler` program
through the same pipeline and expect the same answers.

## `--emit-js-glue` -- the host half is written, not described
`compiler/HostGlueEmitter` writes one ES module beside the `.wasm` (`out.wasm` -> `out.js`).
`WasmLispCompiler` builds a `HostGlueEmitter.Surface` from facts it already settled -- the
parsed import/export declarations, the helper exports the module carries
(`memoryHelpers`/`hostArena`/`seedRandom`/`setTime`), and the same `SuspendingImports` answers
the obligation lines print -- and `hostGlueJs(fileName)` emits from it; nothing is re-walked or
read back out of the bytes.

Emitted: the import object (one key per `:from`, one property per `:as`), the `(ptr, len)`
staging of every memory-typed value in both directions, the `__ronto_alloc_mark`/`_reset`
bracket around a call with the result decoded BEFORE the pop, the
`__ronto_seed_random`/`__ronto_set_time`/`_initialize` startup order, the
`WebAssembly.Suspending` wrappers, the `WebAssembly.promising` entry for exactly the exports
the build lists, and the one-call-at-a-time queue.

**Left to the host**: one plain function per import over ordinary JS values -- a `:string`
arrives decoded, a `:bytes` parameter as a `Uint8Array` copy, and a `:bytes` RESULT is answered
with CHUNKS (`null` ends them) because the generated cursor holds whatever did not fit. Every
cursor is dropped at the next entry.

**Which entries suspend is the HOST's answer, not the declaration's.** `:async t` says the
module TOLERATES a suspension there. On node 24 JSPI an import answering SYNCHRONOUSLY through
a `Suspending` still parks the stack and returns to the event loop, so wrapping every
declared-tolerant import buys suspension points nobody asked for. The generated file therefore
exports `suspending(fn)`: a host marks its own entries, one mark switches the file into its
JSPI shape (`promising` + queue + promise-answering entry points), no marks leaves the SAME
file driving a synchronous host, and an unmarked entry answering a promise is reported by name
at instantiation (`AsyncFunction`) or at the call. Two facts the emitter needs beyond the
directives: `--host-random`'s `env.random_get` is IMPLEMENTED rather than asked for, and the
promising list is `SuspendingImports.reaches` widened to the `FETCH` kind.

**`reaches` follows CALLS**, and the reactor hands its body bridges over as `#'name`
(`HttpReactorInliner`), so no reactor export was ever listed. The walk takes a `followValues`
flag (`NoWasiLoadPathRefusals.walk`), true ONLY for the reachability question: `#'name` and a
literal lambda body count as reached, with no argument shapes carried in, because it is a MAY
analysis and under-reporting there is a missing `promising`. The load-path report says what
actually RUNS and never follows one.

### Traps the generated file must avoid (none visible from a passing build)
- **An EXPORT never becomes a local.** An entry point is a PROPERTY of the returned object; the
  two locals an export declares are `entry$name`/`make$name`, because an export called `call`
  or `bind` would sit beside the helper of that name and make the file a `SyntaxError`. A name
  that is not a bare JS identifier (`:as "new"`, `"do.it"`, `"2do"`) is REFUSED with the alias
  to change, and so is an import whose `:from`/`:as` is not one.
- **Two `wasm-import`s on one `(module, field)` are refused unless identical.** The core module
  collapses them onto ONE slot, so only the last shape would survive the object literal and
  silently unpack every caller of both (`:string` and `:bytes` share a core signature, so the
  module stays valid).
- **`serially` always takes the queue**, even when nothing is marked suspending: the work it
  runs AWAITS, so a second request lands inside it and moves per-call state under the first.
  Only a bare entry point skips the queue when nothing can suspend.
- **A read remainder belongs to its ARGUMENTS and to the call that asked for it.** The cursor is
  dropped at every module entry and whenever the arguments change; a host whose SOURCE moves
  INSIDE one call drops it itself with `lisp.drop(key)` (dog-fetcher's `env.fetch` does, because
  a second fetch replaces the reply the glue still holds). Pinned by
  `WasmHostGlueE2eTest.aHostWhoseSourceMovesInsideOneCallDropsWhatTheGlueStillHolds`.
- **The header describes what was actually emitted**; the marking protocol is advertised only
  when a suspension can reach an entry point.
- **`--emit-js-glue` refuses to overwrite a `.js` it did not write** (marker
  `HostGlueEmitter.MARKER`) -- `-o src/index.wasm` in a Worker directory aims at a hand-written
  `src/index.js`. A side-artifact flag without `-o` is an error, not a silent interpretation.
- **Two reachability holes, both a MISSING `promising`**: `followValues` walked `(lambda ...)`
  but not `#'(lambda ...)`, and the per-export walk is seeded from that export alone, so a
  function value handed over elsewhere (`(defvar *f* #'helper)` funcalled from the export) was
  invisible. `anyTakenAsValue` now asks whether anything that REACHES an import escaped, and
  widens to "any export" when so.

**A divergence to know**: `:bytes` as a RESULT is DECLARED as "the host answers the value's FULL
length, an undersized buffer is a retry", and the generated glue implements the STREAM reading
of the same shape instead (chunks, cursor holds what did not fit, count is what was written).
Both real users (`readRequestBody`, `readResponseBody`) are streams. A host wanting the retry
convention writes that import by hand.

**A latent gap the generator surfaced**: `WasmImportCompiler.usesStrFromMem` named only
`:string`, so a module whose ONLY memory-typed boundary was an `:s-expr`-RETURNING import
exported no `__ronto_alloc`, and no host could answer it (`TypeError: ex.__ronto_alloc is not a
function`). The predicate now names both; pinned by
`WasmImportCompilerTest.sexprResultExportsTheAllocatorToo`.

Host state belonging to ONE call cannot be set beside the call, so the generated object exposes
the critical section: `serially(work)` runs `work` in the queue and hands it entry points that
enter directly. `examples/cloudflare-workers/dog-fetcher` is the worked example (`src/worker.js`
generated and CHECKED IN; `src/index.js` is three lines). Gated to `--no-wasi` core modules (a
component is instantiated through jco; a `--no-gc` module imports nothing). Pins:
`HostGlueEmitterTest` (every checked-in `src/worker.js` byte-for-byte against what its shape
emits -- four shapes over nine files, plus the promising selection, the no-import shape, the
`--host-random` entry and the name-collision refusal), `RontoLispCliTest`, `WasmHostGlueE2eTest`
(node 24 JSPI).

## `--host-boundary` -- WHICH boundary, and what each costs
`compiler/HostBoundary`. A MODULE decision (it changes the import list), so a flag of its own;
`--emit-js-glue` stays a boolean and REFUSES a value by name.

| | `envelope` (DEFAULT) | `streaming` |
| --- | --- | --- |
| imports | `env.fetch` under `--host-fetch`, else NOTHING | `env.readRequestBody`, `env.writeResponseBody`, +`env.readResponseBody` and `env.fetch` under `--host-fetch` |
| host state | none | one cursor per reading import, plus whatever holds its source |
| binary body | DESTROYED -- `ff fe 41` in, `ef bf bd ef bf bd 41` out | crosses exactly |
| large body | copied, memory proportional | linear memory flat |
| streamed upstream reply | buffered first | forwarded chunk at a time |
| generated host | `instantiate` + `defaultHost()` + `worker(module)`, the SAME on both | same |
| module size | within ~1% either way, sign not stable (`size-report/results/cloudflare-workers.md`) | |

**The DEFAULT is `envelope`** -- a default IS the recommendation for everyone who does not read
the guide. **What that changes**: every `--no-wasi` reactor rebuilt without the flag changes
shape; for a BINARY body, measured destruction -- `ff fe 41` arrives as `ef bf bd ef bf bd 41`
(two U+FFFD, the JSON text round trip) with `content-length` still saying three, and nothing
reporting it. `--host-boundary=streaming` gets the old module back byte-for-byte. The five
`httpbin*` Worker examples ECHO request bodies and say so in their `build.sh`; the three
`hello*` ones read no body and stay on the new default.

**It is not a size decision -- do not quote a bound.** Which sign it lands on is a property of
the program and of the tree shaker, which is why `size-report` measures both rows. The STATE is
the point: both defects the glue review turned up on this surface were state-lifetime bugs, and
an envelope host has no cursor to outlive anything.

**What the emitter can WRITE on BOTH boundaries**: the halves a reactor's boundary leaves are
fixed by the transport, so `Surface` carries `derivedFetch` and `envelopeExport` and the file
emits `defaultHost()` (the `env.fetch` host half, from `FetchResponseShape` in both directions,
error arm included) and `worker(module, options)` (a `Request` onto
`ReactorEnvelope.REQUEST_KEYS`, a `Response` off `RESPONSE_KEYS`, the instance created on the
first request and retired if a call traps, the queue taken when the fetch can suspend). A
Worker is `export default worker(module)` and nothing else. `options.host` is laid over the
derived entries one at a time, so a host replacing `env.fetch` keeps the rest of `env`.
`defaultHost(lisp)` takes a thunk answering the instance because ITS cursor is the one a second
fetch inside one call supersedes (`lisp.drop`); the two body imports live in `worker()` rather
than `defaultHost()` because they are per-CALL state and the call is `worker()`'s.

**Two facts derived from the IMPORTS rather than the flag.** `WasmLispCompiler` reads
`derivedFetch` off "`--host-fetch`, the program really calls `rontolisp:fetch`, `env.fetch`
imported, `env.readResponseBody` not" and `envelopeExport` off "an export that is BOTH the
synthesized bridge defun and the transport's own export name". A flag is a request; what the
module imports is the answer. Both fingerprints need both halves: `env.fetch` by module+field
alone matches a program's OWN import of that name, and `%reactor-dispatch` by member name alone
matches a function a program happened to spell that way. Consequence:
`examples/cloudflare-workers/httpbin`, which exports `handle-request` by HAND, gets no
`worker()`.

The seven reactors that go through `clack:clackup` (`hello-*` on envelope, `httpbin-*` on
streaming) pass `--emit-js-glue` and their `src/index.js` is the `worker(module)` call. The four
`httpbin-*` pass `remoteAddr: (r) => r.headers.get("cf-connecting-ip")` -- which header carries
the client address is exactly what `worker()` leaves to its caller. One behavioural difference
of the generated shape: those four now enter through `serially`, so a request takes one promise
hop and module calls are ordered by the queue instead of the isolate's single thread.

**`ReactorEnvelope` (in `compiler`) holds the envelope's names** -- the bridge defun, the export
name, six request keys, three response keys, the `env` module and the two body fields -- because
three packages that may not import each other need them (`eval` synthesizes, `codegen.wasm`
recognises, `HostGlueEmitter` maps). `FetchResponseShape` gained the `env.fetch` field names for
the same reason. `ReactorEnvelopeTest` pins both key lists against `http-reactor.lisp` in both
directions (the response one scoped to `%http-reactor-envelope`, since the 500 arm's `:error`
rides the error DOCUMENT's plist and is not an envelope key).

**`rontolisp-body-imports`** (`reader/Features.BODY_IMPORTS`) guards a hand-written reactor's
own body imports, present exactly where those imports exist. It replaced
`#+(and rontolisp-reactor (not rontolisp-component))`, which enumerated targets instead of
naming the imports (silently including `--no-gc`, which has no packed-array representation for
`:bytes`, and unable to follow a flag). Consumer:
`examples/cloudflare-workers/httpbin/worker.lisp`. Pinned by
`RontoLispCliTest.theStreamingBoundaryReadsTheSourceWithTheBodyImportsFeature` -- the only shape
that CAN pin it, since no Java reads the feature back.

### Traps in the generated `worker()`
- **The instance is bound at admission, so `poisoned` must be re-read INSIDE the critical
  section.** `live().serially(...)` evaluates `live()` before the queue admits anything, so a
  queued request holds the instance an earlier parked call is about to trap. Measured: after a
  host import throws across the boundary, the two requests behind it answered 200 with the
  trapped call's special binding still shallow bound. The module's own re-entry guard does not
  save them -- the EH landing pad CLEARS it on exactly the path that poisons the instance.
- **The WHOLE handler belongs in the try.** Guarding only the module call let
  `await request.arrayBuffer()` on an aborted upload and `new Response` on a status/header the
  application may produce (0, 999, a newline in a value) escape as an unhandled rejection.
  `poisoned` is set only when the call ENTERED the module.
- **`new Response("", { status: 204 })` is a TypeError.** The envelope always carries the
  `"body"` key without a sink, so an ordinary 204 came back as a logged 500; the mapping answers
  `head.body || null`.
- **The decoder must `ignoreBOM`.** These octets are a VALUE; the default decoder deletes a
  leading U+FEFF, shortening a BOM-prefixed body while `content-length` still counts three.
- **An options hook is awaited** -- an async `remoteAddr` crossed as `{}`.
- **The three-line sketch is only written when the file answers EVERY import.** A `--host-fetch`
  build whose only fetch sits on the LOAD path imports `env.fetch` while no export is promising,
  so no host half is written and `worker(module)` 500s on every request; the sketch says
  `worker(module, { host })` there.

Additional pins: `RontoLispCliTest` (each refused mode name; the flag refused on every output
shape with no choice; one source compiled twice, only the streaming build declaring the body
imports), `HostFetchLibraryTest`, `WasmHostGlueE2eTest` (`worker(module)` against a real
`node:http` upstream: GET, POST body through the envelope, `remoteAddr`, two overlapped
requests).

## The re-entry guard -- a module that can suspend refuses interleaved calls
A parked JSPI call returns control to the event loop, so a second call can enter an export while
the first is suspended, and NOTHING in the module owns its state per call: the
`__ronto_alloc_mark`/`_reset` marks interleave (they do not nest), the shallowly-bound specials
share one module-global cell (`.kb/dynamic-special-variables.md`), and a `(ptr,len)` result sits
at un-advanced `HEAP_PTR` scratch. Measured on node 24 JSPI, BOTH corruptions are silent wrong
bytes: a special read back after the resume answers the OTHER call's binding, and the second
resume's result copy overwrites the first's still-unread `(ptr,len)`.

So a module that CAN suspend (any `:async t` import, or `--host-fetch` with `rontolisp:fetch`
actually used) carries a guard global -- a `mut i32`, THIRD FROM LAST so the cached-t/raw
sentinel stay the last two (`reentryGuardGlobalIndex`, emitted only when the module also exports
something). EVERY export wrapper checks-and-sets it on entry
(`global.get; if; unreachable; end` -- a TRAP, because at export entry no Lisp handler can be
active and output on a reactor is a sink, so the build warning is where the message lives) and
CLEARS it on every return, including the hand-rolled `catch_all` landing pad in EH mode
(`WasmExportCompiler.emitReentryGuardStore`), so a host that catches a Lisp-error trap and then
calls SEQUENTIALLY is not refused. A serialising or synchronous host never sees it; a
non-serialising host gets `RuntimeError: unreachable` at the second entry with the FIRST call
completing correctly. A module that cannot suspend gains no guard, no global, no instruction
(byte-identity verified across P1/no-wasi/component). Utility exports (`__ronto_alloc*`, the
seed/clock hooks) stay unguarded. Pins:
`WasmImportCompilerTest.aSuspendingImportGuardsEveryExportAgainstReentry` /
`hostFetchGuardsExportsExactlyWhereFetchIsUsed`, and the
`aGuardedExportAnswersThroughASynchronousHost` preload E2E. The guard is the DEFAULT;
`--reentrant` is the opt-in that retires it.

## `--reentrant` -- overlapped calls on ONE instance
Measured motivation: 8 concurrent 100 ms round trips took 803 ms serialised and 239 ms as
instance-per-request (~17-37 ms `_initialize` per call, plus a 16 MiB GC pre-grow and ~2 MiB
linear memory PER instance); `--reentrant` answers them in ~125 ms on one instance. Target
workload: I/O-bound AND unable to afford an instance per request. All of it is `reentrant`-gated
so every other module stays byte-identical (`WasmReentrantCompilerTest`).

- **Per-task dynamic store** (`codegen.wasm/WasmDynVars`,
  `.kb/dynamic-special-variables.md`): the JVM `_d$` hybrid ported -- only
  `SpecialVarCollector.collectDynamicallyBound` names get a slot in a per-call TASK RECORD (a
  `TYPE_HASH_BUCKETS` of nullable `TYPE_CELL`s in a module global), created by every export
  wrapper on entry (and by `_start` for the load path), saved into a wrapper local and restored
  around the ONE place another extent can run -- the suspending host call in the import wrapper.
  Reads are dynamic-first with the module global as the default; binds/`setq`/exit-restores keep
  the shallow path's save/restore discipline and its unwind limitations. Under-collection is a
  compile-time throw at the binding site (the JVM rule).
- **Park-block allocator** (`WasmExportRuntimeBuilder.buildParkAllocBody`, exported as
  `__ronto_park_alloc`/`__ronto_park_free`): the arena's absolute mark/restore is what two
  interleaved extents cannot share, so staging that must SURVIVE A PARK moves into first-fit
  free-list blocks carved permanently off the bump heap (never split, never coalesced -- steady
  same-size overlap recycles perfectly, which keeps TWO interleaved 64 KiB pull loops
  memory-flat). Everything else stays on the `HEAP_PTR` scratch stack, made park-safe by one
  clamp: `__ronto_alloc_reset` (and the wrappers' own restores) never go below `PARK_FLOOR_ADDR`,
  the top of the newest carve. ABI consequences (stated by the obligation lines, written by the
  glue): a `:string`/`:s-expr` EXPORT result crosses as a park block the READER frees; a
  `:string`/`:s-expr` IMPORT result must be park-written by the host and is freed by the
  wrapper; a `:bytes` receive buffer a host passes into an export must be park-allocated; the
  glue's argument bracket pops SYNCHRONOUSLY at the entry call.
- **The glue** drops the queue and `serially`, keeps the `suspending()` marking protocol and the
  promising selection, and `worker()` calls the entry directly. Bytes-reader cursors keep their
  keyed-by-arguments shape; two overlapped calls pulling one source through IDENTICAL arguments
  are the host's own hazard.
- **Refusals**: a program nothing can suspend; `--component` (its concurrency is the component
  model's); `--dynamic` (the eval mirror is per-instance state the task record does not cover);
  and an ID-LESS streaming body import (a hand-written reactor's own, e.g.
  `httpbin/worker.lisp`'s).

### The streaming body protocol composes, by carrying a CALL IDENTITY
Under the flag `HttpReactorInliner` / `HostFetchLibrary` synthesize every body import with a
leading `:int` id -- `env.readRequestBody(id, ptr, cap)`, `env.writeResponseBody(id, ptr, len)`,
`env.readResponseBody(id, ptr, cap)` -- so the no-handle argument is SCOPED rather than relaxed:
wherever the id-less protocol exists the guard or queue still holds (every serialised build stays
byte-identical), and the compiler refuses an id-less `env.*` body import under `--reentrant`.

Three identities, two mints: the REQUEST's id is minted by the glue's `worker()` per request and
rides the envelope's `"call-id"` key (`ReactorEnvelope.CALL_ID_KEY`, in `REQUEST_KEYS`, absent
everywhere else); the transport reads it where the envelope is parsed and closes it over the body
thunks (`%http-reactor-bind-source` / `-bind-sink`), so everything downstream stays on the
0-arity source. A fetch REPLY's id is its OWN, not the request's (a second fetch inside one call
must not have to supersede the first): `defaultHost()` mints one per fetch, returns it in the
reply head's reserved `"body-id"` key (`FetchResponseShape.HOST_BODY_ID_KEY`), keeps one reader
per reply (dropped when drained; `lisp.drop` and the "superseded" counter are gone from this
shape), and the module's drain pulls by it. The reentrant glue keys all per-call state by id:
`worker()`'s request/response body maps are retired in a `finally`, and the `:bytes` reader's
REMAINDER becomes a map keyed by the pull's arguments (the serialised single-slot cursor dropped
the other call's leftover octets on every alternation -- silent mid-chunk loss under overlap).

Pins: `WasmReentrantCompilerTest` (id-less refusal + composed build), `HttpReactorInlinerTest` /
`HostFetchLibraryTest` (the synthesized shapes),
`RontoLispCliTest.theStreamingBoundaryComposesWithReentrant`. `dog-fetcher` stays streaming +
serialised (the controlled comparison with `btc-ticker` is worth keeping); the composed shape
ships as `examples/cloudflare-workers/dog-relay` (its `src/worker.js` is the fifth
`HostGlueEmitterTest` pin). Known limit it surfaced: a fetched reply relayed as-is is TEXT
(`:body` is a character stream on every backend), so a binary upstream body does not cross
byte-exact.

Gates, all `WasmReentrantE2eTest` (node 24 JSPI): the re-entry reproduction with its expectation
INVERTED (two overlapped calls binding one special across a suspend each read their own binding
back); overlapped `:string` boundaries exact in both directions; two interleaved 64 KiB pull
loops with `memory.buffer.byteLength` unchanged; 8 concurrent upstream round trips through ONE
envelope worker in ~one round trip (bounded < 500 ms against a 100 ms upstream); and two
overlapped streaming requests (one binary `ff fe 41`, one text) each answering its OWN echo.

## `:bytes` -- the byte-TRANSFER type
An `(unsigned-byte 8)` vector (the bare `TYPE_I8ARR` array, `.kb/packed-integer-vectors.md`)
crosses as RAW bytes -- no UTF-8 in either direction, because the `:string` decoder is
non-validating and hands back garbage code points for arbitrary binary (`ff fe 41` -> code point
0x1FE062). **`:string` is a value, `:bytes` is a transfer.**

- A PARAMETER stages as `(ptr,len)` like a string, but bump-ALLOCATED (not at un-advanced
  scratch), so several can coexist.
- A RESULT is the `read(2)` shape: the Lisp signature gains ONE trailing parameter (the receive
  buffer vector; `WasmImportDirective.lispParamCount` / `WasmImportCompiler.lispArity`, from
  which every backend's stub/wrapper arity derives), the host is called with a trailing
  `(ptr, cap)` pair and answers the value's FULL length (undersized buffer = retry, never
  truncation), and the wrapper copies `min(n,cap)` back and POPS the heap to its entry mark (a
  plain `HEAP_PTR` store, safe because nothing between mark and restore can intern) -- so a pull
  loop over one reused buffer keeps linear memory flat (gate: 10000 pulls staging 64 KiB each).
- Same convention on the export side: a `:bytes`-returning export's core signature gains the
  trailing `(ptr,cap)` and returns the full length as its single i32.
- Three helpers, gated on the designator appearing (everything else byte-identical, pinned by
  `bytesHelpersRideOnlyABytesDeclaringModule`): `_bytes_from_mem` (fresh vector from linear,
  reuses TYPE_RAT_NEW), `_bytes_copy` (vector -> mem, returns full length), `_bytes_fill`
  (mem -> vector, returns n), sharing one appended `((ref null eq),i32,i32)->i32` signature at
  the abiTypeBase block (`WasmExportRuntimeBuilder`).
- Modes: GC core modules only. `--component` refuses eagerly (no `list<u8>` lift yet; the refusal
  names it) and `--no-gc` refuses (no arrays).
- Content round-trip can only be proven against a host sharing the module's memory, so the E2E is
  a JS host on node (`WasmBytesBoundaryE2eTest`, node-gated): `ff fe 41` exact in all four
  directions, full-length answer on an undersized buffer with no overrun, and the flat-memory
  loop. The wasmtime preload leg (`bytesBoundaryCrossesThePreloadBoundaryByLength`) pins the
  plumbing through the values that DO cross two disjoint memories -- the lengths.

**First consumers are the reactor's two bodies** (the third is `--host-fetch`'s reply body,
`.kb/fetch-http.md`): `HttpReactorInliner` synthesizes
`(wasm-import '%reactor-read-body :from "env" :as "readRequestBody" :params '() :returns :bytes :async t)`
and
`(wasm-import '%reactor-write-body :from "env" :as "writeResponseBody" :params '(:bytes) :returns :void :async t)`
beside the `handle-request` export, so the head crosses as the JSON envelope and both bodies
cross as octets. **The two spellings are the same rule, not an asymmetry**: a chunk crossing IN
is a `:bytes` result into a caller-passed buffer, one crossing OUT is a `:bytes` parameter the
wrapper stages and pops, and both say the caller owns the memory (which is why the write import
answers nothing -- a host cannot short-read a write). `.kb/clack.md` ("The WASM boundary") has
the whole shape, including why each thunk CALLS its import instead of taking `#'name` (the
suspending-import report follows calls), why `--component` keeps the in-band bodies, and why a
plain WASI COMMAND module keeps them too (its host is `wasmtime run`, which satisfies no `env.*`
import, so declaring one there made the module refuse to instantiate).

## The component path does NOT go through this compiler
`rontolisp:wasm-import` is Preview-1-only (`--component` throws). A `rontolisp:wit-import` under
`--component` lowers to the internal `rontolisp::%component-import` form, which
`WasmComponentImportCompiler` turns into canonical-ABI marshalling defuns -- a different
compiler, but the SAME synthetic-defun + `PLACEHOLDER_FUNC_BASE` + `WasmImportInjector`
mechanism, sharing one ordinal space with these imports. See `.kb/wit.md` ("Component imports").

## Tests and showcases
- `WasmImportCompilerTest` (structural: import-section order, index shift, allocator gating, mode
  rejection); preload E2E in `WasmLispCompilerIntegrationTest`
  (`wasmtime run --preload host=... main.wasm`, host module itself compiled from Lisp with `:as`
  aliases); stub tests in `LispEvaluatorTest`/`JvmLispCompilerTest`.
- Showcases: `examples/browser/webgl-triangle/` (10 imports, no exports, whole program in
  top-level forms run by `_initialize`; deliberately self-contained), `webgl-cube/` (mat4 math in
  Lisp, bulk floats via a `setFloat` staging array), `webgl-galaxy/` (32 imports, GLSL sources as
  Lisp `:string` params, handle-table one-liner JS bindings, `:string` results for shader info
  logs). cube, galaxy, heat3d and robot-arm pull the WebGL2 boundary from
  `examples/browser/webgl-common/gl.lisp`, and `webgl-common/gl-imports.js` is staged beside them.
