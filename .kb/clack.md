# Clack (`(ql:quickload "clack")` + `clack:clackup` on the rontolisp backend)

Clack loads VERBATIM from the Quicklisp dist (clack-20250622-git + lack-20260101-git);
`clack:clackup :server :rontolisp` runs on the interpreter, the JVM, `--native`, WASM
`--component`, a reactor host, and a Servlet 6 war. A plain Preview 1 `.wasm` has no incoming
TCP (`.kb/tcp-sockets.md`): `clackup` signals the `http-handler` message at CALL time. Out of
scope: WebSocket (`clack.socket`), `:swank-port`.

Pins: `ClackE2eTest` (`RONTOLISP_CLACK_E2E=1`; Docker, embedded Tomcat, network), with a bare
handler lambda and tiny-routes + its cookie middleware, unpatched.

## Two routing layers, and why both
tiny-routes (`ClackE2eTest`) and ningle v0.3.0 + myway (`NingleE2eTest`,
`RONTOLISP_NINGLE_E2E=1`) load unpatched on all four transports. ningle adds: the app as a
CLOS object (`defmethod call :around`, `call-next-method`, `(setf find-class)`); requirement
closures compiled at route-DEFINITION time over a `loop ... on ... by #'cddr` pair
(`.kb/loop-iteration-heads.md`); every request through `lack-request` (~2 MB of a ~2.7 MB
module; a Worker needs the `--no-wasi` stubs for `random` and `getenv`,
`.kb/wasm-export-no-wasi.md`; no size opt-in like `tiny-routes/lite`, myway compiles every
rule to a cl-ppcre scanner); the one request BODY parse on the normal path (yason). Its
`not-found` returns nil, so lack answers a body LIST holding NIL (`.kb/http-server.md`).
Preview 1 runs ningle's routing via `examples/cloudflare-workers/{hello,httpbin}-ningle/check.lisp`.

## The handler backend is a built-in shim, found LATE-BOUND by name
`clack-handler-rontolisp.lisp` defines `clack.handler.rontolisp` (`run`/`stop`). Clack finds
it via lack's `find-package-or-load`: `find-package`, and only on a MISS `asdf:load-system`,
then `(apply (intern "RUN" pkg) ...)`.
- **The package is NOT seeded in `PackageRegistry`** (unlike every other shim): seeding
  satisfies the probe before the system loads and leaves `run` undefined. The interpreter's
  builtin-system branch of `loadSystem` uses the RESOLVING `eval(form)`, not
  `eval(form, globalEnv)`.
- **Two system names**: `find-package-or-load` hyphenates `/` but not `.`, so clack asks ASDF
  for `clack.handler.rontolisp`; `clack-handler-rontolisp` maps to the same resource.
- **The compile paths splice the shim EAGERLY with clack** (`LoadInliner.spliceSystem`); the
  probe reads the baked package table and `RUN` resolves through `_lookup`
  (`.kb/symbol-runtime-api.md`).

## run / stop and the stoppable server seam
Interpreter/JVM `run` rides `rontolisp::%http-server-*` (`RontoHttpServer`) under an
`unwind-protect`; `%http-server-start` takes the handler as a FUNCTION VALUE.
- Default `:use-thread t`: `clack:stop` DESTROYS `run`'s thread and never calls our `stop`.
  `Thread.interrupt` lands in `joinServer`'s latch await, join returns NORMALLY, the unwind
  stops the server. `:use-thread nil`: `run` blocks forever (hunchentoot parity).
- JVM shares the directive's `_httpHandlerFn` static slot: **ONE Clack server per process.**
- `:use-thread t` needs `:thread-support` (interpreter/JVM, `.kb/threads.md`); WASM lacks it.
- rontolisp's server protocol IS Clack's (`.kb/http-server.md`); the shim only asks for
  `:raw-body :buffered`, the synchronous bivalent stream lack-request / http-body read.

