# The server-side HTTP value model (`rontolisp:http-handler` = Clack)

A handler RECEIVES the Clack env plist and RETURNS the Clack response list; a Clack app IS
a rontolisp handler. `rontolisp:fetch` keeps `(:status :headers :body)`
(`compiler/FetchResponseShape`, `.kb/fetch-http.md`).

## Invariant: shape declared once, construction per backend
- 15 ordered keys in `runtime/RontoClackEnv.FIELDS`, re-exported by `compiler/ClackEnv`;
  consumers switch with `default -> throw`. `ClackEnvTest` pins the set.
- ONE Lisp copy, `eval/http-server.lisp` (`%http-make-env`, `%http-normalize-response`,
  percent-decoding, buffered-body Gray class), self-contained by rule. Building the env in
  interpreted Lisp cost 3.1x throughput (18880 -> 6125 rps), hence native construction per
  backend. The interpreter loads the library EAGERLY at server start (a lazy load races
  one-virtual-thread-per-request, `.kb/concurrent-served-requests.md`).
- JVM: a DIRECT INVOKESTATIC of compiled `%http-normalize-response`
  (`ClackEnv.NORMALIZE_RESPONSE`, survives `--optimize`'s class shaker). **The direct calls
  take the defun's calling convention from its `FunctionInfo`** (`LibraryCall`), because which
  defuns bounce is whole-program (`.kb/jvm-tail-bounce.md`): a program taking `#'error` made
  the normalizer bounce and a unary call named an undeclared method. `RontoHttpClack`/
  `RontoHttpServer`/`RontoClackEnv`/`RontoHashTable` TRAVEL with the output
  (`.kb/jvm-export.md`).
- `RontoHttpServer.Request` holds raw facts only: target verbatim, headers in wire order with
  duplicates, body as BYTES, `scriptName` a RAW target prefix.

## --native: the runner's server
A `--native` module is Preview 1 (no incoming TCP), so the RUNNER owns socket and protocol
(`runner/src/http/serve.rs`, the `rlhttp` imports) and the module owns the application. Its
loop `%http-serve-exchange` IS the reactor transport (`.kb/clack.md`): one request model with
the Worker host, no third env builder. `awaitRequest` polls the server's bell, a `UnixStream`
pair a connection's thread writes a byte on after queuing a request, which `rltcp.wait` polls
beside the program's sockets (pinned by `tcp::tests`'s
`a_wait_answers_the_first_socket_that_has_something`; the scheduler's half in
[jdbc-postgresql.md](jdbc-postgresql.md), "In a served program").
- **A failure is the socket transports' 500.** An application (or the normalizer) signalling
  before any response went out answers, on all four backends, `500` `text/plain` `Internal
  Server Error`, the report on stderr as `HTTP-HANDLER: handler failed: <report>`
  (interpreter / JVM: `RontoHttpServer.dispatch`; `--native` and the component: `%http-failed`).
  `--native` calls `%http-reactor-answer` (the reactor handle without its catch) in a
  `handler-case` of its own; the failure head's in-band body replaces chunks that crossed.
  Before, `--native` leaked the report to the client as JSON and the component trapped. A
  re-signal `(error c)` there carried the run-time FORMAT (component 240,547 -> 157,047 bytes
  without it). The Worker reactor answers the same 500 with the report under `"error"`
  (`.kb/clack.md`). Pins:
  `HttpHandlerJvmTest#aNativeExecutableServesTheDirectiveThroughItsRunner`,
  `WasmLispCompilerIntegrationTest#httpHandlerServesUnderWasmtimeServe` (`/boom`),
  `java-http-server-spec.yaml` `a-handler-that-throws-after-the-head`.
- **The directive too**: `HttpReactorInliner.lowerHttpHandlerForRunner` (BEFORE
  `HttpServeLibrary.process`) rewrites it to `%http-serve` with its `:raw-body` mode, so the
  default asynchronous body means what it means elsewhere (the adapters' calls get
  `:buffered`). Same pin.
