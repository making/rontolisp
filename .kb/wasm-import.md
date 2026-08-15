# `rontolisp:wasm-import` (host functions callable from Lisp) + export `:as` aliases

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(T...) :returns T)` is
the reverse of `wasm-export`: it declares a host function (JS import object key
`module`, property `field`; wasmtime `--preload module=...`) and makes it callable from
Lisp like a top-level defun. Type designators are shared with `WasmExportCompiler`
(`:int`/`:float`/`:bool`/`:string`/`:s-expr`/`:bytes`, `:void` return). Generic parsing
lives in `am.ik.rontolisp.compiler.WasmImportDirective` (shared with the JVM backend);
WASM-side validation/codegen in `WasmImportCompiler`.

**How the fixed-index invariant survives adding imports**: the WASM spec puts all
imported functions before all defined ones, so a new import would shift every `FUNC_*`
constant. Instead, each import becomes a **synthetic defun** (registered in Pass 1 with
marker body; Pass 2a reserves a `userFunctionBodies` slot, filled after the lambda pass
when `_str_from_mem`'s index is known). The wrapper unboxes each arg (`castI31GetS`,
`castFloatGetF64`, string ptr/len via `WasmExportCompiler.emitStringResult`), then emits
`call (PLACEHOLDER_FUNC_BASE=1<<27) + ordinal` (written with writeUnsignedLeb128), then
boxes the result. Because it IS a defun, `#'name`/`funcall`/`mapcar`/dispatch/`eval`
work with no extra wiring. Host-ABI func types are appended after the export wrapper
types (`TYPE_P1_FUTURE + 1 + numExports + j`).

The **`am.ik.wasm.WasmImportInjector` post-pass** (reuses `WasmTreeShaker`'s
package-private section/opcode scanners) then rewrites the finished module: prepends the
import entries at the FRONT of the import section (indices 0..K-1; creates the section
before the function section under `--no-wasi`), remaps every `call`/`ref.func`
immediate (`>= placeholderBase -> ordinal`, else `+K`), and shifts export/start-section
function indices. Runs before `WasmTreeShaker.shake` (`--optimize` composes; unused
imports are shaken like unused WASI imports). The pre-injection module is invalid (calls
to 2^27) — never validate/emit it directly.

Modes: `--component` and `--no-gc` throw a clear `UnsupportedOperationException`.
Interpreter (`Environment`) and JVM (`JvmLispCompiler` pass 1 synthesizes a
`(defun name (...) (error ...))` stub via `WasmImportDirective`; the directive itself is
an ACONST_NULL no-op in `JvmExprCompiler`) define error-signalling stubs so shared
sources load everywhere. An import with a `:string` result forces the
`__ronto_alloc`/`_str_from_mem` helper pair on (flag `memoryHelpers`, superset of the
old `exportUsesMemory`); an `:s-expr` result forces `usesRead`.

**Export aliases**: `wasm-export` gained `:as "alias"` (string or quoted symbol) —
`Decl.exportName()` defaults to the Lisp name; used by both the GC backend export
section and `NoGcWasmCompiler`.

**wasm-import/wasm-export inside a user `defpackage` (the shared `gl` package)**: unlike
ordinary quoted data (which passes through `PackageResolver` untouched), the quoted name
argument of both directives IS package-resolved — `PackageResolver.resolveWasmDirective`
resolves it like a defun name against the current package, so
`(rontolisp:wasm-import 'create-shader ...)` under `(in-package gl)` registers the
synthetic defun as `gl:create-shader` (or `gl::name` for an unexported symbol), matching
what call sites canonicalize to; an explicitly qualified name is a fixed point. Only the
name argument is special: the `:params` keyword list and a lenient quoted-symbol `:as`
alias stay untouched under the quote exemption. The host-facing default (`:as` omitted:
import field / export name) is the bare member name, never the qualified spelling
(`WasmImportDirective`/`WasmExportCompiler.unqualifiedMember`).
`examples/browser/webgl-common/gl.lisp` used to be the showcase for a hand-written
block; **it is now a wit-import consumer** (todo 132, below), so the showcase for the
hand-written form is `webgl-triangle` and each demo's own staging imports. gl.lisp still
holds the hand-written `defpackage gl` (the enum constants and
`gl:make-shader`/`gl:build-program` need one, and the directives bind into the current
package rather than naming one), spliced into each webgl demo by a compile-time
`(require :gl "../webgl-common/gl.lisp")`; `--optimize` shakes the entries a demo never
calls, so declaring the union is free. Caveat: a program that takes functions as values
(e.g. via the spliced linalg library) keeps same-arity import wrappers reachable through
the funcall dispatcher — webgl-heat3d's module still imports `disable`/`depthMask` it
never calls. That used to leak onto the page, which had to know to provide exactly those
two; since the pages spread the whole generated union it no longer does.

**It is now also a LOWERING TARGET** (todo 127, `.kb/wit.md`): on the Preview 1 backend a
`(rontolisp:wit-import "gl.wit" :interface "local:webgl/gl" :package gl)` expands to
exactly one of these directives per WIT function — same `:from`/`:as`/`:params`/`:returns`
shape, same synthetic-defun mechanism, same `WasmImportInjector` post-pass — so the module
is byte-identical to the hand-written block and `--optimize` still shakes the never-called
imports. `:from` defaults to the interface's bare name; the WIT label becomes the `:as`
field camelCased (`:field-style :camel`, the default) or verbatim (`:kebab`). Only
`:int`/`:float`/`:bool`/`:string` are reachable from a WIT type (nothing maps to
`:s-expr`), so a WIT type outside that flat set is a compile error naming the WIT file and
line — the interpreter/JVM lowering has no such limit, because there a `wit-import` lowers
to a provider call, not to an import.

