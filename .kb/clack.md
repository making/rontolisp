# Clack (`(ql:quickload "clack")` + `clack:clackup` on the rontolisp backend)

The `.todo/223` milestone: Eitaro Fukamachi's Clack loads VERBATIM from the
Quicklisp dist (clack-20250622-git + lack-20260101-git) and a Clack application
runs through `clack:clackup :server :rontolisp` on the interpreter, the JVM and
the WASM `--component` backend. Preview 1 has no incoming TCP by design
(`.kb/tcp-sockets.md`): the program COMPILES and `clackup` signals the standard
`http-handler` message at CALL time (see the policy note below). Pinned by
`ClackE2eTest` (opt-in `RONTOLISP_CLACK_E2E=1`: Docker for the pinned wasmtime,
network for the first quickload) — which runs its three legs twice: once over a
bare handler lambda, and once over a real ROUTING application (tiny-routes plus
its cookie middleware, both quickloaded unpatched; `.kb/asdf.md`), because "one
handler function" is not what an application looks like and the routes are read
inside the application's own package.

## Two routing layers are verified, and they are a different test

**tiny-routes** (`ClackE2eTest`'s second trio) and **ningle** v0.3.0 +
myway + map-set (`NingleE2eTest`, opt-in `RONTOLISP_NINGLE_E2E=1`, same three
legs) both load unpatched and both serve on the interpreter, the JVM and the
`--component` build. Keeping the second one is not redundancy — it exercises
machinery the first has no counterpart for, and each of these is what broke
while it was being made to work:

- **The application is a CLOS object**, a `lack-component`, so serving it goes
  through `defmethod call :around` + `call-next-method` and
  `(setf (find-class '<app>) (find-class 'app))` rather than through funcalling
  a composed closure.
- **Dispatch runs REQUIREMENT closures compiled at route-DEFINITION time**
  (`ningle/route::compile-requirements` closes over a `loop for (name val) on
  ... by #'cddr` pair), so a route can be selected by something that is not the
  path — `:accept` negotiation, or a user's `(setf (ningle:requirement app
  :key) fn)`. That closure is what the `.kb/loop-iteration-heads.md` fix exists
  for: before it, every requirement answered "unsatisfied".
- **ningle reads every request through `lack-request`**, which tiny-routes
  never touches: the http-body / fast-http / smart-buffer / circular-streams /
  quri / yason / trivial-mimes chain is compiled and run by these legs. That
  chain is also ~2 MB of a ~2.7 MB module, and it is what made a ningle Worker
  impossible until the `--no-wasi` stubs learned to answer `random` and
  `getenv` (`.kb/wasm-export-no-wasi.md`) — the load-time trap was
  smart-buffer's, not ningle's doing. There is no size opt-in to offer either (myway compiles every rule to
  a cl-ppcre scanner), which is the deliberate difference from
  `tiny-routes/lite`. That chain is also why **a ningle application is the only
  one of the two that parses a request BODY on the normal path**: a controller
  reads `request-parameters`, which for `application/json` reaches yason
  through http-body. yason makes its own stream out of the string, so until
  `make-string-input-stream` existed as a public name every JSON request body
  answered `400 Bad Request` — `lack/request` catches the undefined-function
  error and ningle's `call :around` turns it into the 400. Retired in
  `.kb/read-load-streams.md`; the reason it took so long to surface is that the
  serve legs here post form-encoded bodies, which http-body parses without a
  stream constructor.
- **`ningle:not-found` sets the status and returns nil**, so lack's
  `finalize-response` answers a body LIST holding NIL and every ningle 404 has
  that shape — the response-contract arm in `.kb/http-server.md`.

Preview 1 cannot serve either of them, but ningle's ROUTING runs there:
`examples/cloudflare-workers/hello-ningle/check.lisp` and
`examples/cloudflare-workers/httpbin-ningle/check.lisp` drive it through the
reactor path on the interpreter, the JVM and Preview 1 (`examples/examples.yaml`).
The second is the wider pin, and deliberately does NOT share the other httpbin
Workers' code: it covers routes assigned in a loop, an `:ANY` fallback rule per
path (the 405), a `:regexp t` rule whose `:captures` bind (the decline), a
controller that returns a string and mutates `ningle:*response*`, and the JSON
and form-encoded bodies arriving already parsed as `request-body-parameters`.

## The handler backend is a built-in shim system, found LATE-BOUND by name

`clack-handler-rontolisp.lisp` (`ShimLibraries`/`BuiltinSystems` +
`resource-config.json`) defines package `clack.handler.rontolisp` exporting
`run`/`stop`. Clack's discovery protocol drives every design choice here:
`find-handler` -> lack's `find-package-or-load` -> `(find-package
"CLACK.HANDLER.RONTOLISP")`, and only on a MISS `(asdf:find-system name nil)` +
`(asdf:load-system name :verbose nil)`, then `(apply (intern "RUN" pkg) ...)`.
Consequences, each load-bearing:

- **The package is NOT seeded in `PackageRegistry`** (unlike every other shim
  package): a pre-seeded package would satisfy the `find-package` probe before
  the system ever loads and leave `run` undefined at the apply. The shim
  carries its own `defpackage` (the leaf-module pattern) instead, and the
  interpreter's builtin-system branch of `loadSystem` evaluates shim forms
  through the RESOLVING `eval(form)` (not `eval(form, globalEnv)`) so that
  defpackage registers.
- **The system answers to TWO names**: `clack-handler-rontolisp` (the
  ecosystem-conventional spelling a user can name directly) and
  `clack.handler.rontolisp` — `find-package-or-load` derives the system name
  from the PACKAGE name by hyphenating `/` but leaving `.` alone (no
  backward-compatible flag on the handler path), so the dotted spelling is the
  one clack actually asks ASDF for. Both keys map to the one resource in
  `ShimLibraries.RESOURCES` / `BuiltinSystems`.
- **The interpreter's runtime `asdf:find-system` answers built-in systems**
  (returns the name string) even before they are loaded — that hit is what
  routes `find-package-or-load` onto its `load-system` branch at clackup time.
- **The compile paths splice the shim EAGERLY with clack**
  (`LoadInliner.spliceSystem`: after splicing system `"clack"`, it splices
  `clack-handler-rontolisp`), because a compiled program cannot load anything
  at run time: there, the `find-package` probe reads the baked package table
  (the shim's defpackage is in the program) and the interned
  `CLACK.HANDLER.RONTOLISP:RUN` resolves through the `_lookup` registry
  (todo-229, `.kb/symbol-runtime-api.md`) — the asdf branch is never reached.

## run / stop and the stoppable server seam

`run` follows the clack-handler-hunchentoot shape: it BLOCKS until the server
stops, and cleans up in an unwind. On the interpreter/JVM it rides the internal
`rontolisp::%http-server-*` seam (`HttpHandlerSupport.startServer/joinServer/
stopServer/serverPort`; the handle is an opaque integer index, the socket/mutex
convention; owned by the rontolisp package as INTERNAL symbols):

```lisp
(let ((server (rontolisp::%http-server-start app port address :raw-body :buffered)))
  (unwind-protect (progn (rontolisp::%http-server-join server) server)
    (rontolisp::%http-server-stop server)))
