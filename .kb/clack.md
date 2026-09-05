# Clack (`(ql:quickload "clack")` + `clack:clackup` on the rontolisp backend)

Clack loads VERBATIM from the Quicklisp dist (clack-20250622-git + lack-20260101-git);
`clack:clackup :server :rontolisp` runs on the interpreter, the JVM, WASM `--component`, a
reactor host, and a Servlet 6 war. Preview 1 has no incoming TCP (`.kb/tcp-sockets.md`): the
program compiles and `clackup` signals the `http-handler` message at CALL time. Out of scope:
WebSocket (`clack.socket`), `:swank-port`.

Pins: `ClackE2eTest` (opt-in `RONTOLISP_CLACK_E2E=1`; Docker for pinned wasmtime, embedded
Tomcat for the war, network for the first quickload), run twice — a bare handler lambda and a
real ROUTING application (tiny-routes + its cookie middleware, unpatched).

## Two routing layers, and why both
**tiny-routes** (`ClackE2eTest` group 2) and **ningle** v0.3.0 + myway + map-set
(`NingleE2eTest`, `RONTOLISP_NINGLE_E2E=1`) both load unpatched and serve on all four
transports. ningle exercises what tiny-routes does not:
- The application is a CLOS object (`lack-component`): `defmethod call :around` +
  `call-next-method` + `(setf (find-class '<app>) (find-class 'app))`, not a closure.
- Dispatch runs REQUIREMENT closures compiled at route-DEFINITION time
  (`ningle/route::compile-requirements` closes over a `loop for (name val) on ... by #'cddr`
  pair) — what `.kb/loop-iteration-heads.md` exists for.
- It reads every request through `lack-request`: the http-body / fast-http / smart-buffer /
  circular-streams / quri / yason / trivial-mimes chain, ~2 MB of a ~2.7 MB module. A ningle
  Worker needed the `--no-wasi` stubs for `random` and `getenv`
  (`.kb/wasm-export-no-wasi.md`). No size opt-in (myway compiles every rule to a cl-ppcre
  scanner), unlike `tiny-routes/lite`.
- Only ningle parses a request BODY on the normal path (`request-parameters` -> yason, which
  makes its own stream), so until `make-string-input-stream` existed every JSON body answered
  400 (`.kb/read-load-streams.md`). `ningle:not-found` sets the status and returns nil, so
  lack's `finalize-response` answers a body LIST holding NIL (`.kb/http-server.md`).
- Preview 1 cannot serve either, but ningle's ROUTING runs there via
  `examples/cloudflare-workers/{hello,httpbin}-ningle/check.lisp`.

## The handler backend is a built-in shim, found LATE-BOUND by name
`clack-handler-rontolisp.lisp` (`ShimLibraries`/`BuiltinSystems` + `resource-config.json`)
defines package `clack.handler.rontolisp` exporting `run`/`stop`. Clack's discovery is
`find-handler` -> lack's `find-package-or-load` -> `find-package`, and only on a MISS
`asdf:find-system` + `asdf:load-system`, then `(apply (intern "RUN" pkg) ...)`.
- **The package is NOT seeded in `PackageRegistry`** (unlike every other shim): seeding
  satisfies the probe before the system loads and leaves `run` undefined at the apply. The
  shim carries its own `defpackage`, and the interpreter's builtin-system branch of
  `loadSystem` uses the RESOLVING `eval(form)`, not `eval(form, globalEnv)`.
- **Two system names**: `clack-handler-rontolisp` and `clack.handler.rontolisp` —
  `find-package-or-load` hyphenates `/` but leaves `.` alone, so the dotted one is what clack
  asks ASDF for. Both keys map to one resource.
- **The compile paths splice the shim EAGERLY with clack** (`LoadInliner.spliceSystem`, right
  after system `"clack"`); the probe then reads the baked package table and
  `CLACK.HANDLER.RONTOLISP:RUN` resolves through `_lookup` (`.kb/symbol-runtime-api.md`).