## Transport selection (the one-clackup-source rule)
`:server :rontolisp` = "serve on THIS target's inbound transport", chosen at COMPILE time by
reader features, so ONE source runs on six transports (`examples/net/httpbin-clack.lisp`).
`:port` reads `PORT` on the socket transports only.
- `#-rontolisp-wasm`: the socket server.
- `#+rontolisp-native`: the runner's server (`.kb/http-server.md`, "--native: the runner's
  server"); never returns.
- `#+(and rontolisp-wasm (not rontolisp-reactor) (not rontolisp-native))`: the
  `rontolisp:http-handler` directive. **The `(not rontolisp-native)` is load-bearing**: a
  `--native` module is a Preview 1 build with `:rontolisp-wasm` set.
- `#+rontolisp-reactor` (`--no-wasi`, Preview 1, `--no-gc`): run stores the app and leaves the
  `rontolisp::%http-reactor` marker; the compiler synthesizes `handle-request`; run returns at
  once. Pinned by `RontoLispCliTest.clackRontolispBackendUnderNoWasi*`. The Java subset's
  `com.sun.net.httpserver` reaches it through `%http-serve-begin` (`.kb/java-frontend.md`,
  "A server as a reactor"; `FetchSpecE2eTest#javaHttpServer`'s `reactor` leg is the one test
  where the generated `worker()` answers real HTTP).
- `#+rontolisp-servlet` (`-o app.war`): the war's top level must RETURN, so run registers via
  the same `%http-server-start` (a dead handle) and returns. **Trap: `:use-thread` is not
  merely ignored** — default `t` runs `run` on a thread held at the class-initialization lock
  until `<clinit>` returns, so registration landed after the container looked (3 of 10
  succeeded); hence the initializer's bounded wait and the volatile slot. The war legs keep
  the default `:use-thread` deliberately.

**The legs live in ONE place, `http-serve.lisp`** (`%http-serve` / `%http-serve-stop`;
`eval/HttpServeLibrary`), shared with the Clojure `ring.adapter.rontolisp/run-server`
(`.kb/clojure-frontend.md`, "Ring adapter") so the adapters cannot drift. Read with the
TARGET's features.
- Compile path: the FIRST pass of `CompileFrontend.expand` (after the async-sugar rewrite):
  every leg is what a later pass reads (directive, `:raw-body` scan, reactor marker).
- Interpreter: an EXACT-name hook (`HttpServeLibrary.isServeFunction`) ahead of the
  `RONTOLISP::%HTTP-` prefix hook, which would load `http-server.lisp` instead.
- `resource-config.json` carries the file (`NativeImageResourceConfigTest`).
- **A second pair for an adapter whose program goes on and ends when its server stopped**
  (the Java subset): `%http-serve-begin` binds and answers the server at once on EVERY leg,
  `%http-serve-run` answers once it stopped, `%http-serve-port` the bound port or nil. On
  `--native` run IS the serving loop, ended by `%http-serve-stop`'s flag; under a host run is
  nothing.

Why a reader feature and not a front-end rewrite: shim sources are already read with the
target's features, and the directive then does not EXIST in a reactor compile. **The feature
reflects the TARGET, not the vendor.**

## WASM component / Preview 1
The WASI leg calls the directive with the literal `'rontolisp::%http-serve-app` inside a
defun, so `HttpHandlerInliner.usesHttpHandler` detects it NESTED (quoted data excluded) and
lowers the call site to nil; the `%serve-dispatch` bridge + export are appended AFTER the
program so the handler name resolves where the directive was written.
- **Trap: the synthesized `%serve-dispatch`, `%serve-request-body`, `%serve-handle` carry an
  explicit `cl-user::`.** The program's last `in-package` is in effect at the append point;
  unqualified, every `--component` compile ending inside its own package failed with `Cannot
  compile: %SERVE-DISPATCH`. The handler reference stays unqualified.