```

- `%http-server-start` takes the handler as a FUNCTION VALUE (unlike the
  `http-handler` directive's quoted name) plus port (0 = ephemeral,
  `%http-server-port` reads it back) and bind address (the directive binds the
  wildcard; the seam binds `address`).
- With clackup's default `:use-thread t`, `run` runs on a `bt2:make-thread`
  thread and `clack:stop` DESTROYS that thread (it never calls our `stop`):
  `destroy-thread` is `Thread.interrupt`, the interrupt lands in
  `joinServer`'s latch await, join returns NORMALLY, and the unwind-protect
  stops that one server. `stopServer` is idempotent so the unwind cleanup and
  an explicit `clack:stop` (the `:use-thread nil` protocol, unreachable in
  practice because `run` blocks) cannot double-fault.
- With `:use-thread nil`, `run` (and so `clackup`) blocks forever serving —
  hunchentoot parity, the script shape.
- JVM: `JvmHttpServerSeamCompiler` stores the handler value in the same
  `_httpHandlerFn` static slot the directive uses and reuses the whole
  injected-`handle(Request)` runtime (`JvmLispCompiler`'s `usesHttpHandler`
  gate also fires on `%http-server-start`). CONSEQUENCE, and the reason the
  shim documents ONE Clack server per process: every server dispatches through
  the one handler slot / the one `*app*` global.
- `clackup`'s default `:use-thread t` exists at all because `Features`
  INTERPRETER/JVM now include **`:thread-support`** (`.kb/threads.md`) — the
  feature upstream bordeaux-threads pushes at load time, which can never reach
  a read-time conditional here, declared statically like `:unicode`. WASM
  stays without it, so the default is nil there.

## There is no env / response bridge any more (the todo-258 cutover)

Since the Clack cutover rontolisp's own server protocol IS Clack's
(`.kb/http-server.md`): the handler receives the Clack environment and returns
the Clack response, built and normalized once in `http-server.lisp` for every
backend. The shim therefore hands the application to `%http-server-start`
DIRECTLY (no `%bridge`, no `%env`, no `%headers-table`, no `%body-string`) and
asks for the one thing Clack needs that rontolisp's default is not:
`:raw-body :buffered` — a synchronous bivalent body stream instead of the
native asynchronous one, which is what lets lack-request / circular-streams /
http-body actually read a served body (sessions, CSRF, ningle). What used to
be documented here as shim limits is now the shared model's contract:
`:remote-addr`/`:remote-port` carry the real peer on the interpreter/JVM (nil
on the component — wasi:http exposes none); duplicate request headers join
with `", "`; a bare-string response body is REFUSED (and a PATHNAME body --
lack-app-file's file-serving form, a distinct value since todo-304 -- is
refused as unsupported until the transport can serve a file); of the
function-response protocol the DELAYED form is supported and the streaming
writer refused.
Still out of scope per `.todo/223`: WebSocket (`clack.socket`) and
`:swank-port`.

## How the `:rontolisp` backend picks its transport (the one-clackup-source rule)

`:server :rontolisp` means "serve on THIS target's native inbound transport",
and the choice is made at COMPILE time by the reader features — which is what
lets ONE source (`examples/net/httpbin-clack.lisp`, clackup line included) run
unchanged on the interpreter, the JVM, `wasmtime serve` AND a reactor host
(verified on all four, workerd via `wrangler dev` for the reactor leg,
2026-08-09). Since todo-335 the FETCH-capable shape rides the same rule:
`examples/cloudflare-workers/dog-fetcher/worker.lisp` is `:server :rontolisp`
and runs on all four too — JDK client / wasi:http (its serve leg needs
`wasmtime serve -S cli=y -S tcp=y -S inherit-network=y`, because clack's
socket leg keeps wasi:sockets in the import surface) / `env.fetch` under
`--no-wasi --host-fetch` (re-verified on all four legs, wrangler dev included,
2026-08-12):

- `#-rontolisp-wasm` (interpreter / JVM): the stoppable socket server above.
- `#+(and rontolisp-wasm (not rontolisp-reactor))`: the
  `rontolisp:http-handler` directive leg below — `wasmtime serve` under
  `--component`, the call-time error on Preview 1.