## run / stop and the stoppable server seam
`run` blocks until the server stops and cleans up in an unwind. Interpreter/JVM ride
`rontolisp::%http-server-*` (`RontoHttpServer.startServer/joinServer/stopServer/serverPort`;
opaque integer handle, INTERNAL symbols) under an `unwind-protect`.
- `%http-server-start` takes the handler as a FUNCTION VALUE (unlike the directive's quoted
  name), port (0 = ephemeral, `%http-server-port` reads it back) and bind address.
- Default `:use-thread t`: `run` is on a `bt2:make-thread` thread and `clack:stop` DESTROYS
  it, never calling our `stop` — `destroy-thread` is `Thread.interrupt`, it lands in
  `joinServer`'s latch await, join returns NORMALLY, the unwind stops that server.
  `stopServer` is idempotent. `:use-thread nil`: `run` blocks forever (hunchentoot parity).
- JVM: `JvmHttpServerSeamCompiler` stores the handler in the same `_httpHandlerFn` static slot
  the directive uses (`usesHttpHandler` also fires on `%http-server-start`). **ONE Clack
  server per process.**
- `:use-thread t` works only because `Features` INTERPRETER/JVM include `:thread-support`
  (`.kb/threads.md`); WASM lacks it, so the default is nil there.

rontolisp's server protocol IS Clack's (`.kb/http-server.md`): the shim hands the app to
`%http-server-start` DIRECTLY and asks only for `:raw-body :buffered` — the synchronous
bivalent body stream that lets lack-request / circular-streams / http-body read a served body.

## Transport selection (the one-clackup-source rule)
`:server :rontolisp` = "serve on THIS target's native inbound transport", chosen at COMPILE
time by reader features, so ONE source runs on five transports
(`examples/net/httpbin-clack.lisp`; `hello-clack.lisp` is the four-form minimum). `:port`
reads the `PORT` env var, which only the socket transports look at. The manifest pins four of
five legs per file.
- `#-rontolisp-wasm` — the socket server above.
- `#+(and rontolisp-wasm (not rontolisp-reactor))` — the `rontolisp:http-handler` directive
  leg: `wasmtime serve` under `--component`, call-time error on Preview 1.
- `#+rontolisp-reactor` (`--no-wasi`, Preview 1 only, and `--no-gc`; `Features.WASM_REACTOR`,
  selected in `RontoLispCli.compileRecorded`) — run stores the app in the SHARED reactor store
  and leaves the `rontolisp::%http-reactor` marker; the compiler synthesizes the
  `handle-request` export. `:port`/`:address` ignored, run returns at once. Pinned by
  `RontoLispCliTest.clackRontolispBackendUnderNoWasi*`.
- `#+rontolisp-servlet` (`-o app.war`; `Features.JVM_SERVLET`, `.kb/http-server.md`) — the
  container owns the port and the war's top level must RETURN, so run hands the app to the
  SAME `%http-server-start` seam (which in war mode registers and answers a dead handle) and
  returns. No bind, no join; `stop` is nil. `:script-name` makes a war under a context path
  route. **Trap: `:use-thread` is not merely ignored here** — default `t` spawns a thread for
  `run`, held at the class-initialization lock until `<clinit>` returns, so registration
  landed just AFTER the container looked (3 successes in 10) until `RontoHttpServletInitializer`
  got a bounded wait and the handler slot was made VOLATILE. The war legs leave `:use-thread`
  at its default deliberately.

Why a reader feature and not a front-end rewrite: the shim already branches per target,
builtin-shim sources are read with the target's features (`ShimLibraries.forms`), and the
directive then does not EXIST in a reactor compile. **The feature reflects the TARGET, not
the vendor.**