- Run with `wasmtime serve -S cli=y -S inherit-network=y` (usocket imports wasi:sockets).
- **Preview 1**: the directive (and `stream-read`/`stream-close`/`streamp`) is a call-time
  error stub ("requires --component"). An uncaught error is a silent trap there, so the E2E
  pins the message through `handler-case`.

## The host-driven reactor: `http-reactor.lisp` + two designators
The reactor transport (one app store, the JSON envelope, the 500 catch, the compile-time
marker) is the shared library `http-reactor.lisp`, for hosts that call an EXPORTED FUNCTION
instead of handing over a socket. Spliced when a `%http-reactor-*` name is referenced, BEFORE
`HttpServerLibrary`; the interpreter's `%HTTP-REACTOR-` lookup hook must sit BEFORE the
broader `%HTTP-` hook.

Both **`:server :rontolisp` under `#+rontolisp-reactor`** and **`:server :reactor`**
(`clack-handler-reactor.lisp`, again NOT seeded) store into the ONE `%http-reactor-app`.
`:reactor` is host-driven on EVERY backend, so a Worker can be developed through `dispatch`
on the interpreter.

### Body SOURCE
`nil`, a STRING, or a PULL THUNK (next chunk, `nil`/`""` at end, possibly a FUTURE).
- **Normalized at every entry**: a host's JSON `null` parses as the symbol `NULL`, non-nil
  and not a string; it used to be FUNCALLED.
- **An EMPTY source is no body, at one look-ahead** (pull once, push back), so `:raw-body` is
  nil for a bodiless request in both modes and an empty source can fall back to the
  envelope's `"body"` key. A head framing an empty body (`Content-Length: 0`) gets an empty
  stream (`.kb/http-server.md`).

### Body SINK
An optional function of one chunk (possibly answering a FUTURE). Given one, every chunk goes
to it and the head's `"body"` key is dropped.
- **The key is ABSENT, not empty**: "crossed out of band" must differ from "empty string".
- **Chunks cross BEFORE the head**, so a head that HAS a `"body"` key wins over what was
  written; that makes a handler error mid-body recoverable.
- **Octets stay octets** (a stream body is forwarded chunk by chunk, byte-exact).
  Flattening lets every UTF-8-encoding transport double high bytes (`ff fe 41` -> `c3 bf c3 be
  41`). The ONE arm that cannot take octets is the no-sink arm (a JSON string key): lenient
  UTF-8 text.
- Two backend bugs this surfaced, pinned by ci-spec
  `stream-new-builds-a-pull-stream-on-every-backend`: the JVM answered `(consp a-stream)` = T
  (a stream is an `Object[3]`), and `%future-force` trapped in an asyncMode module with no
  scheduler (forcing POLLS there, `WasmFutureRuntimeBuilder.buildSyncForce`).

### The WASM boundary: a head export and two body imports
A host across a wasm boundary can pass neither closure nor sink, so `HttpReactorInliner`
synthesizes, beside `handle-request(headPtr, headLen) -> (ptr, len)`, the imports
`env.readRequestBody(ptr, cap) -> n` and `env.writeResponseBody(ptr, len)`. Decisions:
- **`:bytes`, not `:string`** (`.kb/wasm-import.md`): the string decoder is non-validating (a
  binary body cannot cross) and a `:string` RESULT is host-allocated per call. With `:bytes`
  the CALLER owns the memory both ways; a body costs NO linear memory.
- **The imports are CALLED, never taken as `#'value`**: an escaped import widens the
  suspending-import report to "any export may suspend". Hence `%reactor-write-chunk` is a
  defun.
- **`:async t`**, so the HOST picks synchronous or suspending (`WebAssembly.Suspending`) over
  one module; it also puts the re-entry guard on the exports.
- **Only Preview 1 core-module REACTORS, only under `--host-boundary=streaming`.**
  `--component` refuses `wasm-import` (re-evaluate with a `list<u8>` lift); `wasmtime run`
  satisfies no `env.*` import (modules refused to INSTANTIATE), hence
  `HttpReactorInliner.process`'s `reactor` flag. The DEFAULT is `envelope`: the split costs
  ~1% bytes but host-side STATE (`compiler/HostBoundary`). **Trap: a rebuild without the flag
  moves bodies IN BAND, destroying a binary one.**