- `#+rontolisp-reactor` (`--no-wasi` — Preview 1 only, the compiler ignores it
  under `--component` so the feature does too — and `--no-gc`;
  `Features.WASM_REACTOR`, selected in `RontoLispCli.compileRecorded`): run
  stores the app in the SHARED reactor store and leaves the
  `rontolisp::%http-reactor` marker; the compiler answers with the synthesized
  `handle-request` export. `:port`/`:address` are ignored, run returns at
  once. Pinned by `RontoLispCliTest.clackRontolispBackendUnderNoWasi*` and its
  without-`--no-wasi` twin.

WHY a reader feature and not a front-end rewrite: the shim is the ONE place
that already branches per target (`#+rontolisp-wasm`), builtin-shim sources are
read with the target's features (`ShimLibraries.forms`), and keeping both legs
in the shim means the directive simply does not EXIST in a reactor compile — no
Java pass has to coordinate "directive present AND marker present" or dodge a
duplicate-export synthesis. WHY not `:server :auto`: it would leave
`:rontolisp` meaning "owns a socket" and cost every ported program a source
edit, which is the asymmetry the rule exists to remove. The reactor leg rides
the same shared machinery as the explicit `:reactor` backend (see
below), so the two designators cannot drift. If a future host type cannot be
told apart by compile flags, this scheme is the thing to re-evaluate — the
feature reflects the TARGET, not the deployment vendor.

## WASM component / Preview 1