## WASM component / Preview 1
The shim's WASI-wasm `run` stores the app and calls the `rontolisp:http-handler` DIRECTIVE
with literal quoted `'%app` plus `:raw-body :buffered`, inside a defun body.
`HttpLibrary.process` / `HttpHandlerInliner.usesHttpHandler` therefore detect the directive
NESTED in a form (quoted data excluded), extract the static handler name for `%serve-handle`
export wiring, and lower the call site to nil. The `%serve-dispatch` bridge + `wasm-export`
are appended AFTER the program so the handler NAME resolves where the directive was written.
- **Trap: the three synthesized names — `%serve-dispatch`, `%serve-request-body`,
  `%serve-handle` — carry an explicit `cl-user::` qualifier.** The program's last
  `in-package` is still in effect at the append point, so unqualified they came out as
  `MY-APP::%SERVE-DISPATCH` while http.lisp (spliced at the HEAD, under `cl-user`) calls the
  unqualified ones; every `--component` compile ending inside its own package died with
  `Cannot compile: %SERVE-DISPATCH`. The handler reference stays unqualified.
- Run flags: `wasmtime serve -S cli=y -S tcp=y -S inherit-network=y` (the spliced usocket
  shim wit-imports wasi:sockets). `:use-thread` is effectively nil; `clack:stop` is
  meaningless under `wasmtime serve`.
- **Preview-1 call-time-error policy**: `rontolisp:http-handler` on Preview 1 is a CALL-time
  error stub with the "requires --component" message, as are
  `stream-read`/`stream-close`/`streamp`. An uncaught error is a silent trap there, so the
  E2E pins the message through `handler-case`.

## The host-driven reactor: `http-reactor.lisp` + two designators
The reactor TRANSPORT — one application store, the JSON envelope over
`%http-make-env`/`%http-normalize-response`, the handler-case that answers 500, the
compile-time marker — is the SHARED internal library `http-reactor.lisp`
(`rontolisp::%http-reactor-register`/`-handle`/`-dispatch`; `eval/HttpReactorLibrary`). It
serves hosts that call an EXPORTED FUNCTION instead of handing over a socket.

Splicing: the compile path splices it when the program references a `%http-reactor-*` name
(`RontoLispCli`, after `HttpReactorInliner`, BEFORE `HttpServerLibrary`). The interpreter
lazy-loads through the `RONTOLISP::%HTTP-REACTOR-` function-lookup hook, which must sit
BEFORE the broader `%HTTP-` hook. NOT excluded from `LibraryDefunPruner`.

Two designators, both storing into the ONE shared `%http-reactor-app`: **`:server :rontolisp`
under `#+rontolisp-reactor`**, and **`:server :reactor`** (`clack-handler-reactor.lisp`,
package `clack.handler.reactor`, both system spellings in `ShimLibraries.RESOURCES`/
`BuiltinSystems`/`resource-config.json`, again NOT seeded) — host-driven on EVERY backend, so
a Worker can be developed through `dispatch` on the interpreter. Two public functions below
`clackup`: `handle` `(app request-json &optional body) -> response-json` and `dispatch`
`(request-json &optional body) -> response-json`.

### Body SOURCE
A BODY SOURCE is `nil`, a STRING (buffered), or a PULL THUNK — arity 0, answering the next
chunk, `nil`/`""` for end of stream, possibly a FUTURE of one (`%http-reactor-pull` resolves
it; this transport is synchronous code where `await` is not legal).
- **Normalized at every entry.** A host's "no body" is JSON `null`, which `json-parse`
  answers as the symbol `NULL` — non-nil and not a string, it used to be FUNCALLED.
  `%http-reactor-source` is the one guard (`nil` / string / `functionp`, else no body).
- **An EMPTY already-buffered source is end of stream at the FIRST read.**
  `%http-reactor-text-source`'s rule is "never answer `""` before the end"; `sent` starts true
  for an empty source.
- **An EMPTY source is no body, and costs one look-ahead**: `%http-reactor-raw-body` pulls
  once before deciding and pushes the chunk back (`%http-reactor-pushback`), preserving
  upstream's rule that `:raw-body` is nil for a bodiless request in BOTH modes. The same
  look-ahead lets an empty source FALL BACK to the envelope's `"body"` key.

