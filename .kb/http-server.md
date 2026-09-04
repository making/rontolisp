# The server-side HTTP value model (`rontolisp:http-handler` = Clack)

A handler RECEIVES the Clack env plist and RETURNS the Clack response list; a Clack app IS a
rontolisp handler (`clack.handler.rontolisp` converts nothing per request). `rontolisp:fetch` keeps
`(:status :headers :body)` (`compiler/FetchResponseShape`, `.kb/fetch-http.md`).

## Invariant: shape declared once, construction per backend

- 15 ordered keys in `runtime/RontoClackEnv.FIELDS`, re-exported name for name by
  `compiler/ClackEnv` (+ AST scan); consumers switch with `default ->` throw, so a new key fails
  each backend loudly. `ClackEnvTest` pins the set.
- ONE Lisp copy, `src/main/resources/am/ik/rontolisp/eval/http-server.lisp`
  (`eval/HttpServerLibrary`): `%http-make-env` (positional raw tuple), `%http-normalize-response`,
  percent-decoding, header table, Host split, buffered-body Gray class; self-contained by rule.
- Trap: the env built in interpreted Lisp cost 3.1x throughput (18880 -> 6125 rps); hence native
  per backend.
- Interpreter: `LispEvaluator.buildClackEnv`/`normalizeClackResponse`/`responseHeaders`/
  `responseBody`; cold `(unsigned-byte 8)` body arm -> `%http-body-string`. The library loads
  EAGERLY at server start (`ensureHttpServerLoaded`; a lazy load races
  one-virtual-thread-per-request, `.kb/concurrent-served-requests.md`), lazily on a direct
  `RONTOLISP::%HTTP-*` call (`resolveFunction`).