The shim's WASI-wasm `run` (`#+(and rontolisp-wasm (not rontolisp-reactor))`)
stores the app and calls the
`rontolisp:http-handler` DIRECTIVE with the literal quoted `'%app` (a one-line
`(funcall *app* env)` indirection — the directive requires a literal quoted
name) plus `:raw-body :buffered` — inside a defun body. `HttpLibrary.process` (and `HttpHandlerInliner.usesHttpHandler`)
therefore detect the directive NESTED in a form (quoted data excluded),
extract the static handler name for the `%serve-handle` export wiring, and
lower the call site to nil: instantiation runs the top-level program (clackup
stores `*app*`, `run` returns at once), then requests arrive through the
exported `handle`. The generated `%serve-dispatch` bridge + `wasm-export` are
appended AFTER the program so the handler NAME resolves where the directive was
written — against the shim's own (spliced) defpackage for the qualified `'%app`,
and against the current package for a user's own unqualified top-level
`(rontolisp:http-handler 'my-handler)`.

That position is also why the three names the bridge SYNTHESIZES —
`%serve-dispatch`, `%serve-request-body` and the exported `%serve-handle` — carry
an explicit `cl-user::` qualifier. The program's last `in-package` is still in
effect at the append point, so unqualified they came out as
`MY-APP::%SERVE-DISPATCH` while http.lisp — spliced at the HEAD, where `cl-user`
is current — calls the unqualified ones, and the `--component` compile of any
application that ends inside its own package died with `Cannot compile:
%SERVE-DISPATCH`. `cl-user::` normalizes to the bare name in every package, so
the two spellings meet whatever the program did (found 2026-08-08 by the
tiny-routes serve leg, whose routes are read in `:tr-app`; pinned by
`ClackE2eTest.tinyRoutesServesOnWasmComponentUnderWasmtimeServe`). The handler
reference deliberately stays unqualified — qualifying it would break the
user-directive case above.

Run flags: `wasmtime serve -W
gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y` — the socket
flags because the spliced usocket shim (a clack dependency) wit-imports
wasi:sockets. `:use-thread` is effectively nil (no `:thread-support` on WASM)
and `clack:stop` is meaningless under `wasmtime serve`.

**Two Preview-1 policy changes shipped here (the todo-195 call-time-error
policy, extended):** `rontolisp:http-handler` on Preview 1 is now a CALL-time
error stub (was a compile error) with the same "requires --component" message,
and so are `stream-read`/`stream-close`/`streamp` when no stream type exists
(Preview 1, or a non-async component) — both sit in the shim's wasm `run`/
`%app`, which are DEAD code on Preview 1 but are compiled whenever clack
loads. An uncaught error is a silent trap on Preview 1, so the E2E pins the
message through `handler-case`.

## The host-driven reactor: `http-reactor.lisp` + two thin designators

The reactor TRANSPORT — the one application store, the JSON envelope over
`%http-make-env` / `%http-normalize-response`, the handler-case that answers
500, and the compile-time marker — is the SHARED internal library
`http-reactor.lisp` (`rontolisp::%http-reactor-register` / `-handle` /
`-dispatch`; `eval/HttpReactorLibrary`; envelope documented in that file's
header). It exists for the hosts that call an EXPORTED FUNCTION instead of
handing the program a socket — a Cloudflare Worker, and any other
`wasm-export` / `--no-wasi` reactor embedding (the browser, node, a JVM host).
The compile path splices it whenever the program references a
`%http-reactor-*` name (`RontoLispCli`, right after `HttpReactorInliner` and
BEFORE `HttpServerLibrary`, whose entry points it calls; `JsonLibrary` later
picks up its json call sites); the interpreter lazy-loads it through the
`RONTOLISP::%HTTP-REACTOR-` function-lookup hook, which must sit BEFORE the
broader `%HTTP-` hook (every public entry is a function, so the first touch is
always a call). Unlike the builtin shims it is NOT excluded from
`LibraryDefunPruner` — but its consumers are the (unprunable) shim runs, so in
practice it is present exactly when a reactor designator is.

TWO designators reach it, and that is deliberate:

- **`:server :rontolisp` under `#+rontolisp-reactor`** — the one-source rule
  above: reactor when the TARGET is a reactor.
- **`:server :reactor`** (`clack-handler-reactor.lisp`, package
  `clack.handler.reactor`, both system spellings in
  `ShimLibraries.RESOURCES` / `BuiltinSystems` / `resource-config.json`, again
  NOT seeded in `PackageRegistry`) — host-driven on EVERY backend: its `run`
  stores the app where `:rontolisp` would bind a socket, which is what lets a
  Worker (or a browser, node or JVM embedding) be developed and driven through
  `dispatch` on the interpreter (`hello-clack/check.lisp`). Since the
  `:rontolisp` reactor leg landed it is no longer NEEDED for a Worker — one
  `:rontolisp` source covers every host — and both designators store into the
  ONE shared `%http-reactor-app`, so a program that mixes them (clack always
  splices `clack-handler-rontolisp`; the user quickloads the reactor one on
  top) stays coherent.

The explicit designator is driven by **`clackup`**, like every other handler
backend:

```lisp
(ql:quickload "clack-handler-reactor")
(load "app.lisp")
(clack:clackup #'app :server :reactor :use-thread nil)
```

Below `clackup` sit two functions, and BOTH are public: `handle`
`(app request-json &optional body) -> response-json` is the adapter, and
`dispatch` `(request-json &optional body) -> response-json` runs it over the
application `run` stored — both thin names over the shared
`%http-reactor-handle` / `-dispatch`, so the designators cannot drift. A
program that wants no clack at all can still call `handle` directly with its
own `wasm-export` — that is the shape this shim shipped with, and it still
works.

### The head and the body source (todo-341 Phase 2)

The transport takes a request HEAD — the JSON envelope — and a BODY SOURCE,
the optional second argument above. The source is an abstract Lisp value, one
of `nil` (no body), a STRING (already buffered), or a PULL THUNK: arity 0,
answering the next chunk, `nil` or `""` for end of stream, and possibly a
FUTURE of one so a suspending host import is a legal source
(`%http-reactor-pull` resolves it, the same rule `%stream-new` applies at the
read — this transport is synchronous code where `await` is not legal). The
envelope's own `"body"` key is exactly the STRING case, and is the fallback
when the caller passes no source, which is what keeps every host glue file
written before the split working unchanged.

ONE transport therefore serves every backend: on the interpreter and the JVM
the host passes a closure or a string directly, and on the WASM backends the
synthesized export builds the thunk over a host import — the next section.

### The WASM boundary: a head export and a body import (todo-341 Phase 2b)

A host on the other side of a wasm boundary cannot pass a Lisp closure, so
`HttpReactorInliner` writes the source itself. Beside the `handle-request`
export it synthesizes, on the Preview 1 core-module backend, an import and the
thunk over it:

```lisp
(rontolisp:wasm-import '%reactor-read-body :from "env" :as "readRequestBody"
                       :params '() :returns :bytes :async t)
(defun %reactor-read-chunk ()
  (let ((%reactor-buf (rontolisp::%http-reactor-buffer 65536)))
    (rontolisp::%http-reactor-chunk %reactor-buf (%reactor-read-body %reactor-buf))))
(defun %reactor-dispatch (%reactor-json)
  (rontolisp::%http-reactor-dispatch %reactor-json (function %reactor-read-chunk)))
```

so the whole boundary is `handle-request(headPtr, headLen) -> (ptr, len)` plus
`env.readRequestBody(ptr, cap) -> n`. Four decisions in that, each load-bearing:

- **`:bytes`, not `:string`** (`.kb/wasm-import.md`): the string decoder is
  non-validating, so a binary body cannot cross it, and a `:string` RESULT is
  host-allocated per call, which is what makes chunking pointless (todo-341
  findings 2 and 4). The `:bytes` result is caller-buffered and the wrapper pops
  its staging, so the body costs NO linear memory: measured, a 256 KiB body a
  handler drops leaves `memory.buffer.byteLength` where it was, where the
  envelope used to hold the body about 17 times over.
- **the import is CALLED, never taken as `#'value`**: the build's
  suspending-import report follows calls, and an escaped import widens its
  answer to "any export may suspend" (`.kb/wasm-import.md`).
- **`:async t`**, so the HOST picks the strategy over one module: answering
  synchronously (read the body, then call in — what every checked-in Worker glue
  does) and suspending inside the call (`WebAssembly.Suspending` over the
  request's own reader, entered through `promising`, calls serialised) are both
  legal. The declaration is also what puts the re-entry guard on the exports,
  which is the difference between a supported streaming host and a silent
  corruption.
- **Preview 1 core modules only.** `--component` keeps the whole body inside the
  envelope's `"body"` key: a `wasm-import` is refused there (a component's host
  functions cross the canonical ABI) and so is the packed array behind
  `:bytes`. Re-evaluate when the component path grows a `list<u8>` lift —
  nothing else about the split is Preview-1-specific. `--no-gc` keeps it too and
  cannot serve at all anyway (below).

A HAND-WRITTEN reactor (one that exports `handle-request` itself, so nothing is
synthesized) writes those three forms itself:
`examples/cloudflare-workers/httpbin/worker.lisp` is the worked example, and it
is also where the two boundaries meet — the same file is built as a core module
AND as a component by `httpbin-component/`, so its import is guarded by
`#-rontolisp-component`. That feature exists for exactly this
(`reader/Features.COMPONENT`, added under `--component` by the CLI): the reactor
features cannot stand in for it, because a `--component --no-wasi` build IS a
reactor and carries them too.

Pinned by `WasmReactorBodyE2eTest` (node, a host that shares the module's
memory: a body whose every character straddles a chunk boundary, a reader that
answers 0 meaning no body, the envelope fallback, a binary body, and the flat
linear memory), `HttpReactorInlinerTest`'s two bridge-shape tests, and
`RontoLispCliTest.aComponentBuildReadsTheSourceWithTheComponentFeature`.

**A chunk is a string OR OCTETS** (an `(unsigned-byte 8)` vector), and both
drains read through ONE adapter, `%http-reactor-text-source`, so neither has to
know which arrived. Octets are the shape a byte-shaped host boundary has — the
`:bytes` import of Phase 2b reads into a REUSABLE buffer, which is the only
convention that keeps linear memory flat (the todo's finding 2), and a `:string`
result cannot carry arbitrary bytes at all (finding 4). Two rules the adapter
owns, and both are load-bearing:

- **an open UTF-8 sequence is carried into the next chunk**
  (`%http-reactor-decode-chunk`, over `http-server.lisp`'s
  `%http-utf8-complete-end` / `%http-utf8-decode-octets`). The host that cut the
  body into chunks read whatever the socket gave it and knows nothing about code
  points, so a multi-byte character straddling a boundary is the NORMAL case,
  not a corner one; decoding each chunk independently answers two malformed
  characters per split. A body that ENDS mid-sequence keeps the decoder's lenient
  rule — the bytes come back as characters rather than being dropped.
- **the adapter never answers `""` before the end**: an empty answer IS end of
  stream to both consumers, and a chunk whose every byte was carried over
  decodes to nothing. It pulls again instead.

**A host READER is a source in two lines** (`%http-reactor-buffer` /
`%http-reactor-chunk`) — the `read(2)` shape every byte-shaped boundary takes,
and the shape the WASM `:bytes` import has: the caller owns one buffer and hands
it over, the reader fills up to its length and answers how many octets it wrote
(possibly as a FUTURE — a suspending import answers one), and 0 is end of
stream. Both halves live in the transport rather than in each host's bridge
because BOTH are load-bearing: the buffer is allocated once and REUSED for every
chunk of every request (a buffer per chunk grows the host's memory by the whole
body — the todo's finding 2 — while one buffer grows it by nothing), and the
count-to-chunk step is where the octets are copied out before the next read
overwrites them. Reuse is sound because a reactor answers one request at a time;
the re-entry guard a suspending module carries (`.kb/wasm-import.md`) refuses the
overlap that would share the buffer.

**An EMPTY source is no body, and costs one look-ahead.** Once the body stops
riding the envelope, "is there a body at all" is a question only the host can
answer — a reader answers 0 for a bodiless GET — so `%http-reactor-raw-body`
pulls once before deciding, and pushes that chunk back
(`%http-reactor-pushback`) so the application still gets it. What the rule
preserves is upstream's: `:raw-body` is nil for a bodiless request in BOTH modes
(lack guards it with `(when raw-body ...)`), which an unread pull source would
otherwise turn into a stream every GET pays for. The same look-ahead is what
lets an empty source FALL BACK to the envelope's own `"body"` key
(`%http-reactor-request-body`), so a host may start handing over a reader
without also having to stop filling the envelope in the same commit.

Which SHAPE the application then sees is the `:raw-body` mode, and on a
reactor the mode is REGISTERED with the app (`%http-reactor-register app
[:buffered]`, the `%http-reactor-buffered` flag) rather than read off a
directive, because a reactor has no `http-handler` call at run time:

- **`:buffered`** — what both Clack handler backends' `run` registers, and
  what `clack.handler.reactor:handle` always passes: the source is drained
  (`%http-reactor-body-text`) into `http-server.lisp`'s bivalent Gray stream,
  so a lack middleware's `read-byte` / `file-position` work as before.
- **`:stream` (the default)** — `%http-reactor-body-stream` builds a
  first-class rontolisp pull stream over the source with
  `rontolisp::%stream-new` (todo-341 Phase 1), so the portable
  `(await (read-all (getf env :raw-body)))` drain works on a reactor too. That
  is what closed the todo's finding 6: `rontolisp:http-handler`'s `:raw-body`
  argument used to be DROPPED by `HttpReactorInliner.lowerHttpHandler` and the
  reactor always buffered, so `examples/net/httpbin.lisp` — whose drain is the
  portable one — answered 500 on `--no-wasi` as soon as a request carried a
  body. The mode now rides the synthesized registration.

An empty or absent body stays `nil` in BOTH modes: upstream guards `:raw-body`
with `(when raw-body ...)`, and a bodiless GET must not pay for a stream.

`--no-gc` has neither mode: it rejects the async surface by name, and
`http-server.lisp`'s own `%http-drain` / `%http-serve-request` are
`async-defun`s, so that backend cannot carry the HTTP transport at all —
with or without a body stream. Its reactors are the `wasm-export`-only ones
(`examples/cloudflare-workers/hello`).

Pinned by the `http-reactor-body-source` ci-spec case (all four backends: a
pull thunk, an in-band string and no body at all through the default mode,
then the same pull source through `:buffered`, then an OCTET pull source
through both modes whose every character straddles a chunk boundary, then the
same two modes over a HOST READER filling a four-octet buffer, and a reader that
is empty answering both nil and the envelope fallback), the five
`LispEvaluatorAsdfTest` reactor-body tests, and
`HttpReactorInlinerTest.theLoweredHttpHandlerDirectiveKeepsItsRawBodyMode`.

**And a program can skip the shim too.** `handle` is thirty lines over
`%http-make-env` / `%http-normalize-response` plus the JSON envelope, so a
reactor that wants NO clack package in the module at all can write those lines
itself and export its own `handle-request`.
`examples/cloudflare-workers/httpbin` is exactly that: the same five endpoints
answering the same documents as `examples/cloudflare-workers/httpbin-clack`,
over the SAME envelope (one `src/index.js`, byte-identical between them). The
two are written in their own idiom rather than kept diff-clean against each
other (2026-08-10) — the library-free one is plain defuns and a `dispatch`
`cond`, the clack one an application FUNCTION plus a middleware — but the
endpoints and the envelope are fixed, so the pair is still a controlled
measurement of what clack
costs on a reactor, and the answer is size and a little startup — node 24, same
machine, `--no-wasi --optimize`, re-measured 2026-08-08 after the dispatch-gate
refinement halved the clack build (`.kb/optimize-dead-code-elimination.md`,
"The symbol BUILDERS no longer bail"; the clack column stood at 1,691,678 B /
376,239 B gzip with a 19.4 ms `_initialize` before it):

| | hand-written adapter | `clackup` + clack |
| --- | --- | --- |
| module | 248,956 B / 76,076 B gzip | 534,777 B / 146,707 B gzip |
| `_initialize`, cold | 4.5 ms | 4.8 ms |
| warm `GET` / `POST` | 0.023 / 0.038 ms | 0.024 / 0.039 ms |

(The example `build.sh` lines pass `--optimize=size` since todo-295: the pair
moved to 200,155 / 58,793 and 474,150 / 124,756 gzip that day, for a
warm-request price of 3-11 µs — `.kb/optimize-dead-code-elimination.md`, "What
ROUTING costs a clack module". The `--optimize` columns above stay as the
controlled clack-vs-no-clack measurement. Both tables are RECORDS of their own
experiment, not the current build: after the CLOS-lowering pass, the
one-source cutover (middlewares default-ON, the thin shim) and the CLOS-aware
library pruning (`.kb/library-defun-pruning.md`, 2026-08-09 — lack-util's
unreferenced ironclad/core leaves) the pair stands at
**178,971 / 54,648** and **264,277 / 79,438** (gzip -9 -n, 2026-08-09) —
what the example READMEs now carry, and what `build.sh` reproduces.)

The hand-written half still pays for being a PORTABLE Clack application: naming
`%http-make-env` splices `http-server.lisp` — env builder, response normalizer,
and the buffered `:raw-body` Gray stream
(`HttpServerLibrary.referencesBufferedBody` keeps that half when the program
mentions `%http-body-stream`). Measured when that shape landed (both builds were
roughly twice today's size then): ~140 KB over the pre-Clack handler it replaced
(283,200 B).

- `%http-reactor-handle` is `(app request-json &optional body buffered) ->
  response-json` (the head and the body source, above). It converts
  nothing itself: it builds the raw tuple and calls `rontolisp::%http-make-env`
  / `%http-normalize-response`, exactly as every other transport does, so it
  cannot drift from what a SERVED request sees. All that is left in the library
  is the JSON envelope, documented in `http-reactor.lisp`'s own header.
- **A FUTURE-valued application answer is resolved at the boundary (todo-335)**:
  before normalizing, the handle checks `futurep` and resolves through
  `rontolisp::%future-force` — the FUNCTION spelling of await's resolve, legal
  in this synchronous transport where the `await` special form is not
  (interpreter = `awaitValue`, JVM = the `_await` runtime, non-asyncMode WASM =
  `_p1_future_await`, asyncMode = the `_sched_loop` force; `LispNames`
  documents the table). That is what lets an `async-defun` handler — or a
  tiny-routes ROUTE returning an async-defun's future, the fetch-capable Worker
  shape — run on the reactor; on a reactor the future is settled at creation,
  so the force never blocks. The socket transports already had this courtesy
  through `%http-serve-request`'s await.
- **The bare `rontolisp:http-handler` DIRECTIVE lowers to this transport under
  `--no-wasi` (todo-335, `HttpReactorInliner.lowerHttpHandler`)**: a reactor
  owns no socket, so the directive can only mean the host-driven envelope —
  every `(rontolisp:http-handler 'name ...)` becomes
  `(progn (%http-reactor-register (function name) [:buffered]) (%http-reactor '%http-reactor-dispatch "handle-request"))`
  in the CLI, before the serve-mode switch reads the program (so
  `--component --no-wasi` compiles it as a zero-import reactor component
  instead of hitting the serve+no-wasi ctor error). The port is dropped
  unevaluated; the `:raw-body` pair is NOT — it rides the registration
  (todo-341 Phase 2, above). One `http-handler` source now
  serves a socket on the interpreter/JVM, wasi:http under `--component`, and
  `handle-request` on a reactor — `examples/net/dog-fetcher.lisp` unedited is
  the pin (`RontoLispCliTest`, the reactor-component invoke case in
  `WasmLispCompilerIntegrationTest`).
- **The envelope is an API now** — `{method, target, headers, body, scheme,
  remote-addr}` in, `{status, headers, body}` out. Two parts of it are
  load-bearing and were both found by measurement: `target` is the RAW request
  target (path and query still joined, still encoded — `%http-make-env` owns
  that split, and a pre-split path leaves `:query-string` nil), and the response
  `headers` cross as an ARRAY of `[name, value]` pairs, not an object, so a
  repeated `Set-Cookie` survives. The pairs are built into a VECTOR
  (`%http-reactor-header-pairs`, todo-296): a headerless Clack response — tiny-routes'
  `(ok "x")` is one — must cross as `[]`, and json-stringify renders an empty
  LIST as `false`, which the Headers constructor on the JS side throws on. No
  shipped example had ever produced a headerless response, which is why this
  survived until a routed one did.
- **`%http-reactor-handle` CATCHES and answers 500.** On a reactor an uncaught
  Lisp error is a trap that takes the whole instance down and the host must
  throw the instance away; catching is what every other rontolisp transport
  already does with a handler error. The consequence to know: loading the
  reactor library puts `handler-case` in the module, so the program compiles in
  EH mode.
- **`run` stores the app; the EXPORT is synthesized by the compiler.** A reactor
  owns no socket, so `run` binds nothing: it calls `%http-reactor-register` and
  returns nil (`stop` is nil too). What replaces the socket is an export, and
  `rontolisp:wasm-export` needs a LITERAL name at compile time — which a program
  whose whole body is a `clackup` call cannot supply. So `run` carries a marker,
  `(rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch
  "handle-request")`, that `eval/HttpReactorInliner` lowers to nil on the WASM
  backends and answers by APPENDING
  `(defun %reactor-dispatch (json) (rontolisp::%http-reactor-dispatch json))`
  plus its `wasm-export` after the program. BOTH shims' runs carry that marker
  (the cloudflare one on every WASM read, the `:rontolisp` one under the
  reactor feature) naming the SAME shared dispatcher, so when a program
  splices both the two markers are identical and the inliner's first-wins is
  not a choice (`HttpReactorInlinerTest.twoIdenticalMarkersSynthesizeOneBridge`).
  The precedent is exact:
  `HttpLibrary` reads the `http-handler` directive nested in the
  `clack-handler-rontolisp` shim's `run` the same way. It is a SEPARATE marker,
  not an overload of `http-handler`, because that directive means "bind a
  socket" on the WASI targets — which is exactly what made the `:rontolisp`
  backend trap on reactors before its reactor leg existed (`.todo/281`'s
  original `_initialize TRAPPED` symptom was that directive, not `clackup`).
- **The cloudflare shim's marker is `#+rontolisp-wasm` and nothing defines it.**
  On the interpreter and the JVM the shims never read the form, because there
  is no export to synthesize: the host calls `dispatch` as an ordinary
  function. That is why `dispatch` is EXPORTED from the package rather than
  being a compiler-only internal, and why `run` does not `defun` a
  `handle-request` of its own — a library defining a function into the user's
  namespace is surprising, and `check.lisp` would then pin a name only one
  backend needs.
  Consequence, and the reason `HttpReactorInliner.declaresExport` exists: the
  marker fires for any WASM program that merely quickloads the shim, `clackup`
  called or not — exactly like `HttpLibrary`'s nested-directive detection. For
  the PRE-clackup shape (the user writes the `wasm-export` and calls `handle`
  from it, still supported and still documented) that would append a SECOND
  export named `handle-request`, and a module with a duplicate export name is
  one no engine will compile — measured on V8: `CompileError: Duplicate export
  name 'handle-request'`. So the synthesis is SKIPPED when the program already
  declares that export name itself (`:as` alias or defaulted); the marker is
  lowered either way, because nothing defines it. Pinned by
  `HttpReactorInlinerTest.doesNotSynthesizeWhenTheProgramAlreadyExportsThatName`
  and its `:as` twin.
- **`clackup`'s two `format t` calls are why `--no-wasi` stdout is a sink.**
  Upstream clackup prints a start-up banner (`(and (not use-thread) (not
  silent))` — and `use-thread` defaults to nil on WASM, so it always fires) and
  `clack.handler:run` prints a debug NOTICE (`debug` defaults to `t`). Both are
  in third-party source. The alternatives were making every Worker author pass
  `:silent t :debug nil` (the asymmetry this whole feature exists to remove) or
  binding `*standard-output*` inside `run` (impossible — the banner is printed
  BEFORE `run` is applied), so the fix went where the cause is: under
  `--no-wasi` the `fd_write` stub discards instead of trapping
  (`.kb/wasm-export-no-wasi.md` has the full reason and the output-only rule).
- **The one keyword the examples still pass is a per-HOST fact, not
  incantation.** `:use-thread nil` because the interpreter and the JVM HAVE
  `:thread-support`, so clackup would otherwise apply `run` — the app store —
  on another thread and race the next form (on WASM it is already the
  default). `:use-default-middlewares nil` was dropped everywhere when
  `.todo/283` landed (the standard-stream `symbol-value`s are bound on the
  compile paths now): lack's `backtrace` middleware — which prints even on an
  error the application CATCHES, via its handler-bind — writes its report to
  `*error-output*`, a discarding sink under `--no-wasi` and real stderr
  everywhere else, and the response stays correct (verified end to end on
  workerd with an unparseable-JSON body: the report is discarded, the 200
  `"json":null` answer comes back).

**Why the designator names the TRANSPORT and not a deployment vendor.** It was
`:server :cloudflare-workers` / `clack-handler-cloudflare-workers` until
2026-08-09; renamed, with no compatibility alias kept. Nothing in the shim is
Cloudflare-specific (it is four delegations to `%http-reactor-*`), and once the
`:rontolisp` reactor leg landed the vendor name no longer even carried "this is
how you deploy to Workers" — that is `:rontolisp` plus `--no-wasi`. What was
left is "host-driven on EVERY backend", which is the MECHANISM, and the
mechanism is what every other name in this lineage already says
(`http-reactor.lisp`, `%http-reactor-*`, `#+rontolisp-reactor`,
`Features.WASM_REACTOR` — and "reactor" is the WASI term of art for exactly
this shape). The ecosystem convention the vendor name was justified by does not
point at vendors either: `clack-handler-hunchentoot` / `-woo` / `-fcgi` name
server implementations and protocols, so a transport name IS the conventional
shape here. The re-evaluation trigger, and the reason not to bring the old name
back as an alias: it is now free for what would really deserve it — a backend
surfacing something only one host has (a Worker's `env`/`ctx` bindings, say),
layered on top of this one. The internals (`%http-reactor-*`) are named for the
mechanism because no user names them, exactly like `%http-server-*`. Pinned by
`LispEvaluatorAsdfTest` (`theReactorHandlerShim*` — now exercising the
delegation and the interpreter's lazy reactor-library hook), by
`HttpReactorInlinerTest` (the
marker lowering and the synthesized export), by `RontoLispCliTest` (the
`:rontolisp` reactor leg end to end through the CLI compile), and by
`examples/cloudflare-workers/hello-clack/`,
`examples/cloudflare-workers/httpbin-clack/` +
`examples/cloudflare-workers/httpbin-tiny-routes/`, whose `check.lisp`s drive
`dispatch` on the interpreter, the JVM and wasm-GC (`examples/examples.yaml`).
The clack-free half of the measured pair,
`examples/cloudflare-workers/httpbin/`, is pinned the same way by its
`check.lisp`: same three backends, same requests, so a divergence between the
shared `handle` and a hand-written one shows up as two manifest cases
disagreeing.

**The two designators get one example directory each, and the pair is the
point.** `examples/cloudflare-workers/httpbin-clack` holds its own
`worker.lisp` (`:server :reactor`) plus a `check.lisp` that drives `dispatch`
on the interpreter, the JVM and Preview 1 — the clackup half of the measured
pair with `httpbin/`, whose `check.lisp` runs the same probes through the
hand-written adapter, so a divergence between the shared `handle` and a copy of
it shows up as two manifest cases disagreeing.
`examples/cloudflare-workers/httpbin-clack-one-source` holds NO Lisp at all:
its `build.sh` compiles `net/httpbin-clack.lisp` ITSELF (`:server :rontolisp`),
which is the one-source rule as a deployable artifact — and it needs no
`check.lisp`, because on the interpreter that file binds a real socket, which
is its own verification. The old `app.lisp` / `worker.lisp` / `serve.lisp`
split existed because the transport was a hand-written adapter that had to be
kept out of the application; a `:server` designator needs no such quarantine,
which is why each directory is one file plus its check.

Measured on the deployed Worker when `clackup` replaced the hand-written
`wasm-export` + `defun` (node 24, same machine, `--no-wasi --optimize`;
absolute sizes predate the 2026-08-08 gate refinement that later halved the
build — the +9% ratio is what this paragraph records): the
module grew 1,575,467 -> 1,691,678 B raw (342,761 -> 373,999 B gzip, +9%) and
`_initialize` went 23 -> 56 ms (median of five runs each), while the per-request cost did not move
(warm `GET` 0.071 -> 0.068 ms, warm `POST` 0.121 ms both). So `clackup` is a
STARTUP cost on a reactor, not a request cost — which on Cloudflare is paid once
per isolate and reported by `wrangler deploy` (Worker Startup Time 14 -> 25 ms).

## Compile-path enablers that are NOT clack-specific

Landed with this milestone, each with its own pin:

- **Nested/computed `asdf:load-system` / `ql:quickload` compile to call-time
  error stubs** (were compile errors) and **nested/computed
  `asdf:find-system` lowers to args-then-nil**
  (`LispMacroExpander.expandRuntimeFindSystem`): lack's
  `find-package-or-load` has all three inside a defun, guarded by a
  `find-package` probe the baked table answers for every spliced system — the
  asdf calls are dead at run time but must compile. Divergence vs the
  interpreter's live registry (which answers real + builtin systems) is
  deliberate: a compiled program has no system registry and can load nothing.
- **`with-open-file` with a non-native option VALUE (`:if-exists :append`
  etc.) expands to a call-time stub** instead of throwing at expansion —
  lack-middleware-backtrace's file-output branch, dead under the default
  `'*error-output*` output.
- **`FreeVarAnalyzer` walks `typecase`/`etypecase` clause HEADS as type
  specifiers** (keyform + bodies only) in both the free and captured walks —
  the backtrace middleware's `(or pathname string)` head inside a capturing
  lambda used to be read as a variable named PATHNAME. Walked structurally,
  not expanded: the expansion needs the class registry for a class-name head.
  Pinned by `JvmLispCompilerTest.compileAndRunEtypecaseInsideACapturingLambda`.
- **`PATHNAME` resolves as a CL name** so `(typecase app ((or
  pathname string) ...))` under `(in-package :clack)` resolves the type name
  to CL's rather than `clack::pathname`. It sat in `PackageRegistry.CL_TYPES`
  while nothing satisfied the type; todo-304 made `pathname` a FUNCTION too
  (the constructor of the distinct pathname value, `.kb/pathnames.md`), so
  the name moved to `CL_FUNCTIONS` and resolves in both positions.