### Body SINK
An optional SINK is a function of one chunk, possibly answering a FUTURE
(`%http-reactor-write` resolves through `%http-reactor-force`). Given one,
`%http-reactor-body-out` writes every chunk to it and the head's `"body"` key is **dropped**.
- **The key is ABSENT, not empty** — "crossed out of band" must be distinguishable from "the
  empty string".
- **Chunks cross BEFORE the head**, so a head that HAS a `"body"` key WINS over anything
  already written — that is what makes a handler error mid-body recoverable. The error arm
  passes no sink.
- **A STREAM body is forwarded, not collected** with a sink (`%http-reactor-stream-chunk`,
  each chunk an OCTET vector, so a relayed fetch reply crosses byte-exact); without a sink it
  is DRAINED (`%http-reactor-body-drain`, the synchronous twin of `%http-drain`).
- **Octets stay octets.** `%http-body-string` returns an `(unsigned-byte 8)` body unchanged;
  flattening lets every UTF-8-encoding transport double the high bytes
  (`ff fe 41` -> `c3 bf c3 be 41`). The ONE arm that cannot take octets is
  `%http-reactor-body-out`'s no-sink arm (a JSON string key), which renders them as lenient
  UTF-8 TEXT (`%http-reactor-body-envelope-text`).
- Two pre-existing backend bugs surfaced here, pinned by ci-spec
  `stream-new-builds-a-pull-stream-on-every-backend`: **the JVM answered `(consp a-stream)` =
  T** (a stream is an `Object[3]`), fixed by `JvmEmitHelper.emitAsyncValueExclusion` gated on
  `Ctx.mayUseAsyncValues`; **`%future-force` trapped in an asyncMode module with no
  scheduler** (`OFF_SCHED_LOOP` was an unreachable stub) — forcing is POLLING there, as on
  Preview 1's `_p1_future_await` (`WasmFutureRuntimeBuilder.buildSyncForce`).

### The WASM boundary: a head export and two body imports
A host across a wasm boundary can pass neither closure nor sink, so `HttpReactorInliner`
synthesizes, on the Preview 1 core-module backend, beside the `handle-request` export,
`wasm-import`s of `env.readRequestBody` (`:returns :bytes :async t`) and
`env.writeResponseBody` (`:params '(:bytes) :async t`), plus `%reactor-read-chunk` (over
`%http-reactor-buffer 65536` + `%http-reactor-chunk`), `%reactor-write-chunk` (over
`%http-reactor-octets`) and `%reactor-dispatch` passing both as `#'` values. Boundary:
`handle-request(headPtr, headLen) -> (ptr, len)`, `env.readRequestBody(ptr, cap) -> n`,
`env.writeResponseBody(ptr, len)`. Five load-bearing decisions:
- **`:bytes`, not `:string`** (`.kb/wasm-import.md`): the string decoder is non-validating so
  a binary body cannot cross, and a `:string` RESULT is host-allocated per call. `:bytes` is
  caller-buffered and the wrapper pops its staging, so a body costs NO linear memory.
- **The DIRECTION flips, same rule**: in is a `:bytes` RESULT into a module-supplied buffer,
  out a `:bytes` PARAMETER the wrapper stages and pops — the CALLER owns the memory either
  way, so no host holds a pointer across the call.
- **The imports are CALLED, never taken as `#'value`**: the suspending-import report follows
  calls, and an escaped import widens to "any export may suspend". Hence
  `%reactor-write-chunk` is a defun.
- **`:async t`**, so the HOST picks the strategy over one module (synchronous answers and
  suspending inside the call via `WebAssembly.Suspending`/`promising` are both legal); it
  also puts the re-entry guard on the exports.