- **Under `--reentrant` both imports lead with an `:int` CALL ID**, carried as `"call-id"`
  (`ReactorEnvelope.CALL_ID_KEY`) and closed over the body thunks where the envelope is
  parsed, so consumers stay id-less and the flag-off shape is byte-identical. The shared
  receive buffer survives overlap because a chunk is copied out before anything can suspend.
- A hand-written reactor writes those forms itself
  (`examples/cloudflare-workers/httpbin/worker.lisp`) under `#+rontolisp-body-imports`.
- Pinned by `WasmReactorBodyE2eTest`, `WasmReactorResponseBodyE2eTest`,
  `HttpReactorInlinerTest`, `RontoLispCliTest.aComponentBuildReadsTheSourceWithTheComponentFeature`,
  `WasmReactorStreamingHostE2eTest`. The generated `worker()` answers the imports
  SYNCHRONOUSLY (a suspending one forces per-request serialisation); `.todo/348` is the trigger.

**Memory**: a chunk-at-a-time drain of a 4 MiB body costs ~850 BYTES of arena and no linear
growth; a drain that KEEPS the body costs the body (`.kb/read-load-streams.md`). The host
reader is the `read(2)` shape over ONE buffer reused for every chunk of every request (sound:
one request at a time, the re-entry guard refuses overlap), shared with `--host-fetch`'s reply
body (`.kb/fetch-http.md`).

**A chunk is a string OR OCTETS.** `:stream` mode decodes nothing (every HTTP body stream
answers octets). The TEXT drain's adapter: **an open UTF-8 sequence is carried into the next
chunk** (a straddling character is the NORMAL case), and **it never answers `""` before the
end** (empty IS end of stream; a chunk wholly carried over pulls again).