**The WebGL demos took that path** (todo 132, done): `examples/browser/webgl-common/gl.wit`
is a `local:webgl` package with `interface gl` (the 29 WebGL2 entries) and `interface ui`
(the one `fail` gl.lisp's shader helpers report through — one directive binds one interface
into one module, so the `ui`-module import needs its own interface), and gl.lisp's 30
hand-written directives are now two `rontolisp:wit-import`s. **Every demo's module is
byte-identical to its pre-migration build** (measured, all six). The four functions whose
Lisp name used DIFFERENT WORDS from the host field were renamed to the WIT label — a label
is one name serving as both, so `shader-compiled-p`/`getShaderParameter` cannot both
survive: they are now `get-shader-parameter`, `get-shader-info-log`,
`get-program-parameter`, `get-program-info-log`. GL objects cross as `type shader = s32`
handle ALIASES: structurally plain s32 (so the lowering is unchanged), but the name is what
tells a reader — and the JS generator — which integers are table handles. The pages'
import object is GENERATED from the same gl.wit (`gl-imports.js`, pinned by
`GlImportObjectTest`, which reuses `WitImportDirective.FieldStyle.CAMEL` so the JS field
names cannot drift from the lowering); that, not the byte-identity, is what the migration
bought.

**An ASYNCHRONOUS host function fits behind this synchronous boundary — JSPI, and
workerd has it unflagged (2026-08-12)**: `examples/cloudflare-workers/dog-fetcher` is a
`--no-wasi` reactor whose outgoing HTTP is one `:string -> :string` import wrapped in
`WebAssembly.Suspending`, with the module's `handle-request` entered through
`WebAssembly.promising`; the wasm stack parks until the promise settles and resumes with
the result, so the Lisp calls it like any other function and nothing in the compiler
knows. That is what gives a reactor a way OUT at all — `rontolisp:fetch` is wasi:http and
a reactor imports no WASI — and it needed no flag and no compatibility-date opt-in, under
`wrangler dev` AND deployed (verified against the real dog.ceo, all five endpoints on
both; a plain-node probe of such a module stubs the import synchronously, or runs node
24 with `--experimental-wasm-jspi`, which has `Suspending`/`promising`). **Two
consequences a host of a suspending import has to honour**, both learned here: (1) a
suspending import may only be called on a stack entered through `promising`, so the
`_initialize` load path must never reach one; (2) suspending RE-ENTERS the module —
control returns to the event loop, so a second request can call in while the first is
parked, sharing the globals and the `__ronto_alloc_mark`/`_reset` bracket (whose marks are
LIFO and cannot nest across interleaved requests). The example serialises calls onto one
promise queue for that reason, measured at ~250 ms apart for eight concurrent upstream
round trips; overlapping them would need a per-call allocator scope, not a second mark.
The first consequence is now discharged for fetch: `--host-fetch` (todo-335, closed
2026-08-12) lowers `rontolisp:fetch` itself onto an injected `env.fetch` import with a
DERIVED envelope, so a Worker no longer declares this import by hand for HTTP -- the
dog-fetcher example above now writes `(await (fetch ...))` and its build prints the
promising/serialise obligation (`.kb/fetch-http.md` has the whole lowering). A USER
import says it suspends with `:async t` (todo-336, the section below); the re-entrancy
the mechanism creates is refused by the export-wrapper guard (todo-337, the section
below -- which is also what re-establishes the precondition of the shallow-binding
divergence in `.kb/dynamic-special-variables.md`).

**`:async t` -- a suspending host import is a future, not a secret (todo-336,
2026-08-12)**: `(rontolisp:wasm-import 'host-fetch ... :async t)` declares that the host
function may SUSPEND, and the call then returns a FUTURE that `rontolisp:await`
resolves. The word deliberately matches export-side `:async` (the stackful async lift):
WIT spells both directions `async func`, and the directive carries the direction. On
this backend the future is DELIBERATELY DEGENERATE -- JSPI blocks the wasm stack, so the
wrapper wraps the boxed result in a settled kind-2 `TYPE_P1_FUTURE` (the `%async-run`
struct; `WasmImportCompiler.buildWrapperBody` pushes the kind under the unboxed args) --
started == settled is the option's documented CONTRACT here, exactly like
`--host-fetch`'s fetch future settling at the reply's HEAD; the option buys one source
that reads the same on every backend, not concurrency. Parsed by `WasmImportDirective` (`:async` takes literal
`t`/`nil` only), so the interpreter/JVM stubs load it unchanged (the host does not exist
there; the stub still signals at the call). A `wit-import`ed `async func` member lowers
to exactly this option on Preview 1 (`WitImportDirective.wasmImportForm`), which is what
makes `futurep` agree on all four backends -- the interpreter/JVM bind an async-defun
over the provider call, `--component` a real subtask future, and P1 previously answered
the RAW value. What the declaration buys the BUILD (`compiler/SuspendingImports`, riding
`NoWasiLoadPathRefusals.walk` with a `rootFunction` seed): a standing obligation warning
naming each `module.field` (Suspending wrap + promising entry + serialised calls, a
synchronous host equally valid), a listing of WHICH exports can reach a suspending
import (per-export walk; an import taken as `#'value` widens it to "ANY export" -- the
walk follows calls, not values), and -- under `--no-wasi` only -- an ERROR when the LOAD
PATH reaches one: `_initialize` runs on a stack no `promising` entered, a suspension
there is a TRAP no handler covers (the SUSPEND kind reports through handler-case like
STDIN), and unlike `--host-fetch` (a flag over portable programs, warning only) the
program itself declared the suspension. A WASI command module keeps the warning and
refuses nothing (`wasmtime --preload` hosts are synchronous; `_start` is the whole
program). Pinned by `SuspendingImportsTest` (obligation lines, export listing,
value-widening, load-path error, handler does not silence it), `WasmImportCompilerTest.parsesAsyncOption`,
`WitImportDirectiveTest.lowersAnAsyncFuncMemberWithAsyncTOnPreview1` and the
`asyncImportAnswersASettledFutureThatAwaitResolves` preload E2E (futurep -> T, await
resolves, `#'`-funcall composes). That "a synchronous host is equally valid" is pinned
as ONE MODULE driven both ways on the reactor boundary -- `WasmReactorBodyE2eTest`
(synchronous) and `WasmReactorStreamingHostE2eTest` (`WebAssembly.Suspending` over a
`ReadableStream`, `promising` entry, chunks delivered on the macrotask queue so the
module demonstrably parks and resumes inside one export call) put the same
`rontolisp:http-handler` program through the same reactor pipeline and expect the same
answers from it.