- **Preview 1 core modules, only a REACTOR, and only under `--host-boundary=streaming`.**
  CAN: `--component` keeps both bodies in the envelope (a `wasm-import` is refused there);
  re-evaluate when the component path grows a `list<u8>` lift. IS: `clack-handler-reactor` is
  host-driven everywhere and there the host is `wasmtime run`, which satisfies no `env.*`
  import — declaring one made those modules refuse to INSTANTIATE, so
  `HttpReactorInliner.process` takes a `reactor` flag. ASKED FOR: the DEFAULT is `envelope`;
  the split costs not bytes (~1%) but host-side STATE. `compiler/HostBoundary` is the
  vocabulary; the flag joins the other two in `bodyOutOfBand`. **Trap: a rebuild without the
  flag moves bodies IN BAND, destroying a binary one.**
- **Under `--reentrant` both imports lead with an `:int` CALL ID.** The host mints an id per
  request, the envelope carries it as `"call-id"` (`ReactorEnvelope.CALL_ID_KEY`), and the
  transport closes it over the body thunks at the ONE place it parses the envelope
  (`%http-reactor-bind-source`/`-bind-sink`), so consumers stay on the id-less 0-arity source
  and the id-less shape is byte-identical with the flag off. The shared receive buffer
  (`%http-reactor-chunk-buffer`) survives the overlap because `%http-reactor-chunk` copies out
  before anything can suspend.
- A HAND-WRITTEN reactor writes those forms itself
  (`examples/cloudflare-workers/httpbin/worker.lisp`), guarded by `#+rontolisp-body-imports`
  (`reader/Features.BODY_IMPORTS`), with the envelope fallback under the negation.
- Pinned by `WasmReactorBodyE2eTest`, `WasmReactorResponseBodyE2eTest`,
  `HttpReactorInlinerTest`'s bridge-shape tests,
  `RontoLispCliTest.aComponentBuildReadsTheSourceWithTheComponentFeature`, and
  `WasmReactorStreamingHostE2eTest`. No checked-in glue streams yet — a suspending body import
  forces promising/queue serialisation per request, so the generated `worker()` answers those
  imports SYNCHRONOUSLY; `.todo/348` is the trigger.

**Memory**: the boundary is flat both ways, and DRAINING is flat too —
`%http-utf8-decode-octets` decodes per character with `write-char` and a WASM string output
stream appends into one GC byte buffer, so a chunk-at-a-time drain of a 4 MiB body costs ~850
BYTES of arena and no linear growth. A drain that KEEPS the body costs the body
(`.kb/read-load-streams.md`).

**A chunk is a string OR OCTETS.** The TEXT drain reads through ONE adapter,
`%http-reactor-text-source`; `:stream` mode's `%http-reactor-body-stream` reads through the
byte-shaped mirror `%http-reactor-octet-source` and decodes nothing, since every HTTP body
stream answers octets (`.kb/fetch-http.md`). Two rules the text adapter owns:
- **An open UTF-8 sequence is carried into the next chunk** (`%http-reactor-decode-chunk` over
  `%http-utf8-complete-end`/`%http-utf8-decode-octets`): a straddling multi-byte character is
  the NORMAL case, and per-chunk decoding answers two malformed characters per split.
- **It never answers `""` before the end** — an empty answer IS end of stream to both
  consumers, and a chunk whose bytes were all carried over decodes to nothing. It pulls again.

**A host READER is a source in two lines** (`%http-reactor-buffer` / `%http-reactor-chunk`) —
the `read(2)` shape: the caller owns one buffer, the reader fills up to its length and answers
the octet count (possibly a FUTURE), 0 is end of stream. Both halves live in the transport:
the buffer is allocated once and REUSED for every chunk of every request, and the
count-to-chunk step copies out before the next read overwrites; sound because a reactor
answers one request at a time and the re-entry guard refuses the overlap. Second consumer:
`--host-fetch`'s reply body (`.kb/fetch-http.md`), sharing the one buffer.

### `:raw-body` mode is REGISTERED with the app
`%http-reactor-register app [:buffered]` (the `%http-reactor-buffered` flag), because a
reactor has no `http-handler` call at run time:
- **`:buffered`** — what both handler shims' `run` registers and what
  `clack.handler.reactor:handle` always passes: the source is drained
  (`%http-reactor-body-octets`) into `http-server.lisp`'s bivalent Gray stream so
  `read-byte`/`file-position` work. The drain answers OCTETS, not text, so a binary upload is
  byte-exact; `%http-body-stream` takes EITHER spelling.