- **Threads are the runner's**: an acceptor and a thread per connection read the head
  (64 KiB cap -> 431) and the WHOLE body (chunked decoded and re-labelled `content-length`,
  `Expect: 100-continue` answered), then queue the exchange. The module answers each when its
  response is whole, a delayed one whose future has not settled from the frame
  `%http-serve-later` awaits it in, out of order (taking the next request nested in the
  transport's call exhausted the one stack at 800 requests in hand). An idle or slow client
  never blocks another (Chrome's speculative preconnects blocked a one-socket loop).
  Keep-alive per HTTP/1.1, 60 s idle timeout.
- **The response goes out whole, `Content-Length`-framed**: body chunks cross BEFORE the head
  and an in-band `"body"` key replaces them (the mid-body 500), so no streaming responses
  (SSE), the reactor's limit too. The runner drops a handler's
  content-length/transfer-encoding/connection fields.
- Envelope keys for a host that owns a socket: `protocol`, `local-addr`, `local-port`,
  `remote-port` (`ReactorEnvelope.REQUEST_KEYS`; a Worker has none).
- **Serving in steps**: `%http-serve-step server limit` serves one request within `limit` ms
  (nil: for ever) and answers whether it did, for an adapter with its own scheduler (the Java
  subset's threads on one stack, `.kb/java-frontend.md`, "com.sun.net.httpserver", whose timed
  threads are why the wait has a limit). Pinned by `FetchSpecE2eTest#javaHttpServer`'s native
  leg and `serve.rs`'s tests.
- **Never returns**: no second thread, so `join`/`:use-thread`/`:join? false` are ignored. An
  exchange the collector drops unanswered answers 500.
- **The end waits for the last response** (5 s at most after `_start` returns): without it a
  program that answers and exits lost its last response (`a-servlet-counts-the-stop-down`,
  macOS CI only). `FetchSpecE2eTest#javaHttpServer`'s native leg pins it.
- Pins: `serve.rs` tests (keep-alive with both framings, repeated headers, in-band
  replacement, HEAD/204/1.0, 400, a taken port), `ClojureRingAdapterTest`'s native leg.

## Graceful shutdown (JDK transports)
**A termination signal drains the JDK server; an explicit stop does not.** One daemon
PLATFORM thread per server runs `HttpServer.stop(grace)`, concurrently. Grace:
`rontolisp.http.shutdown-grace`, else `RONTOLISP_HTTP_SHUTDOWN_GRACE`, else **30 s**
(Kubernetes' default). `stopServer` (`clack:stop`) stays `stop(0)`; the hook does NOT release
`joinServer`'s latches; war/component take shutdown from the container/host. Tests:
`HttpHandlerTest.aGracefulShutdownStopsAcceptingAtOnceAndLetsAnInFlightRequestFinish`,
`RontoLispCliTest.aServedProgramJarDrainsInFlightRequestsWhenTheProcessIsTerminated`.

## Fifth transport: the Servlet war (`-o app.war`)
Servlet 6, deployed unmodified. `RontoHttpServlet` + `RontoHttpServletInitializer`'s
`jakarta.servlet` import is the one sanctioned exception to `runtime` importing nothing
(`.kb/jvm-export.md`). No handler => compile-time refusal.
- Trap: a container appends its default charset to a charset-less `text/*` (Jetty yes, Tomcat
  no), relabelling UTF-8 as Latin-1; `setCharacterEncoding(null)` AFTER adding a charset-less
  content-type, never when the handler declared one.
- Top level goes into `<clinit>`, run via `Class.forName(name, true, loader)`: containers load
  `@HandlesTypes` candidates WITHOUT initializing (the war 500'd on every request, and the
  reflective post-check made that a FAILED DEPLOYMENT).
- `%http-server-start` registers and returns (handle 0, `join` immediate, `stop` no-op).
  Handler slot VOLATILE, one per WEBAPP; the initializer WAITS a bounded **5 s** (a
  `clack:clackup` `:use-thread t` write lands only after `<clinit>` returns, `.kb/clack.md`).
- `scriptName` = `getContextPath() + getServletPath()` (context path UNDECODED per spec).
- ASYNC by default, an invariant not a knob (`.kb/concurrent-served-requests.md`): one virtual
  thread per request; opt out with the `rontolisp.async` context param `false`.
- `JvmWarWriter` is byte-identical across compiles (`.kb/emitted-output-determinism.md`).
- Tests: `WarE2eTest` (`-Drontolisp.war.e2e=true`) on embedded Tomcat AND Jetty,
  `RontoLispCliTest`'s war tests,
  `JvmHttpHandlerTravellingRuntimeTest.aWarCarriesTheServletTransportWhoseOnlyOutsideReferenceIsTheServletApi`,
  the war legs of `ClackE2eTest`/`NingleE2eTest`. Embedded Jetty needs
  `AnnotationConfiguration`; standalone Jetty its `annotations` module; Tomcat nothing.

## Environment contract (verified against upstream Clack)
- `:REQUEST-URI` raw target verbatim; `:QUERY-STRING` raw text after the first `?`, else nil.
- `:SCRIPT-NAME` percent-decoded mount point, `""` on every root-mounted transport (all but a
  war under a context path). The raw prefix comes off BEFORE percent-decoding; a non-prefix
  `scriptName` degrades to the root-mounted split.
- `:PATH-INFO` lenient decoder (malformed escape verbatim, `+` NOT decoded); the mount point
  itself gets `""`, not `/`.
- `:SERVER-PORT` from `Host` = last colon followed only by digits (IPv6 literals survive).
- `:REMOTE-ADDR`/`:REMOTE-PORT` nil on the component (`wasi:http@0.3.0` has no peer accessor).
- `:HEADERS` equal hash table, lowercased, repeats joined `", "`, never nil.
- **The UTF-8 decoder is a RANGE decoder** (`%http-utf8-complete-end` finds where a CHUNKED
  source may cut, `.kb/clack.md`); lenient: an invalid lead byte or truncated sequence answers
  its own chars.
- Tests: ci-spec `http-clack-environment-shape`, `http-percent-decode`,
  `http-clack-script-name`; `WarE2eTest`'s context-path leg.

## `:raw-body` — two modes, a compile-time constant
`:stream` (default) / `:buffered`, one flag per program: `ClackEnv.usesBufferedBody` scans the
program BEFORE `HttpLibrary` rewrites the directive away. The reactor decides at RUN time
(`.kb/clack.md`).
- `:stream`: drained with `(await (read-all ...))`; chunks are OCTET vectors on every backend.
- `:buffered`: what Clack needs (its `:raw-body` is SYNCHRONOUS and cannot block on a WASI
  future): a BIVALENT in-memory stream, character and byte reads off ONE byte cursor with a
  REAL `file-position`, so `circular-streams` -> `http-body:parse` run.
- **`:raw-body` is nil exactly for a request WITHOUT a body**: no octets and no
  `Content-Length` / `Transfer-Encoding` field (RFC 9112 section 6); `Content-Length: 0` is an
  empty stream. Why: tiny-routes' `wrap-request-body` reads `:raw-body` whenever
  `:content-length` is non-nil (it 500'd on every `Content-Length: 0`); hunchentoot/toot/wookie
  and lack's mock agree. Always-a-stream was rejected: it costs the GET an allocation. Two
  spellings of the rule: `RontoHttpServer.Request#declaresBody` and http-server.lisp's
  `%http-declares-body-p`. Pins: ci-spec `http-buffered-body-stream`,
  `HttpHandlerTest#aBufferedRawBodyIsNilExactlyForARequestWithoutABody`,
  `HttpHandlerJvmTest#compiledDirectiveDecodesPathInfoAndServesBufferedRawBody`.
- Interpreter: `eval/HttpRequestBodyStream`, not a Gray class (that measured -36% POST
  throughput); the TRANSPORT closes it at request end, a program's `close` answers t.
- Ordering trap: a harness driving a compiler directly must run `HttpServerLibrary.process`
  THEN (after macro expansion) `GrayStreamsLibrary.process`, mirroring the CLI.
- `%http-body-stream` takes text **or** octets; octets are a correctness rule (decode +
  re-encode doubles a binary body, `ff fe 41` -> `c3 bf c3 be 41`).
- **`:raw-body` element type is `character` on every construction** (`.kb/gray-streams.md`).
  Pinned by ci-spec `http-buffered-body-stream`,
  `LispEvaluatorAsdfTest.aBufferedRawBodyAnswersACharacterElementType`.
- **Module-size filter**: a default-mode program drops the buffered half (35% of a WASM serve
  component), which is why the mode is compile-time.

## Response contract
`(status headers [body])`, normalized by ONE function `%http-normalize-response` (the
interpreter mirrors it arm for arm).
- A non-integer status SIGNALS (no implicit 200). A ningle 404 is `(404 () (NIL))`.
- `headers`: keyword plist, or a dotted alist so a fetch result's `:headers` passes through;
  `content-length`/`transfer-encoding` dropped (the transport frames).
- `body`: a LIST of strings, nil, an `(unsigned-byte 8)` vector, or a stream (drained to one
  octet vector or one string; mixed is refused). A NIL element is `""`. **A BARE STRING
  SIGNALS.** A PATHNAME body (`lack/app/file`) is refused.
- A FUNCTION response is Clack's DELAYED form (next section).
- Migration hazard: the response side fails loudly, the request side SILENTLY —
  `(getf env :method)` just returns nil in a half-migrated handler.
- Tests: ci-spec `http-response-normalizer`; round trips by `HttpHandlerTest`/
  `HttpHandlerJvmTest`/`WasmLispCompilerIntegrationTest`/`ClackE2eTest`/
  `LackEcosystemE2eTest`/`LackEcosystemWasmE2eTest`.

## Clack's streaming writer
**A responder handed `(status headers)` answers a writer `(chunk &key start end close)`; the
component sends each chunk as it is written, every other transport keeps the chunks and sends
the response whole.**
- ONE implementation, `%http-normalize-streaming` (interpreter mirror `writeClackChunk`). The
  body ends at `:close t`, else where the delayed function returns or unwinds: no transport
  writes after its request was answered, so a writer kept for later (SSE from another thread)
  is not served.
- **A delayed function may answer a future** where the transport awaits (the component,
  `--native` via `%http-serve-later`), so the application goes on answering after its function
  returned (`.kb/java-frontend.md`, "The host's events give the turns under a component"). The
  await sits in an `unwind-protect`: a re-signalling `handler-case` made every served JVM
  program carry `RontoSignal.class` (`JvmHttpHandlerTravellingRuntimeTest`).
- **An unwind ends a body on its way short** (`%http-delayed-shut`): the component writes the
  trailers `(:error :internal-error)` and `wasmtime serve` 51.0.0 closes with no last chunk
  (`.kb/java-frontend.md`, "A handler that throws after a chunked head"), so a client never
  reads a failure as a whole body. Where chunks are kept the unwind reaches the 500. Pins:
  `WasmLispCompilerIntegrationTest#httpHandlerServesUnderWasmtimeServe` (`/cut`: curl 18 or 52,
  never 0), `java-http-server-spec.yaml` `a-handler-that-throws-after-the-head`.
- A kept body mixing strings and octets is refused (the octet join needs no encoder, which a
  default-mode program does not carry).
- **The component streams**: the head goes by `task.return` from INSIDE the handler, each
  chunk is the blocking `stream.write`. Measured (wasmtime 49 and 51.0.0): a handler that wrote
  a chunk and asked an origin whether the client had read it was told yes
  (`java-http-server-spec.yaml` `/stream`); interpreter, JVM and `--native` answer no.
  `.todo/k15`: the JDK transports could stream too.
- `HttpServerLibrary.ENTRY_POINTS`: a harness splicing `http.lisp` first
  (`WasmLispCompilerIntegrationTest#compileServeComponent`) finds the library by
  `%http-serve-responding`.
- **Trap, measured**: locals named `write` and `open` in these defuns made every served JVM
  program carry `RontoComplex.class` (`JvmHttpHandlerTravellingRuntimeTest`): a library's
  locals are symbols the capability scans read. They are `sink` and `live`.
- Pins: `HttpHandlerTest#directiveSupportsTheDelayedResponse`,
  `FetchSpecE2eTest#javaHttpServer` (JVM, `--native`, the component's stream).

## A stop called by a handler
A stop on a thread running a handler of that server returns at once and stops on a daemon
thread with `HttpServer.stop(grace)`: the caller's response is written after its handler
returns, so `stop(0)` there cut it. The handle stays joinable until the stop is done. Any
other thread's stop is `stop(0)`. Pin: `HttpHandlerTest#stoppableSeamServesJoinsAndStops`.

**A draining stop, `(%http-server-stop handle t)` / `(%http-serve-stop server t)`, lets open
exchanges end first** (JDK 25's `stop(d)` returns once none is open), for a caller that
answered every request it took (the Java subset's `HttpServing.Server.stop`). Why: the
application answers INSIDE `Handler.handle` and `dispatch` writes after it returns, so the
Java layer's count reached 0 before the bytes went out and `main`'s `server.stop(..)` cut the
last response (a latch-and-stop repro: 34 of 40 replies lost on the JVM before, 0 of 200
after). One-argument stops (`clack:stop`) still cut. `--native` needs nothing (its runner
waits); under a component the host owns the stop.

## An exchange the application takes (the socket legs)
**On the interpreter and the JVM an application may take the exchange it is dispatched and
answer it from any thread at any time — a JDK `HttpExchange`'s life.**
`rontolisp::%http-server-take` (on the handler thread; a thread-local of
`RontoHttpServer.dispatch`) answers the exchange's `Reply`, and the transport then writes and
closes nothing; `%http-server-finish` writes the response whole and closes it on the calling
thread (or the 500 of a failed application). The one user is `%http-serve-begin`'s socket leg
(the Java subset's `com.sun.net.httpserver`). `RontoHttpServer$Reply` travels. A stop by a
handler of a taken exchange cuts at once. Pinned by `FetchSpecE2eTest#javaHttpServer`.

## A binary response body is byte-exact — everywhere but one named place
**An `(unsigned-byte 8)` response body reaches the wire as the octets it holds, on every
transport that writes the wire.** Carry the octets, do not render them. `--component` stages
raw bytes into a `list<u8>` (`WasmComponentImportCompiler.emitStageBytesParam`, `.kb/wit.md`,
"The settled type mapping"); the reactor uses the byte-shaped `env.writeResponseBody` sink.
- Tests assert RAW response bytes (the text spelling passes on a double-encode):
  `HttpHandlerTest.directiveServesAnOctetBodyByteExactly` and its `HttpHandlerJvmTest` /
  `WasmLispCompilerIntegrationTest` twins.
- **The one exception, deliberately**: the reactor envelope's NO-SINK arm renders octets as the
  TEXT their UTF-8 bytes spell (its head is a JSON string); a host wanting binary registers
  `env.writeResponseBody`. Decode, not one-char-per-octet (which doubled every octet >= #x80).
  Stays while the envelope's `"body"` key is a JSON string.