**`--emit-js-glue` -- the host half is written, not described (todo-340,
2026-08-13)**: everything the obligation lines above STATE is derivable, so the flag
WRITES it -- `compiler/HostGlueEmitter`, one ES module beside the `.wasm`
(`out.wasm` -> `out.js`), the `gl-imports.js` precedent (a generated import object
pinned against the declaration it was generated from) applied to a whole module.
`WasmLispCompiler` builds a `HostGlueEmitter.Surface` from the facts it has already
settled -- the parsed import/export declarations, the helper exports the module turns
out to carry (`memoryHelpers`/`hostArena`/`seedRandom`/`setTime`), and the same
`SuspendingImports` answers the obligation lines print -- and `hostGlueJs(fileName)`
emits from it; nothing is re-walked and nothing is read back out of the bytes. What
lands in the file: the import object (one key per `:from`, one property per `:as`),
the `(ptr, len)` staging of every memory-typed value in both directions, the
`__ronto_alloc_mark`/`_reset` bracket around a call with the result decoded BEFORE
the pop, the `__ronto_seed_random`/`__ronto_set_time`/`_initialize` startup order, the
`WebAssembly.Suspending` wrappers, the `WebAssembly.promising` entry for exactly the
exports the build lists, and the one-call-at-a-time queue. **The host is left with what
a declaration cannot state**: one plain function per import over ordinary JS values --
a `:string` arrives decoded, a `:bytes` parameter as a `Uint8Array` copy, and a `:bytes`
RESULT is answered with CHUNKS (`null` ends them) because the generated cursor holds
whatever did not fit, so which source they come from (a `ReadableStream`, a
`Uint8Array`) is all that is left to a host. Every cursor is dropped at the next entry:
a body the module did not drain belongs to the call that could have.