- **`:stream` (default)** — `%http-reactor-body-stream` builds a first-class pull stream of
  OCTET chunks with `rontolisp::%stream-new`, so the portable
  `(await (read-all (getf env :raw-body)))` drain works and answering the stream as a response
  body relays byte-exact. Before it, `HttpReactorInliner.lowerHttpHandler` DROPPED the
  `:raw-body` argument and the reactor always buffered.
- An empty or absent body stays `nil` in BOTH modes. `--no-gc` has neither: it rejects the
  async surface by name and `%http-drain`/`%http-serve-request` are `async-defun`s, so it
  cannot carry the HTTP transport at all.
- Pinned by ci-spec `http-reactor-body-source` and `http-reactor-body-sink` (all four
  backends), six `LispEvaluatorAsdfTest` reactor-body tests,
  `HttpReactorInlinerTest.theLoweredHttpHandlerDirectiveKeepsItsRawBodyMode`,
  `LispEvaluatorAsdfTest.aReactorSinkTakesTheResponseBodyOutOfTheHead`.

### The transport's contract, arm by arm
- `%http-reactor-handle` is `(app request-json &optional body buffered sink) ->
  response-head-json`. It converts nothing: it builds the raw tuple and calls
  `%http-make-env` / `%http-normalize-response` like every other transport.
- **A FUTURE-valued application answer is resolved at the boundary**: `futurep` +
  `rontolisp::%future-force`, the FUNCTION spelling of await's resolve, legal in this
  synchronous transport where the `await` special form is not (`LispNames` documents the
  per-backend table). That lets an `async-defun` handler run on a reactor.
- **The bare `rontolisp:http-handler` DIRECTIVE lowers to this transport under `--no-wasi`**
  (`HttpReactorInliner.lowerHttpHandler`): it becomes `(progn (%http-reactor-register
  (function name) [:buffered]) (%http-reactor '%http-reactor-dispatch "handle-request"))`,
  before the serve-mode switch reads the program (so `--component --no-wasi` compiles a
  zero-import reactor component instead of the serve+no-wasi ctor error). The port is dropped
  unevaluated. `examples/net/dog-fetcher.lisp` unedited is the pin.
- **The envelope is an API**: `{method, target, headers, body, scheme, remote-addr}` in (plus
  optional `script-name`, the mount point as a RAW prefix of `target`), `{status, headers,
  body}` out. `target` is the RAW request target (path and query joined and encoded —
  `%http-make-env` owns the split, and a pre-split path leaves `:query-string` nil), and
  response `headers` cross as an ARRAY of `[name, value]` pairs, not an object, so a repeated
  `Set-Cookie` survives. The pairs are built into a VECTOR (`%http-reactor-header-pairs`): a
  headerless response must cross as `[]`, and json-stringify renders an empty LIST as `false`,
  which the JS Headers constructor throws on.
- **`%http-reactor-handle` CATCHES and answers 500** — an uncaught Lisp error on a reactor is
  a trap that takes the instance down. Consequence: loading the reactor library puts
  `handler-case` in the module, so the program compiles in EH mode.
- **`run` stores the app; the EXPORT is synthesized.** `rontolisp:wasm-export` needs a LITERAL
  name at compile time, so `run` carries the marker `(rontolisp::%http-reactor
  'rontolisp::%http-reactor-dispatch "handle-request")`; `eval/HttpReactorInliner` lowers it
  to nil on the WASM backends and APPENDS `%reactor-dispatch` plus its `wasm-export`. BOTH
  shims' runs carry that marker naming the SAME dispatcher
  (`HttpReactorInlinerTest.twoIdenticalMarkersSynthesizeOneBridge`). A SEPARATE marker, not an
  overload of `http-handler`, because that directive means "bind a socket" on WASI targets.