### `:raw-body` mode is REGISTERED with the app
`%http-reactor-register app [:buffered]`, because a reactor has no `http-handler` call at run
time. `:buffered` (both shims' `run`, `clack.handler.reactor:handle`): drained to OCTETS into
the bivalent Gray stream. `:stream` (default): a first-class pull stream of octet chunks, so
`(await (read-all (getf env :raw-body)))` works and the stream relays byte-exact as a response
body. `--no-gc` cannot carry the HTTP transport at all (it rejects the async surface). Pinned
by ci-spec `http-reactor-body-source` and `http-reactor-body-sink` (all four backends),
`LispEvaluatorAsdfTest`'s reactor-body tests,
`HttpReactorInlinerTest.theLoweredHttpHandlerDirectiveKeepsItsRawBodyMode`,
`LispEvaluatorAsdfTest.aReactorSinkTakesTheResponseBodyOutOfTheHead`.

### The transport's contract, arm by arm
- **A FUTURE-valued answer is resolved at the boundary** with `rontolisp::%future-force`
  (legal where `await` is not; per-backend table in `LispNames`), so an `async-defun` handler
  runs on a reactor.
- **The bare directive lowers to this transport under `--no-wasi`**
  (`HttpReactorInliner.lowerHttpHandler`, before the serve-mode switch, so `--component
  --no-wasi` gives a zero-import reactor component). Pin: `examples/net/dog-fetcher.lisp`,
  unedited.
- **The envelope is an API**: in `{method, target, headers, body, scheme, remote-addr}` (+
  optional `script-name`, a RAW prefix of `target`), out `{status, headers, body}`. `target` is
  the RAW request target (`%http-make-env` owns the split). Response `headers` are an ARRAY of
  `[name, value]` pairs (a repeated `Set-Cookie` survives), built as a VECTOR: json-stringify
  renders an empty LIST as `false`, which the JS Headers constructor throws on.
- **The handler CATCHES and answers 500**: an uncaught Lisp error traps and takes the instance
  down. So the library puts `handler-case` in the module (EH mode). `--native` calls the
  uncaught `%http-reactor-answer` instead (`.kb/http-server.md`, "A failure is the socket
  transports' 500").
- **The failure head is the socket transports' 500, its report under `"error"`**
  (`%http-failure-response`). The generated `worker()` logs `HTTP-HANDLER: handler failed:` +
  `head.error` and builds the `Response` from the other keys. Rejected: glue recognising a
  special JSON arm (an app may answer that very head) and printing to `*error-output*` (a sink
  under `--no-wasi`, doubled under `--host-output`). Pins:
  `WasmHostGlueE2eTest#theEmittedWorkerHalfIsTheWholeHostOnTheEnvelopeBoundary` (`/boom`),
  `WasmReactorResponseBodyE2eTest` (`/half`), ci-spec `http-reactor-body-sink`,
  `LispEvaluatorAsdfTest#theReactorHandlerShimAnswers500RatherThanLettingAnErrorEscape`,
  `java-http-server-spec.yaml` `a-handler-that-throws-after-the-head`.
- **`run` stores the app; the EXPORT is synthesized**: `wasm-export` needs a LITERAL name, so
  `run` carries the marker, which WASM lowers to nil plus an appended `%reactor-dispatch` and
  export. Both shims name the SAME dispatcher
  (`HttpReactorInlinerTest.twoIdenticalMarkersSynthesizeOneBridge`). Not an overload of
  `http-handler`, which means "bind a socket" on WASI targets.
- **The reactor shim's marker is `#+rontolisp-wasm`**, never read on interpreter/JVM — why
  `dispatch` is EXPORTED. **Trap (`HttpReactorInliner.declaresExport`)**: it fires for any WASM
  program that merely quickloads the shim, so a program declaring its own `handle-request` got
  a SECOND one (V8: `Duplicate export name`); synthesis is skipped then.
- **`clackup`'s two `format t` calls are why `--no-wasi` stdout is a sink** (third-party
  source; the banner precedes anything `run` could bind).
- **The examples pass `:use-thread nil`**: interpreter/JVM have `:thread-support`, and
  `run` on another thread would race the next form.

### Why the designator names the TRANSPORT, not a vendor
Formerly `:server :cloudflare-workers`, renamed with no alias: nothing in the shim is
Cloudflare-specific. The old name is left free for a backend surfacing what one host alone
has (a Worker's `env`/`ctx`). Pins: `LispEvaluatorAsdfTest` (`theReactorHandlerShim*`),
`HttpReactorInlinerTest`, `RontoLispCliTest`, the `check.lisp` of
`examples/cloudflare-workers/{hello-clack,httpbin-clack,httpbin-tiny-routes}`.

## The clack-free twin
`examples/cloudflare-workers/httpbin` answers the same five endpoints as `httpbin-clack` over
the SAME envelope, a controlled measurement: clack costs module size (~1.5x) and a little
`_initialize` startup, not per-request time. It keeps a hand-written host because only the
SYNTHESIZED bridge is recognised as the envelope's entry point (`.kb/wasm-import.md`).

## Compile-path enablers that are NOT clack-specific
- **Nested/computed `asdf:load-system` / `ql:quickload` / `asdf:find-system` compile** (lack
  calls them inside a defun) against the spliced asdf runtime (`.kb/asdf.md`).
- **`with-open-file` with a non-native option VALUE (`:if-exists :append`) expands to a
  call-time stub**, not an expansion error (lack-middleware-backtrace).
- **`FreeVarAnalyzer` walks `typecase` clause HEADS as type specifiers**: `(or pathname
  string)` in a capturing lambda was read as a variable. Pinned by
  `JvmLispCompilerTest.compileAndRunEtypecaseInsideACapturingLambda`.
- **`PATHNAME` resolves as a CL name** (also a function; `.kb/pathnames.md`).