**Which entries suspend is the HOST's answer, not the declaration's, and that is
measured.** `:async t` says the module TOLERATES a suspension there; the shipped Worker
wraps `env.fetch` (which is NOT declared `:async t` -- `--host-fetch` leaves it plain so
a load-path fetch stays a warning rather than the `:async t` error) and leaves
`readRequestBody`/`writeResponseBody` plain although they ARE. Nor is the wrapper free:
on node 24 JSPI an import answering SYNCHRONOUSLY through a `Suspending` still parks the
stack and returns to the event loop -- two overlapped calls, second refused by the
re-entry guard -- so wrapping every declared-tolerant import would buy suspension points
the host never asked for. The generated file therefore exports `suspending(fn)`: a host
marks its own entries, one mark switches the file into its JSPI shape (`promising` +
queue + promise-answering entry points), no marks leaves the SAME file driving a
synchronous host, and an unmarked entry answering a promise is reported by name at
instantiation (`AsyncFunction`) or at the call. Two facts the emitter needs beyond the
directives: `--host-random`'s `env.random_get` is IMPLEMENTED rather than asked for
(preview1 fixes what it does, and writing linear memory is the glue's job anyway), and
the promising list is `SuspendingImports.reaches` widened to the `FETCH` kind, because
`--host-fetch` is the declaration that `env.fetch` may suspend.

**Fixing the under-report that widening exposed.** `SuspendingImports.reaches` follows
CALLS, and the reactor hands its two body bridges over as `#'name`
(`HttpReactorInliner`), so no reactor export was ever listed -- the build printed the
general obligation and named nothing, while `dog-fetcher/src/index.js` wrapped
`handle-request` in `promising` by hand. The walk now takes a `followValues` flag
(`NoWasiLoadPathRefusals.walk`), true ONLY for the reachability question: `#'name` and a
literal lambda body count as reached, with no argument shapes carried in, because it is
a MAY analysis and under-reporting there is a missing `promising`. The load-path report
says what actually RUNS and never follows one. The obligation line now names
`handle-request` on every reactor, which is what the glue emits.

**What reviewing the generated file caught**, none of it visible from a passing build --
a generator can write JavaScript that is wrong, or that is not JavaScript, and nothing
downstream reads it:

- **an EXPORT never becomes a local.** An entry point is a PROPERTY of the returned
  object and the two locals an export declares are `entry$name`/`make$name`, because an
  export called `call` or `bind` would otherwise sit beside the helper of that name and
  make the whole file a `SyntaxError`. A name that is not a bare JS identifier (`:as
  "new"`, `"do.it"`, `"2do"`) is REFUSED with the alias to change, and so is an import
  whose `:from`/`:as` is not one -- a quote in a field would break the string literal it
  is written into.
- **two `wasm-import`s on one `(module, field)` are refused unless identical.** The core
  module collapses them onto ONE slot, so only the last declared shape would survive the
  object literal and silently unpack every caller of both (`:string` and `:bytes` share a
  core signature, so the module stays valid): measured as `(send-text "hi")` reaching the
  host as raw octets. The export side already refused its own version of this collision.
- **`serially` always takes the queue**, even when nothing is marked suspending: the work
  it runs AWAITS, so a second request lands inside it and moves the per-call state under
  the first (`A saw B`). Only a bare entry point skips the queue when nothing can suspend
  -- that call cannot be interleaved, and paying a promise for it would make every host
  asynchronous.
- **a read remainder belongs to its ARGUMENTS and to the call that asked for it.** The
  cursor is dropped at every module entry and whenever the arguments change, and a host
  whose SOURCE moves INSIDE one call drops it itself with `lisp.drop(key)` -- which
  `dog-fetcher`'s `env.fetch` does, because a second fetch replaces the reply the glue is
  still holding 34 KiB of. Without it the second reply came back as
  `AAAAAA len=34767`: the leftovers of the first, served without the host being asked,
  so the module-side superseded-body counter never saw the read either. Pinned by
  `WasmHostGlueE2eTest.aHostWhoseSourceMovesInsideOneCallDropsWhatTheGlueStillHolds`,
  which asserts BOTH answers.
- **the header describes what was actually emitted**: the `serially` sketch names the
  module's own first entry point, and the marking protocol is advertised only when a
  suspension can reach one at all.
- **`--emit-js-glue` refuses to overwrite a `.js` it did not write** (the marker is
  `HostGlueEmitter.MARKER`): the glue is named after the module, so `-o src/index.wasm`
  in a Worker directory aims straight at a hand-written `src/index.js`. And a
  side-artifact flag without `-o` is an error rather than a silent interpretation
  (`--emit-wit` was fixed with it -- a Worker source run through the interpreter tries to
  bind a socket).

**Two reachability holes the same review found, both a MISSING `promising`** (a JSPI
trap, where a spare one costs only a promise): `followValues` walked `(lambda ...)` but
not `#'(lambda ...)`, and the per-export walk is seeded from that export alone, so a
function value handed over ANYWHERE ELSE -- `(defvar *f* #'helper)` funcalled from the
export -- was invisible. `anyTakenAsValue` therefore no longer asks whether the IMPORT
escaped but whether anything that REACHES one did, and widens to "any export" when so.
Consequence, deliberately taken: a reactor hands its body bridges over as `#'name`, so a
reactor now widens rather than listing -- one export either way, and soundness is the
side to err on.

**A divergence to know about**: `:bytes` as a RESULT is declared as "the host answers the
value's FULL length, an undersized buffer is a retry", and the generated glue implements
the STREAM reading of the same shape instead -- a host answers CHUNKS, the cursor holds
what did not fit, and the count is what was written. Both of its real users
(`readRequestBody`, `readResponseBody`) are streams, and a chunk source cannot know the
buffer size to answer a full length against. A host that wants the retry convention
writes that import by hand.

**A latent gap the generator surfaced**: `WasmImportCompiler.usesStrFromMem` named only
`:string`, so a module whose ONLY memory-typed boundary was an `:s-expr`-RETURNING import
exported no `__ronto_alloc` -- and an `:s-expr` result is host-written bytes exactly like a
`:string` one, so NO host could answer it (`TypeError: ex.__ronto_alloc is not a function`,
reproduced on node before the fix; the generated glue's `write()` made it unmissable). The
predicate now names both. Any module that already had a memory-typed export or a `:string`
result is unchanged; pinned by `WasmImportCompilerTest.sexprResultExportsTheAllocatorToo`
beside the `:string` case it sat next to.

Host state that belongs to ONE call cannot be set beside the call (a suspended call
returns to the event loop and the next request would move it), so the generated object
exposes the critical section itself: `serially(work)` runs `work` in the queue and hands
it entry points that enter directly, because the queue they would take is the one they
are in. `examples/cloudflare-workers/dog-fetcher` is the worked example -- `src/worker.js`
is generated and CHECKED IN, and its `src/index.js` is three lines (todo-351 finished
that: the derived host halves cover the streaming boundary too).
Gated to `--no-wasi` core modules (a component is instantiated through jco; a
`--no-gc` module imports nothing, so `new WebAssembly.Instance(module, {})` is its whole
glue). Pinned by `HostGlueEmitterTest` (every checked-in `src/worker.js` byte-for-byte
against what its shape emits -- four shapes over nine files, a reactor that FETCHES and one
that only ANSWERS on each boundary; the glue depends on the DECLARATIONS alone, which is
why one derived string covers a whole family and why that is asserted rather than assumed;
plus the promising selection, the no-import shape, the `--host-random` entry and the
name-collision refusal),
`RontoLispCliTest` (the flag writes
the file; every other output shape is a clear error), and `WasmHostGlueE2eTest` (node 24
JSPI: one generated file driving a suspending host, two overlapped calls both answering
through the queue, then a synchronous host answering a STRING from the same file).
That re-evaluation trigger FIRED (todo-348, 2026-08-15): under `--reentrant` the
generated file drops the queue and `serially` entirely and the import object above them
is unchanged -- exactly as predicted. The serialising shape stays the default and its
emission is byte-identical (`HostGlueEmitterTest`'s checked-in pins are untouched).

**`--host-boundary` -- WHICH boundary, and what each costs (todo-351, 2026-08-14)**: a
reactor's bodies leaving the envelope (`.kb/clack.md`) was unconditional on the Preview 1
core module, and `--host-fetch`'s reply body with them. What that buys is real; what it
costs was paid by every program, including the ones that fetch one JSON document and
answer one. `compiler/HostBoundary` makes it a choice, and the choice is a MODULE
decision -- it changes the import list -- so it is a flag of its own rather than a value on
`--emit-js-glue`, which stays a boolean and now REFUSES one by name (an unvalidated value
used to parse and be discarded, compiling the other boundary without a word).

| | `envelope` (DEFAULT) | `streaming` |
| --- | --- | --- |
| imports | `env.fetch` under `--host-fetch`, else NOTHING | `env.readRequestBody`, `env.writeResponseBody`, +`env.readResponseBody` and `env.fetch` under `--host-fetch` |
| host state | none | one cursor per reading import, plus whatever holds its source |
| binary body | DESTROYED -- `ff fe 41` in, `ef bf bd ef bf bd 41` out | crosses exactly |
| large body | copied, memory proportional | linear memory flat |
| streamed upstream reply | buffered first | forwarded chunk at a time |
| generated host | `instantiate` + `defaultHost()` + `worker(module)`, the SAME on both |
| module size | within ~1% of each other, sign not stable -- see `size-report/results/cloudflare-workers.md` |  |

**The DEFAULT is `envelope`, and it moved there on purpose (2026-08-14).** The
first cut kept `streaming` as the default and recommended `envelope` in the
guides; that was rejected, correctly -- a default IS the recommendation for
everyone who does not read the guide, and two answers to one question means the
tool and the docs disagree with the tool winning. So the recommendation was
carried into the default and the in-tree consumers that need the other one were
migrated in the same change.

**What that broke, stated plainly.** Every `--no-wasi` reactor rebuilt without
the flag changes shape. For a body that is a document, nothing observable; for a
BINARY one, measured destruction -- `ff fe 41` arrives as the seven bytes
`ef bf bd ef bf bd 41` (two U+FFFD where two octets were, the JSON text round
trip doing it) with the `content-length` beside it still saying three, and
nothing reporting it. `--host-boundary=streaming` gets the old module back
byte-for-byte. The five `httpbin*` Worker examples ECHO request bodies and now
say so in their `build.sh`; the three `hello*` ones read no body and were left on
the new default unchanged (verified: their hand-written host answers correctly,
with two import-object entries the envelope module simply does not link).
This is the house policy rather than an exception: a rebuild is an acceptable
price for the better default, and every consumer is in-tree.

**It is not a size decision -- do not sell it as one, and do not quote a bound.** The
todo-351 spike measured 148,533 B against 147,959 B (48,912 / 49,029 gzip -9 -n) on a
clack-free ticker, the envelope one marginally LARGER gzipped; the shipped
`btc-ticker` measures the other way round and about 1% apart on both. Which sign it
lands on is a property of the program and of the tree shaker, not of the boundary,
which is exactly why `size-report` measures both rows rather than a README asserting
one. The 20 lines of host glue are not the point either; the STATE is. Both defects the todo-340 review turned up on this surface were state-lifetime bugs (a
reply cursor outliving its source, an instance selected outside the critical section), and
an envelope host has no cursor to outlive anything.

**What that lets the emitter WRITE -- on BOTH boundaries.** The halves a reactor's
boundary leaves are fixed by the transport rather than chosen by the program, so `Surface`
gained the two facts saying so (`derivedFetch`, `envelopeExport`) and the file emits them:
`defaultHost()` (the `env.fetch` host half, from `FetchResponseShape` in both directions,
error arm included) and `worker(module, options)` (a `Request` onto
`ReactorEnvelope.REQUEST_KEYS`, a `Response` off `RESPONSE_KEYS`, the instance created on
the first request and retired if a call traps, the queue taken when the fetch can
suspend). A Worker is then `export default worker(module)` and nothing else -- BOTH
shipped ones are that, three lines each, byte-pinned. `options.host` is laid over the
derived entries one at a time, so a host replacing `env.fetch` keeps the rest of `env`.

**The line the first cut drew here was wrong, and it is worth writing down why.** It said
the streaming halves are not derivable, because "with a body out of band the host owns the
reader the octets come from". That is true of `instantiate`, whose caller is an arbitrary
host -- and false of `worker()`, which IS the host: the request body's octets are the
`Request` it is already holding, the response chunks are the `Response` it is already
building, and the reply body is the `fetch` its own `defaultHost()` just made. So
`worker()` writes all four, `defaultHost(lisp)` takes a thunk answering the instance
because ITS cursor is the one a second fetch inside one call supersedes (`lisp.drop`), and
the two body imports live in `worker()` rather than `defaultHost()` because they are
per-CALL state and the call is `worker()`'s. **The consequence is the point of the whole
item**: the boundary is no longer an ERGONOMICS decision at all. Both shapes cost three
lines of host, and what separates them is only what happens to a body -- which is what the
guides now say ("reach for envelope; stay on streaming when a body is binary, large or
relayed").

**Two facts, derived from the IMPORTS rather than threaded down from the flag.**
`WasmLispCompiler` reads `derivedFetch` off "`--host-fetch`, the program really calls
`rontolisp:fetch`, `env.fetch` imported, `env.readResponseBody` not" and `envelopeExport`
off "an export that is BOTH the synthesized bridge defun and the transport's own export
name" -- on EITHER boundary, since todo-351 made `worker()` fill the body imports too.
A flag is a request; what the module imports is the
answer, and the glue has to describe the module it was emitted beside. Both fingerprints
need both halves, and the review that added the second half of each is why: `env.fetch` by
module+field alone matches a program's OWN import of that name (`--host-fetch` splices
nothing for a program that never fetches, so the glue would have offered to implement
rontolisp's HTTP envelope into a slot meaning something else), and `%reactor-dispatch` by
member name alone matches a function a program happened to spell that way. Note what still
follows: `examples/cloudflare-workers/httpbin`, which exports `handle-request` by HAND,
gets no `worker()`.

**Every other shipped Worker took the generated glue (todo-352).** The seven reactors that
go through `clack:clackup` -- the `hello-*` trio on the envelope boundary, the four
`httpbin-*` on streaming -- had two hand-written hosts between them, copied byte for byte
across the directories; their `build.sh` now passes `--emit-js-glue` and their
`src/index.js` is the `worker(module)` call. The four `httpbin-*` pass one option,
`remoteAddr: (r) => r.headers.get("cf-connecting-ip")`, because their hand-written host
sent Clack's `:remote-addr` and dropping it would have been a silent regression -- which
header carries the client address is exactly the thing `worker()` leaves to its caller.
`examples/cloudflare-workers/httpbin` stays hand-written, and that is a fair thing for the
"no library, boundary included" example to be; giving the compile path a way to recognise
a HAND-WRITTEN envelope export is the bigger decision nobody has needed yet. What the
migration bought is one copy of the state whose LIFETIME is what goes wrong: every defect
this surface has produced (the todo-340 and todo-351 reviews) was a state-lifetime bug,
and two of them existed in a generated file only because a hand-written one had been
transcribed imperfectly. One behavioural difference, and it is the generated shape rather than
this migration: the four streaming Workers now enter through `serially`, so a request
takes one promise hop and the module calls are ordered by the queue instead of by the
isolate's own single thread. That is what makes the per-call body state safe to set
inside the section, and `dog-fetcher` has shipped it since todo-351. Verified before and
after under node 24 on one Worker of each family -- the transcripts, binary echo
included, are identical -- and under `wrangler dev` for `httpbin-clack`.

**`ReactorEnvelope` (in `compiler`) is where the envelope's names now live** -- the bridge
defun, the export name, the six request keys, the three response keys, the `env` module
and the two body fields -- because three packages that may not import each other needed
them (`eval` synthesizes, `codegen.wasm` recognises, `HostGlueEmitter` maps), and an API
spelled three times drifts. `FetchResponseShape` gained the `env.fetch` field names for
the same reason. `ReactorEnvelopeTest` pins both key lists against `http-reactor.lisp`,
the file that really reads and writes them, in both directions -- the response one
scoped to `%http-reactor-envelope`, since the 500 arm's `:error` rides the error
DOCUMENT's plist and is not an envelope key.

**`rontolisp-body-imports`, and why the guard it replaces was wrong.** A hand-written
reactor guards its own body imports with a reader feature now
(`reader/Features.BODY_IMPORTS`), present exactly where those imports exist. It replaced
`#+(and rontolisp-reactor (not rontolisp-component))`, which enumerated two of the targets
that cannot carry them instead of naming the imports: it silently included `--no-gc` (a
reactor with no packed-array representation for `:bytes` at all) and could not have
followed a flag. `examples/cloudflare-workers/httpbin/worker.lisp` is the consumer -- one source
whose imports the feature turns on for the streaming core module and off for the
interpreter, the JVM, a WASI command module, a component and the envelope boundary
alike. Pinned by `RontoLispCliTest.theStreamingBoundaryReadsTheSourceWithTheBodyImportsFeature`,
which is the only shape that CAN pin it: no Java reads the feature back, so only a source
branching on it can show it working (the `#-rontolisp-component` precedent).

**What reviewing the generated `worker()` caught**, none of it visible from a passing
build, and all of it the same shape as the todo-340 round -- state whose LIFETIME the
emitted code got wrong:

- **the instance is bound at admission, so `poisoned` has to be re-read inside the
  critical section.** `live().serially(...)` evaluates `live()` before the queue admits
  anything, so a request already in the queue holds the instance an earlier parked call is
  about to trap. Measured: after a host import throws across the boundary, the two
  requests behind it answered 200 with the trapped call's special binding still shallow
  bound and its counter still advancing. The module's own re-entry guard does not save
  them -- the EH landing pad CLEARS it on exactly the path that poisons the instance. The
  hand-written `dog-fetcher/src/index.js` had the check; the generated copy had dropped
  it -- and that copy is now the only one, so the check had to come back before the hand
  written file went away.
- **the WHOLE handler belongs in the try.** Only the module call was guarded, so
  `await request.arrayBuffer()` on an aborted upload and `new Response` on a status or
  header the application is free to produce (0, 999, a newline in a value) escaped as an
  unhandled rejection -- on Cloudflare a 1101 page with nothing in the log. `poisoned` is
  now set only when the call ENTERED the module, since a refused Response says nothing
  about the instance.
- **`new Response("", { status: 204 })` is a TypeError.** The envelope always carries the
  `"body"` key without a sink, so an ordinary 204 came back as a logged 500 with the
  instance discarded; the mapping answers `head.body || null`.
- **the decoder must `ignoreBOM`.** These octets are a VALUE: the default decoder deletes
  a leading U+FEFF, shortening a BOM-prefixed request body by a character while the
  `content-length` the same mapping just wrote still counts three.
- **an options hook is awaited.** An async `remoteAddr` crossed as `{}` -- a 200 with an
  empty hash table where an address was due, and no diagnostic anywhere.
- **the three-line sketch is only written when the file answers EVERY import.** A
  `--host-fetch` build whose only fetch sits on the LOAD path imports `env.fetch` while no
  export is promising, so no host half is written -- and `worker(module)` 500s on every
  request. The sketch now says `worker(module, { host })` there.

**The default does not move, and neither does `dog-fetcher`.** The pair is the controlled
comparison -- `dog-fetcher` streaming, `btc-ticker` envelope -- the way `httpbin` /
`httpbin-clack` is for clack. The streaming MODULE is byte-identical to its pre-flag
output; its glue moved by exactly the `ignoreBOM` line above. Pinned additionally by
`RontoLispCliTest` (each
refused mode name; the flag refused on every output shape that has no choice to make; the
same source compiled twice, only the streaming build declaring the body imports),
`HostFetchLibraryTest` (both boundaries carry the same derived envelope; the envelope one
names neither the import nor the cursor nor the reactor's receive machinery) and
`WasmHostGlueE2eTest` (`worker(module)` against a real `node:http` upstream: a GET, a POST
body through the envelope, `remoteAddr`, and two overlapped requests both answering).

**The re-entry guard -- a module that can suspend refuses interleaved calls (todo-337,
2026-08-12)**: a parked JSPI call returns control to the host's event loop, so a second
call can enter an export while the first is suspended, and NOTHING in the module owns
its state per call -- the `__ronto_alloc_mark`/`_reset` bracket's marks interleave (they
do not nest), the shallowly-bound specials share one module-global cell
(`.kb/dynamic-special-variables.md`), and a `(ptr,len)` result sits at un-advanced
`HEAP_PTR` scratch. Measured on node 24 `--experimental-wasm-jspi` before the guard,
BOTH corruptions are silent wrong bytes: a special read back after the resume answers
the OTHER call's binding (and the outer value leaks), and the second resume's result
copy overwrites the first's still-unread `(ptr,len)` (`"bb\"AAAAAAA"` where
`"AAAAAAAAAA"` was returned). So a module that CAN suspend -- any `:async t` import
declared, or `--host-fetch` with `rontolisp:fetch` actually used -- carries a guard
global (a `mut i32`, third from last so the cached-t/raw-sentinel stay the last two;
`reentryGuardGlobalIndex`, emitted only when the module also exports something), and
EVERY export wrapper checks-and-sets it on entry (`global.get; if; unreachable; end` --
a TRAP, because at export entry no Lisp handler can be active and output on a reactor
is a sink, so the build warning is where the message lives; it names the trap) and
clears it on every return, including the hand-rolled catch_all landing pad in EH mode
(`WasmExportCompiler.emitReentryGuardStore`) so a host that catches a Lisp-error trap
and then calls SEQUENTIALLY is not refused as a re-entry it never made. A serialising
host (dog-fetcher's promise queue) and a synchronous host never see it; a
non-serialising host gets `RuntimeError: unreachable` at the second entry, with the
FIRST call completing correctly -- verified on node 24 JSPI with two overlapped calls
(one binding a special across the suspend, one returning a string), both wrong before,
"right or refused" after, and serial byte-behaviour unchanged. Any module that cannot
suspend gains no guard, no global, no instruction (byte-identity re-verified across
P1/no-wasi/component). Utility exports (`__ronto_alloc*`, the seed/clock hooks) stay
unguarded -- they are the host's own bracket tools. Pinned by
`WasmImportCompilerTest.aSuspendingImportGuardsEveryExportAgainstReentry` /
`hostFetchGuardsExportsExactlyWhereFetchIsUsed` and the
`aGuardedExportAnswersThroughASynchronousHost` preload E2E. The guard stays the
DEFAULT; `--reentrant` (todo-348, the section below) is the opt-in that retires it by
making the module own its per-call state.

**`--reentrant` -- overlapped calls on ONE instance (todo-348, 2026-08-15)**: the
per-call state the guard's paragraph said had to land first HAS landed, behind an
opt-in, and the guard is relaxed exactly there. Measured motivation (recorded per the
todo's own instruction): 8 concurrent 100 ms round trips took 803 ms serialised and
239 ms as instance-per-request (~17-37 ms `_initialize` per call, PLUS a 16 MiB GC
pre-grow and ~2 MiB linear memory PER instance -- the shape a Worker's memory budget
cannot afford at real concurrency); `--reentrant` answers them in ~125 ms on one
instance. The trigger workload is exactly the todo's: I/O-bound AND unable to afford an
instance per request. What the flag changes, all of it `reentrant`-gated so every other
module stays byte-identical (`WasmReentrantCompilerTest`):

- **The per-task dynamic store** (`codegen.wasm/WasmDynVars`,
  `.kb/dynamic-special-variables.md`): the JVM `_d$` hybrid ported -- only
  `SpecialVarCollector.collectDynamicallyBound` names get a slot in a per-call TASK
  RECORD (a `TYPE_HASH_BUCKETS` of nullable `TYPE_CELL`s in a module global), created by
  every export wrapper on entry (and by `_start` for the load path), saved into a
  wrapper local and restored around the ONE place another extent can run -- the
  suspending host call in the import wrapper. Reads are dynamic-first with the module
  global as the default; binds/`setq`/exit-restores keep the exact save/restore
  discipline (and the exact `.todo/192` unwind limitations) the shallow path has.
  Under-collection is a compile-time throw at the binding site, the JVM rule.
- **The park-block allocator** (`WasmExportRuntimeBuilder.buildParkAllocBody`,
  exported as `__ronto_park_alloc`/`__ronto_park_free`): the arena's absolute
  mark/restore is exactly what two interleaved extents cannot share, so staging that
  must SURVIVE A PARK moves into first-fit free-list blocks carved permanently off the
  bump heap (never split, never coalesced -- steady same-size overlap recycles
  perfectly, which is what makes TWO interleaved 64 KiB pull loops memory-flat, the
  finding-2 gate widened to two callers). Everything else stays on the `HEAP_PTR`
  scratch stack, whose pops are made park-safe by one clamp: `__ronto_alloc_reset` (and
  the wrappers' own restores) never go below `PARK_FLOOR_ADDR`, the top of the newest
  carve. The ABI consequences, stated by the obligation lines and written by the glue:
  a `:string`/`:s-expr` EXPORT result crosses as a park block the READER frees (the
  scratch it used to sit at is trampled by whatever wasm runs in the microtask gap
  before the host's decode -- todo-337's second corruption); a `:string`/`:s-expr`
  IMPORT result must be park-written by the host and is freed by the wrapper; a
  `:bytes` receive buffer a host passes into an export must be park-allocated; the
  glue's argument bracket pops SYNCHRONOUSLY at the entry call (args are boxed before
  the first suspension).
- **The glue** drops the queue and `serially` (the todo-340 re-evaluation trigger,
  fired), keeps the `suspending()` marking protocol and the promising selection, and
  `worker()` calls the entry directly. The bytes-reader cursors keep their
  keyed-by-arguments shape; two overlapped calls pulling one source through IDENTICAL
  arguments are documented as the host's own hazard.
- **Refusals**: a program nothing can suspend (no `:async t` import and no used
  `--host-fetch` fetch -- overlap cannot happen, the flag would buy nothing);
  `--component` (its concurrency is the component model's); `--dynamic` (the eval
  mirror is per-instance state the task record does not cover); and an ID-LESS
  streaming body import (a hand-written reactor's own, e.g. `httpbin/worker.lisp`'s)
  -- the composed shape below is what the CLI synthesizes instead.
- **The streaming body protocol composes, by carrying a CALL IDENTITY (todo-369,
  2026-08-15)**: under the flag `HttpReactorInliner` / `HostFetchLibrary` synthesize
  every body import with a leading `:int` id -- `env.readRequestBody(id, ptr, cap)`,
  `env.writeResponseBody(id, ptr, len)`, `env.readResponseBody(id, ptr, cap)` -- so
  todo-341 finding 3's no-handle argument is not relaxed but SCOPED: wherever the
  id-less protocol exists, the guard or the queue still holds (every serialised build
  stays byte-identical -- the four `httpbin-*` glue byte-pins are untouched), and the
  compiler refuses an id-less `env.*` body import under `--reentrant` instead of
  shipping a cursor. Three identities, two mints: the REQUEST's id is minted by the
  glue's `worker()` per request and rides the envelope's `"call-id"` key
  (`ReactorEnvelope.CALL_ID_KEY`, in `REQUEST_KEYS`, absent everywhere else); the
  transport reads it at the one place the envelope is parsed and closes it over the
  body thunks (`%http-reactor-bind-source` / `-bind-sink`), so everything downstream
  stays on the 0-arity source. A fetch REPLY's id is its OWN, not the request's (a
  second fetch inside one call must not have to supersede the first): `defaultHost()`
  mints one per fetch, hands it back in the reply head's reserved `"body-id"` key
  (`FetchResponseShape.HOST_BODY_ID_KEY`), keeps one reader per reply -- dropped when
  drained; `lisp.drop` and the open-reply "superseded" counter both gone from this
  shape -- and the module's drain pulls by it. The reentrant glue keys all per-call
  state by id: `worker()`'s request/response body maps are retired in a `finally`, and
  the `:bytes` reader's REMAINDER becomes a map keyed by the pull's arguments (the
  serialised single-slot cursor dropped the other call's leftover octets on every
  alternation -- silent mid-chunk loss under overlap). Gates in `WasmReentrantE2eTest`:
  two overlapped streaming requests (one binary `ff fe 41`, one text) each answered
  its OWN echo, and two overlapped calls each relaying a different upstream's reply
  chunk-at-a-time to its own client. Pins: `WasmReentrantCompilerTest` (id-less
  refusal + composed build), `HttpReactorInlinerTest` / `HostFetchLibraryTest` (the
  synthesized shapes), `RontoLispCliTest.theStreamingBoundaryComposesWithReentrant`
  (the CLI really threads the flag into the synthesis).
  `dog-fetcher` itself stays streaming + serialised, unchanged -- the controlled
  comparison with `btc-ticker` is worth keeping -- and the composed shape ships as
  `examples/cloudflare-workers/dog-relay` (dog.ceo's own reply relayed chunk by chunk,
  six concurrent relays in about one round trip; its `src/worker.js` is the fifth
  `HostGlueEmitterTest` pin). What that example surfaced and deliberately does NOT
  show: a fetched reply relayed as-is is TEXT (`:body` is a character stream on every
  backend), so a binary upstream body does not cross byte-exact -- `.todo/370`.

Gates, all in `WasmReentrantE2eTest` (node 24 JSPI): the todo-337 reproduction with its
expectation INVERTED (two overlapped calls binding one special across a suspend each
read their own binding back, default intact); overlapped `:string` boundaries exact in
both directions; two interleaved 64 KiB pull loops with `memory.buffer.byteLength`
unchanged; and the width itself -- 8 concurrent upstream round trips through ONE
envelope worker in ~one round trip (bounded < 500 ms against a 100 ms upstream,
refuting the 800 ms serial shape).

Tests: `WasmImportCompilerTest` (structural: import-section order, index shift,
allocator gating, mode rejection), preload-based E2E in
`WasmLispCompilerIntegrationTest` (`wasmtime run --preload host=... main.wasm`, host
module itself compiled from Lisp with `:as` aliases), stub tests in
`LispEvaluatorTest`/`JvmLispCompilerTest`. Showcases: `examples/browser/webgl-triangle/` (hello
world: 10 imports, no exports, whole program in top-level forms run by `_initialize`;
deliberately self-contained, does not use the shared package),
`examples/browser/webgl-cube/` (3D: mat4 math in Lisp, bulk floats via a `setFloat` staging
array) and `examples/browser/webgl-galaxy/` (browser
host; the whole WebGL pipeline is driven from Lisp through 32 imports -- GLSL sources as
Lisp strings via `:string` params, handle-table one-liner JS bindings, `:string` results
for shader info logs -- staged to Pages via pom.xml); cube, galaxy,
heat3d and robot-arm all pull the WebGL2 boundary from `examples/browser/webgl-common/gl.lisp`,
and `webgl-common/gl-imports.js` is staged beside them because their pages import it.

**`:bytes` -- the byte-TRANSFER type, and the caller-passes-the-buffer result rule
(todo-341 Phase 0, 2026-08-13)**: an `(unsigned-byte 8)` vector (the bare `TYPE_I8ARR`
array, `.kb/packed-integer-vectors.md`) crosses as RAW bytes -- no UTF-8 in either
direction, because the `:string` decoder is non-validating and hands back garbage code
points for arbitrary binary (`ff fe 41` -> code point 0x1FE062). The line worth keeping:
**`:string` is a value, `:bytes` is a transfer.** A parameter stages as `(ptr,len)` like
a string (but bump-ALLOCATED, not at un-advanced scratch, so several can coexist); a
RESULT is the `read(2)` shape -- the Lisp signature gains ONE trailing parameter (the
receive buffer vector; `WasmImportDirective.lispParamCount` / `WasmImportCompiler
.lispArity`, which every backend's stub/wrapper arity derives from), the host is called
with a trailing `(ptr, cap)` pair and answers the value's FULL length (undersized buffer
= retry, never truncation), and the wrapper copies `min(n,cap)` back and POPS the heap
to its entry mark -- a plain `HEAP_PTR` store, safe because nothing between mark and
restore can intern -- so a pull loop over one reused buffer keeps linear memory flat
(the todo-341 finding-2 gate: 10000 pulls staging 64 KiB each, memory flat, pinned).
Same convention on the export side: a `:bytes`-returning export's core signature gains
the trailing `(ptr,cap)` and returns the full length as its single i32. Three helpers,
gated on the designator appearing (everything else byte-identical, pinned by
`bytesHelpersRideOnlyABytesDeclaringModule`): `_bytes_from_mem` (fresh vector from
linear, reuses TYPE_RAT_NEW), `_bytes_copy` (vector -> mem, returns full length) and
`_bytes_fill` (mem -> vector, returns n) sharing one appended `((ref null eq),i32,i32)
->i32` signature at the abiTypeBase block (`WasmExportRuntimeBuilder`). Modes: GC core
modules only -- `--component` refuses eagerly (no `list<u8>` lift yet; the refusal names
it) and `--no-gc` refuses (no arrays). Content round-trip can only be proven against a
host that shares the module's memory, so the E2E is a JS host on node
(`WasmBytesBoundaryE2eTest`, node-gated): `ff fe 41` exact in all four directions,
full-length answer on an undersized buffer with no overrun, and the flat-memory loop;
the wasmtime preload leg (`bytesBoundaryCrossesThePreloadBoundaryByLength`) pins the
plumbing through the values that DO cross two disjoint memories -- the lengths.

**Its first consumers are the reactor's two bodies** (todo-341 Phases 2b and 3b,
2026-08-13; the third is `--host-fetch`'s reply body, todo-347 -- same result
shape, same `env` module, `.kb/fetch-http.md`): `HttpReactorInliner` synthesizes
`(wasm-import '%reactor-read-body :from "env" :as "readRequestBody" :params '()
:returns :bytes :async t)` and
`(wasm-import '%reactor-write-body :from "env" :as "writeResponseBody" :params
'(:bytes) :returns :void :async t)` beside the `handle-request` export, so the head
crosses as the JSON envelope and both bodies cross as octets -- in, into one reused
buffer; out, straight out of the module's memory -- the flat-memory property above,
measured on a real Worker module in both directions. **The two spellings are the
same rule, not an asymmetry**: a chunk crossing IN is a `:bytes` result into a
caller-passed buffer, one crossing OUT is a `:bytes` parameter the wrapper stages and
pops, and both say the caller owns the memory (which is why the write import answers
nothing -- a host cannot short-read a write). `.kb/clack.md` ("The WASM boundary")
has the whole shape, including why each thunk CALLS its import instead of taking
`#'name` (the suspending-import report follows calls), why `--component` keeps the
in-band bodies, and why a plain WASI COMMAND module keeps them too (its host is
`wasmtime run`, which satisfies no `env.*` import, so declaring one there made the
module refuse to instantiate).

**The component path does NOT go through this compiler** (todo 128): `rontolisp:wasm-import`
is still a Preview-1-only directive (`--component` throws). A `rontolisp:wit-import` under
`--component` instead lowers to the internal `rontolisp::%component-import` form, which
`WasmComponentImportCompiler` turns into canonical-ABI marshalling defuns — a different
compiler, but the SAME synthetic-defun + `PLACEHOLDER_FUNC_BASE` + `WasmImportInjector`
mechanism described above, sharing one ordinal space with these imports. See `.kb/wit.md`
("Component imports").