- **The reactor shim's marker is `#+rontolisp-wasm` and nothing defines it** — on
  interpreter/JVM the shims never read the form, which is why `dispatch` is EXPORTED rather
  than compiler-only. **Trap (`HttpReactorInliner.declaresExport`)**: the marker fires for any
  WASM program that merely quickloads the shim, so for the PRE-clackup shape it would append a
  SECOND `handle-request` export (V8: `CompileError: Duplicate export name`). Synthesis is
  SKIPPED when the program already declares that export name (`:as` alias or defaulted).
- **`clackup`'s two `format t` calls are why `--no-wasi` stdout is a sink** — upstream prints
  a banner and `clack.handler:run` a debug NOTICE, third-party source, and binding
  `*standard-output*` inside `run` is impossible (the banner precedes the apply).
- **The one keyword the examples still pass**: `:use-thread nil`, because interpreter/JVM HAVE
  `:thread-support` and clackup would otherwise apply `run` on another thread and race the
  next form. `:use-default-middlewares nil` was dropped once the standard-stream
  `symbol-value`s were bound on the compile paths.

### Why the designator names the TRANSPORT, not a vendor
Renamed from `:server :cloudflare-workers` / `clack-handler-cloudflare-workers` with no
compatibility alias — nothing in the shim is Cloudflare-specific and deployment to Workers is
`:rontolisp` plus `--no-wasi`. The old name is left free for a backend surfacing something
only one host has (a Worker's `env`/`ctx`), layered on this one. Pins:
`LispEvaluatorAsdfTest` (`theReactorHandlerShim*`), `HttpReactorInlinerTest`,
`RontoLispCliTest`, and the `check.lisp` of
`examples/cloudflare-workers/{hello-clack,httpbin-clack,httpbin-tiny-routes}`.

## The clack-free twin
`examples/cloudflare-workers/httpbin` is a hand-written adapter answering the same five
endpoints as `httpbin-clack` over the SAME envelope, so the pair is a controlled measurement:
clack costs module size (roughly 1.5x) and a little `_initialize` startup, not per-request
time. The clack one takes the generated `worker()`; the library-free one keeps a hand-written
host because it exports `handle-request` by hand and only the SYNTHESIZED bridge is recognised
as the envelope's entry point (`.kb/wasm-import.md`). The hand-written half still pays for
being a PORTABLE Clack application: naming `%http-make-env` splices `http-server.lisp`
(`HttpServerLibrary.referencesBufferedBody` keeps the buffered half when the program mentions
`%http-body-stream`). Layout: `httpbin-clack` holds `worker.lisp` (`:server :reactor`) + a
`check.lisp`; `httpbin-clack-one-source` holds NO Lisp — its `build.sh` compiles
`net/httpbin-clack.lisp` itself (`:server :rontolisp`).

## Compile-path enablers that are NOT clack-specific
- **Nested/computed `asdf:load-system` / `ql:quickload` / `asdf:find-system` compile** (were
  compile errors): lack's `find-package-or-load` has all three inside a defun. They resolve to
  the spliced asdf runtime's real defuns over the baked `%asdf-registry%`; the historical stub
  lowerings (`LispMacroExpander.expandRuntimeFindSystem`) survive only as the no-pipeline
  fallback (`.kb/asdf.md`).
- **`with-open-file` with a non-native option VALUE (`:if-exists :append`) expands to a
  call-time stub** instead of throwing at expansion — lack-middleware-backtrace's file branch.
- **`FreeVarAnalyzer` walks `typecase`/`etypecase` clause HEADS as type specifiers** (keyform
  + bodies only) in both walks — `(or pathname string)` inside a capturing lambda was read as
  a variable named PATHNAME. Pinned by
  `JvmLispCompilerTest.compileAndRunEtypecaseInsideACapturingLambda`.
- **`PATHNAME` resolves as a CL name**, moved from `PackageRegistry.CL_TYPES` to
  `CL_FUNCTIONS` once `pathname` became a FUNCTION too (`.kb/pathnames.md`).