- JVM: thin glue `JvmHttpHandlerRuntimeBuilder` over `runtime/RontoHttpClack.buildEnv`/`toResponse`
  (`_hash*` HashMap, prin1-text keys). Bytecode keeps only the `:raw-body` build,
  `_invoke_1`+`_await`, a DIRECT INVOKESTATIC of compiled `%http-normalize-response`
  (`ClackEnv.NORMALIZE_RESPONSE`; survives `--optimize`'s class shaker), `_drain_body`;
  `usesHttpHandler` forces `usesHashTables`.
  `RontoHttpClack`/`RontoHttpServer`/`RontoClackEnv`/`RontoHashTable` TRAVEL with the output
  (`.kb/jvm-export.md`) and import nothing project-side.
- WASM component: http.lisp `%serve-handle` -> raw tuple -> `%http-serve-request` (ONE async frame).
- `RontoHttpServer.Request` = 11 raw facts: target verbatim (percent-encoded, query included),
  headers in wire order with duplicates, body as BYTES, protocol/scheme/local/remote, `scriptName`
  (RAW target prefix, `""` root-mounted; enters via the tuple, not a library special case).

## Graceful shutdown (JDK transports)

**A termination signal drains the JDK server; an explicit stop does not.** Hook
`RontoHttpServer.registerServer` -> `shutdownGracefully(grace)` -> `HttpServer.stop(delay)`.

- Grace: `rontolisp.http.shutdown-grace` property, else `RONTOLISP_HTTP_SHUTDOWN_GRACE` env, else
  **30 s** (Kubernetes' default `terminationGracePeriodSeconds`); a bad value falls back to it.
- One daemon PLATFORM thread per server, draining CONCURRENTLY (`stop(delay)` blocks per server).
- `stopServer` (`%http-server-*` seam, `clack:stop`) stays `stop(0)`; the hook does NOT release
  `joinServer`'s latches; war/component take shutdown from the container/host.
- Tests: `HttpHandlerTest.aGracefulShutdownStopsAcceptingAtOnceAndLetsAnInFlightRequestFinish`,
  `RontoLispCliTest.aServedProgramJarDrainsInFlightRequestsWhenTheProcessIsTerminated`.

## Fifth transport: the Servlet war (`-o app.war`)

Servlet 6, deployed unmodified. `RontoHttpServlet` + `RontoHttpServletInitializer` travel on the
THIRD list `JvmHttpHandlerRuntimeBuilder.WAR_RUNTIME_CLASS_FILES`; their `jakarta.servlet` import is
the one sanctioned exception to `runtime` importing nothing (`.kb/jvm-export.md`),
`jakarta.servlet-api` `provided` in the root pom.

- `RontoHttpServlet`: 11-field `Request` in, `Response` out, byte-exact. Trap: a container appends
  its default charset to a charset-less `text/*` (Jetty yes, Tomcat no), relabelling UTF-8 as
  Latin-1 — clear it with `setCharacterEncoding(null)` AFTER adding a charset-less content-type,
  never when the handler declared one. `scriptName` = `getContextPath() + getServletPath()` (context
  path UNDECODED per spec => raw strippable prefix; `/api/*` in `/myapp` mounts at `/myapp/api`).
- `RontoHttpServletInitializer`: `@HandlesTypes(RontoHttpServer.Handler.class)` — no name, no
  `web.xml`, one-line `ServletContainerInitializer` service file.
- `Features.JVM_SERVLET` / `:rontolisp-servlet` (when `-o` ends in `.war`) + `JvmLispCompiler.servlet`:
  - top level into `<clinit>` (as `jvm-export` does), run via `Class.forName(name, true, loader)` —
    containers load `@HandlesTypes` candidates WITHOUT initializing. Without the move the war 500s on
    every request; the reflective post-check makes that a FAILED DEPLOYMENT, and
    `ExceptionInInitializerError` -> `ServletException`.
  - the directive stores the funcref and RETURNS (a written port warns once at compile time);
    `%http-server-start` REGISTERS AND RETURNS (handle 0, `join` immediate, `stop` no-op, `port` 0)
    — the spelling the Clack servlet leg uses (`#+rontolisp-servlet`, `.kb/clack.md`).
  - handler slot VOLATILE; the initializer WAITS a bounded **5 s** (a `clack:clackup` `:use-thread t`
    write lands only after `<clinit>` returns; without the wait 3 deployments in 10 succeeded).
  - no handler => compile-time refusal in `JvmLispCompiler`.
  - ASYNC by default, an invariant not a knob (`.kb/concurrent-served-requests.md`): `startAsync` +
    `setTimeout(0)` + one virtual thread per request + `complete()` in a `finally`; opt out with the
    `rontolisp.async` context param `false` (a filter chain refusing async falls back to sync with
    one warning); one handler slot per WEBAPP (class loader).
  - `JvmWarWriter` = `JvmJarWriter`, different entry prefix and manifest (no `Main-Class`),
    byte-identical across compiles (`.kb/emitted-output-determinism.md`).
- Tests: `WarE2eTest` (`-Drontolisp.war.e2e=true`) on embedded Tomcat AND Jetty (discovery is
  container behavior): spike table, octet body on RAW bytes, distinct-thread concurrency, both
  deployment-failure shapes. Plus `RontoLispCliTest`'s war tests,
  `JvmHttpHandlerTravellingRuntimeTest.aWarCarriesTheServletTransportWhoseOnlyOutsideReferenceIsTheServletApi`,
  and the war legs of `ClackE2eTest`/`NingleE2eTest` (sharing `EmbeddedServletContainer`, war
  UNMODIFIED). Embedded Jetty needs `AnnotationConfiguration`; standalone Jetty its `annotations`
  module; Tomcat nothing.

## Environment contract (verified against upstream Clack)

- `:REQUEST-METHOD` upcased interned keyword; `:SERVER-PROTOCOL` keyword; `:URL-SCHEME`;
  `:REQUEST-URI` raw target verbatim; `:QUERY-STRING` raw text after the first `?`, nil if absent.
- `:SCRIPT-NAME` mount point, percent-decoded; `""` on every root-mounted transport, i.e. all but a
  war under a context path (`lack/app/mount` and the session middleware `setf getf` it and
  `:PATH-INFO`). The raw prefix comes off the target BEFORE percent-decoding; a non-prefix
  `scriptName` degrades to the root-mounted split rather than signalling.
- `:PATH-INFO` percent-decoded, raw mount prefix stripped first; lenient decoder (malformed escape
  copies verbatim, `+` NOT decoded — a query-string rule); the mount point itself gets `""`, not `/`.
- `:SERVER-NAME`/`:SERVER-PORT` from `Host` (port = last colon followed only by digits, so IPv6
  literals survive), else the listening address.
- `:REMOTE-ADDR`/`:REMOTE-PORT` real on JDK backends, nil on the component (`wasi:http@0.3.0` has no
  peer accessor). **Re-evaluation trigger**: a later wasi:http with one gets wired into
  `%serve-handle`'s raw tuple and this clause deleted.
- `:HEADERS` equal hash table, lowercased names, repeats joined `", "`, never nil; `:CONTENT-TYPE`;
  `:CONTENT-LENGTH` integer or nil; `:RAW-BODY`. Plist freshly consed and proper per request
  (lack-request `rplacd`s it).
- Tests: ci-spec `http-clack-environment-shape`, `http-percent-decode`, `http-clack-script-name`;
  `WarE2eTest`'s context-path leg.
- **UTF-8 decoder is a RANGE decoder**: `%http-utf8-decode-octets v start end`, `%http-utf8-length`
  (one lead byte), `%http-utf8-complete-end` (end of a range's last COMPLETE sequence — where a
  CHUNKED body source may cut, `.kb/clack.md`); `%http-utf8-decode` (list spelling) is one line over
  it. Lenient: a byte leading no valid sequence, and a truncated sequence, answer their own chars.

## `:raw-body` — two modes, a compile-time constant

Directive and `rontolisp::%http-server-start` take `:raw-body :stream` (default) / `:buffered`;
`ClackEnv.usesBufferedBody` scans the program BEFORE `HttpLibrary` rewrites the directive away (order
in `RontoLispCli`), one flag per program. The HOST-DRIVEN REACTOR decides at RUN time instead, via
`%http-reactor-register app [:buffered]` (`.kb/clack.md`).

- `:stream` — asynchronous stream drained with `(rontolisp:await (rontolisp:read-all ...))`; a
  bodiless request gets an already-closed stream; nothing buffered on the component; chunks are
  OCTET vectors on every backend (`.kb/fetch-http.md`).
- `:buffered` — what Clack needs (its `:raw-body` is SYNCHRONOUS, cannot block on a WASI future):
  body read in full into a BIVALENT in-memory stream, `read-line`/`read-char` AND
  `read-byte`/`read-sequence` off ONE byte cursor with a REAL `file-position`; lets `lack:builder` ->
  `circular-streams` -> `http-body:parse` run natively (`LackEcosystem*E2eTest` served-body legs). A
  bodiless request gets `:raw-body nil`.
- Interpreter: `eval/HttpRequestBodyStream`, Java `byte[]` + cursor in the stream table
  (`Environment`'s `httpBodyStreamOpener/Closer`; the TRANSPORT closes it at request end).
  `read-line`/`read-char`/`peek-char`/`file-position` have concrete-type arms in `Environment`;
  `read-byte`/`listen` use the `InputStream` arm. The Gray class here measured -36% POST throughput.
- JVM + WASM: compiled Gray class `http-request-body-stream` (over
  `rontolisp:fundamental-binary-input-stream`) built by `%http-body-stream` over the request's OCTETS
  (JVM `handle` passes `RontoHttpClack.bodyOctets`); its single-pass `stream-read-line` avoids
  per-character generic dispatch.
- Ordering trap: the Gray `:raw-body` needs `GrayStreamsLibrary.process`'s call-site rewrite, so a
  harness driving a compiler directly must run `HttpServerLibrary.process` THEN (after macro
  expansion) `GrayStreamsLibrary.process`, mirroring the CLI.
- `%http-body-stream` takes the TEXT a transport read **or** the OCTETS a byte-shaped one did; the
  second is a correctness rule — the lenient decoder makes decode+re-encode double a binary body
  (`ff fe 41` -> `c3 bf c3 be 41`). Same reason a reactor `:buffered` drain answers octets.
- **`:raw-body` element type is `character` on every construction**: the Gray class subclasses BOTH
  input base classes so the bivalent rule (`.kb/gray-streams.md`) answers `character`, and the
  interpreter's stream HANDLE answers it through the lite built-in. Buffer-sizing callers need it
  (tiny-routes' `read-stream-to-string`); no read/drain path consults it, so byte-exactness is
  unaffected. Pinned by ci-spec `http-buffered-body-stream` and
  `LispEvaluatorAsdfTest.aBufferedRawBodyAnswersACharacterElementType`.
- **Module-size filter**: `HttpServerLibrary.process(program, bufferBody)` drops the buffered half
  (Gray class + `%http-body-stream` + `%http-utf8-encode`) from a default-mode program — a measured
  35% of a WASM serve component; `HttpLibrary` SYNTHESIZES `%serve-request-body` per mode
  (pass-through vs drain+wrap) instead of a runtime flag. A program naming `%http-body-stream`
  directly (ci-spec `http-buffered-body-stream`) keeps it regardless.

## Response contract

`(status headers [body])`, normalized by ONE function `%http-normalize-response` (interpreter mirrors
it in Java, arm for arm):

- `status` must be an integer — a non-integer car SIGNALS (no implicit 200). The two-element bodyless
  form is legal (lack's `finalize-response` when `make-response` got NO body argument); a ningle 404
  is not it — `make-context` passes the body argument, so it is `(404 () (NIL))`.
- `headers`: keyword plist, or (widening) a dotted alist so a fetch result's `:headers` passes
  through; every pair becomes its own header line (repeated `:set-cookie` correct by construction);
  `content-length`/`transfer-encoding` dropped (the transport frames).
- `body`: a LIST of strings (joined), nil, an `(unsigned-byte 8)` vector, or a rontolisp stream
  (drained — the one extra await); the last TWO come back UNCHANGED. A stream DRAINS (`%http-drain`,
  reactor-synchronous `%http-reactor-body-drain`) to ONE octet vector when its chunks are octets, to
  one string when they are strings (a guest `make-stream`); a mixed stream is refused. Join is
  `%http-octets-join`, an aref/aset blit in this prelude-free library (the prelude's `%octets-join`
  is the same loop for `read-all`). A NIL element contributes the empty string rather than signalling
  (upstream writes chunks through `flex:string-to-octets`). **A BARE STRING SIGNALS**: lack's
  `finalize-response` wraps a string controller result in a list (`.kb/pathnames.md`). A PATHNAME
  body (`lack/app/file`) is refused by the unsupported-type arm until a transport can serve a file.
- A FUNCTION response is Clack's DELAYED form only — a responder captures the real response; the
  streaming WRITER protocol is refused by the closure the responder returns.
- Migration hazard (also in the docs): the response side fails loudly, the request side SILENTLY —
  `(getf env :method)` just returns nil in a half-migrated handler.
- Tests: ci-spec `http-response-normalizer`; round trips by `HttpHandlerTest`/`HttpHandlerJvmTest`/
  `WasmLispCompilerIntegrationTest` serve cases/`ClackE2eTest`/`LackEcosystemE2eTest`/
  `LackEcosystemWasmE2eTest`.

## A binary response body is byte-exact — everywhere but one named place

**An `(unsigned-byte 8)` response body reaches the wire as the octets it holds, on every transport
that writes the wire.** Carry the octets, do not render them.

- `%http-serve-request` hands a string OR an octet vector to the transport, resolving only a STREAM
  body (the arm needing the await).
- JDK: `RontoHttpServer.Response` holds `byte[] body`, with `Response.of(status, headers, String)`
  for text; `LispEvaluator.responseBody` answers octets (cold arm reads the packed `LispIntVector`
  from `%http-body-string`, stream arm drains octet chunks); `RontoHttpClack.toResponse` reads the
  JVM `long[]{width, e0, ...}` (`_drain_body` answers one for an octet-chunk stream);
  `writeResponse` encodes nothing.
- `--component`: octets cross `%http:body-stream-write` as a `list<u8>`; the canonical lowering takes
  a packed `(unsigned-byte 8)` vector for a `list<u8>`/`stream<u8>` parameter —
  `WasmComponentImportCompiler.emitStageBytesParam`, staging raw array bytes instead of
  `_str_to_mem`'s UTF-8 (`.kb/wit.md`, "list<u8> = string"); any other value takes the string path.
- Reactor: the byte-shaped `env.writeResponseBody` sink takes them as they are
  (`WasmReactorResponseBodyE2eTest`).
- Tests: `HttpHandlerTest.directiveServesAnOctetBodyByteExactly`, its `HttpHandlerJvmTest` twin,
  `WasmLispCompilerIntegrationTest.httpHandlerServesAnOctetBodyByteExactlyUnderWasmtimeServe` — all
  assert RAW response bytes, since the text spelling passes on a double-encode; the STREAM body is
  pinned on all four backends by the relay tests (`.kb/fetch-http.md`).
- **The one exception, deliberately**: the reactor envelope's NO-SINK arm (`%http-reactor-body-out`)
  renders octets (a body, or a stream's chunks joined) as the TEXT their UTF-8 bytes spell, via
  `%http-reactor-body-envelope-text` (`rontolisp::%octets-to-string`), its head being a JSON string;
  a host wanting binary registers `env.writeResponseBody`. Decode, not one-char-per-octet flattening
  (the host UTF-8 encodes the JSON string, so flattening doubled every octet >= #x80).
  **Re-evaluation trigger**: stays while the envelope's `"body"` key is a JSON string.
  (`%http-octets-string`/`%http-body-text`, the flattening helpers, are deleted.)
