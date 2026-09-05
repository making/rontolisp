# The server-side HTTP value model (`rontolisp:http-handler` = Clack)

A handler RECEIVES the Clack env plist and RETURNS the Clack response list; a Clack app IS
a rontolisp handler. `rontolisp:fetch` keeps `(:status :headers :body)`
(`compiler/FetchResponseShape`, `.kb/fetch-http.md`).

## Invariant: shape declared once, construction per backend
- 15 ordered keys in `runtime/RontoClackEnv.FIELDS`, re-exported name for name by
  `compiler/ClackEnv`; consumers switch with `default -> throw`. `ClackEnvTest` pins the set.
- ONE Lisp copy, `eval/http-server.lisp` (`eval/HttpServerLibrary`): `%http-make-env`
  (positional raw tuple), `%http-normalize-response`, percent-decoding, header table, Host
  split, buffered-body Gray class; self-contained by rule. Trap: building the env in
  interpreted Lisp cost 3.1x throughput (18880 -> 6125 rps); hence native per backend.
- Interpreter: `LispEvaluator.buildClackEnv`/`normalizeClackResponse`/`responseHeaders`/
  `responseBody`. The library loads EAGERLY at server start (`ensureHttpServerLoaded`; a
  lazy load races one-virtual-thread-per-request, `.kb/concurrent-served-requests.md`).
- JVM: `JvmHttpHandlerRuntimeBuilder` over `runtime/RontoHttpClack.buildEnv`/`toResponse`,
  with a DIRECT INVOKESTATIC of compiled `%http-normalize-response`
  (`ClackEnv.NORMALIZE_RESPONSE`, survives `--optimize`'s class shaker); `usesHttpHandler`
  forces `usesHashTables`. `RontoHttpClack`/`RontoHttpServer`/`RontoClackEnv`/
  `RontoHashTable` TRAVEL with the output (`.kb/jvm-export.md`).
- WASM component: http.lisp `%serve-handle` -> raw tuple -> `%http-serve-request` (ONE
  async frame).
- `RontoHttpServer.Request` = 11 raw facts: target verbatim, headers in wire order with
  duplicates, body as BYTES, protocol/scheme/local/remote, `scriptName` (RAW target prefix).

## Graceful shutdown (JDK transports)
**A termination signal drains the JDK server; an explicit stop does not.**
`RontoHttpServer.registerServer` -> `shutdownGracefully(grace)` -> `HttpServer.stop(delay)`,
one daemon PLATFORM thread per server draining CONCURRENTLY. Grace:
`rontolisp.http.shutdown-grace` property, else `RONTOLISP_HTTP_SHUTDOWN_GRACE`, else **30 s**
(Kubernetes' default). `stopServer` (`%http-server-*` seam, `clack:stop`) stays `stop(0)`;
the hook does NOT release `joinServer`'s latches; war/component take shutdown from the
container/host. Tests:
`HttpHandlerTest.aGracefulShutdownStopsAcceptingAtOnceAndLetsAnInFlightRequestFinish`,
`RontoLispCliTest.aServedProgramJarDrainsInFlightRequestsWhenTheProcessIsTerminated`.

## Fifth transport: the Servlet war (`-o app.war`)
Servlet 6, deployed unmodified. `RontoHttpServlet` + `RontoHttpServletInitializer` travel on
the THIRD list `JvmHttpHandlerRuntimeBuilder.WAR_RUNTIME_CLASS_FILES`; their
`jakarta.servlet` import is the one sanctioned exception to `runtime` importing nothing
(`.kb/jvm-export.md`). Gated by `Features.JVM_SERVLET` / `:rontolisp-servlet` +
`JvmLispCompiler.servlet`; no handler => compile-time refusal.
- Trap: a container appends its default charset to a charset-less `text/*` (Jetty yes,
  Tomcat no), relabelling UTF-8 as Latin-1 — clear it with `setCharacterEncoding(null)`
  AFTER adding a charset-less content-type, never when the handler declared one.
- Top level goes into `<clinit>`, run via `Class.forName(name, true, loader)` — containers
  load `@HandlesTypes` candidates WITHOUT initializing; without the move the war 500s on
  every request, and the reflective post-check makes that a FAILED DEPLOYMENT.
- The directive stores the funcref and RETURNS; `%http-server-start` REGISTERS AND RETURNS
  (handle 0, `join` immediate, `stop` no-op, `port` 0) — the `#+rontolisp-servlet` spelling
  the Clack leg uses (`.kb/clack.md`).
- Handler slot VOLATILE, one per WEBAPP; the initializer WAITS a bounded **5 s** (a
  `clack:clackup` `:use-thread t` write lands only after `<clinit>` returns).
- `scriptName` = `getContextPath() + getServletPath()` (context path UNDECODED per spec).
- ASYNC by default, an invariant not a knob (`.kb/concurrent-served-requests.md`):
  `startAsync` + `setTimeout(0)` + one virtual thread per request + `complete()` in a
  `finally`; opt out with the `rontolisp.async` context param `false`.
- `JvmWarWriter` = `JvmJarWriter`, different entry prefix and manifest, byte-identical
  across compiles (`.kb/emitted-output-determinism.md`).
- Tests: `WarE2eTest` (`-Drontolisp.war.e2e=true`) on embedded Tomcat AND Jetty,
  `RontoLispCliTest`'s war tests,
  `JvmHttpHandlerTravellingRuntimeTest.aWarCarriesTheServletTransportWhoseOnlyOutsideReferenceIsTheServletApi`,
  the war legs of `ClackE2eTest`/`NingleE2eTest`. Embedded Jetty needs
  `AnnotationConfiguration`; standalone Jetty its `annotations` module; Tomcat nothing.

## Environment contract (verified against upstream Clack)
- `:REQUEST-METHOD` upcased interned keyword; `:SERVER-PROTOCOL` keyword; `:URL-SCHEME`;
  `:REQUEST-URI` raw target verbatim; `:QUERY-STRING` raw text after the first `?`, else nil.
- `:SCRIPT-NAME` mount point, percent-decoded; `""` on every root-mounted transport, i.e.
  all but a war under a context path. The raw prefix comes off the target BEFORE
  percent-decoding; a non-prefix `scriptName` degrades to the root-mounted split.
- `:PATH-INFO` percent-decoded, raw mount prefix stripped first; lenient decoder (malformed
  escape verbatim, `+` NOT decoded); the mount point itself gets `""`, not `/`.
- `:SERVER-NAME`/`:SERVER-PORT` from `Host` (port = last colon followed only by digits, so
  IPv6 literals survive), else the listening address.
- `:REMOTE-ADDR`/`:REMOTE-PORT` real on JDK backends, nil on the component
  (`wasi:http@0.3.0` has no peer accessor).
- `:HEADERS` equal hash table, lowercased names, repeats joined `", "`, never nil;
  `:CONTENT-TYPE`; `:CONTENT-LENGTH` integer or nil; `:RAW-BODY`. Plist freshly consed and
  proper per request.
- **UTF-8 decoder is a RANGE decoder**: `%http-utf8-decode-octets v start end`,
  `%http-utf8-length`, `%http-utf8-complete-end` (end of a range's last COMPLETE sequence,
  where a CHUNKED body source may cut, `.kb/clack.md`). Lenient: an invalid lead byte and a
  truncated sequence answer their own chars.
- Tests: ci-spec `http-clack-environment-shape`, `http-percent-decode`,
  `http-clack-script-name`; `WarE2eTest`'s context-path leg.

## `:raw-body` — two modes, a compile-time constant
Directive and `rontolisp::%http-server-start` take `:raw-body :stream` (default) /
`:buffered`; `ClackEnv.usesBufferedBody` scans the program BEFORE `HttpLibrary` rewrites the
directive away (order in `RontoLispCli`), one flag per program. The HOST-DRIVEN REACTOR
decides at RUN time, via `%http-reactor-register app [:buffered]` (`.kb/clack.md`).
- `:stream` — asynchronous stream drained with `(await (read-all ...))`; a bodiless request
  gets an already-closed stream; chunks are OCTET vectors on every backend.
- `:buffered` — what Clack needs (its `:raw-body` is SYNCHRONOUS, cannot block on a WASI
  future): a BIVALENT in-memory stream, character and byte reads off ONE byte cursor with a
  REAL `file-position`; lets `lack:builder` -> `circular-streams` -> `http-body:parse` run
  natively. A bodiless request gets `:raw-body nil`.
- Interpreter: `eval/HttpRequestBodyStream`, `byte[]` + cursor in the stream table
  (`Environment`'s `httpBodyStreamOpener/Closer`; the TRANSPORT closes it at request end) —
  a Gray class here measured -36% POST throughput. JVM + WASM: compiled Gray class
  `http-request-body-stream` over `rontolisp:fundamental-binary-input-stream`, built by
  `%http-body-stream`.
- Ordering trap: a harness driving a compiler directly must run `HttpServerLibrary.process`
  THEN (after macro expansion) `GrayStreamsLibrary.process`, mirroring the CLI.
- `%http-body-stream` takes the TEXT a transport read **or** the OCTETS a byte-shaped one
  did; the second is a correctness rule — the lenient decoder makes decode+re-encode double
  a binary body (`ff fe 41` -> `c3 bf c3 be 41`).
- **`:raw-body` element type is `character` on every construction** (the Gray class
  subclasses BOTH input base classes, `.kb/gray-streams.md`). Pinned by ci-spec
  `http-buffered-body-stream`,
  `LispEvaluatorAsdfTest.aBufferedRawBodyAnswersACharacterElementType`.
- **Module-size filter**: `HttpServerLibrary.process(program, bufferBody)` drops the
  buffered half from a default-mode program — a measured 35% of a WASM serve component;
  `HttpLibrary` SYNTHESIZES `%serve-request-body` per mode instead of a runtime flag.

## Response contract
`(status headers [body])`, normalized by ONE function `%http-normalize-response`
(interpreter mirrors it in Java, arm for arm).
- `status` must be an integer — a non-integer car SIGNALS (no implicit 200). The two-element
  bodyless form is legal; a ningle 404 is not it, it is `(404 () (NIL))`.
- `headers`: keyword plist, or (widening) a dotted alist so a fetch result's `:headers`
  passes through; every pair becomes its own header line;
  `content-length`/`transfer-encoding` dropped (the transport frames).
- `body`: a LIST of strings (joined), nil, an `(unsigned-byte 8)` vector, or a rontolisp
  stream — the last TWO come back UNCHANGED. A stream DRAINS (`%http-drain`, reactor
  `%http-reactor-body-drain`) to ONE octet vector when its chunks are octets, one string
  when they are strings; a mixed stream is refused. Join is `%http-octets-join`. A NIL
  element contributes the empty string. **A BARE STRING SIGNALS.** A PATHNAME body
  (`lack/app/file`) is refused by the unsupported-type arm.
- A FUNCTION response is Clack's DELAYED form only; the streaming WRITER protocol is refused.
- Migration hazard: the response side fails loudly, the request side SILENTLY —
  `(getf env :method)` just returns nil in a half-migrated handler.
- Tests: ci-spec `http-response-normalizer`; round trips by `HttpHandlerTest`/
  `HttpHandlerJvmTest`/`WasmLispCompilerIntegrationTest`/`ClackE2eTest`/
  `LackEcosystemE2eTest`/`LackEcosystemWasmE2eTest`.

## A binary response body is byte-exact — everywhere but one named place
**An `(unsigned-byte 8)` response body reaches the wire as the octets it holds, on every
transport that writes the wire.** Carry the octets, do not render them.
- `%http-serve-request` hands a string OR an octet vector to the transport, resolving only a
  STREAM body. JDK: `RontoHttpServer.Response` holds `byte[] body`; `writeResponse` encodes
  nothing. `--component`: octets cross `%http:body-stream-write` as a `list<u8>` —
  `WasmComponentImportCompiler.emitStageBytesParam` stages raw array bytes instead of
  `_str_to_mem`'s UTF-8 (`.kb/wit.md`, "list<u8> = string"). Reactor: the byte-shaped
  `env.writeResponseBody` sink takes them as they are.
- Tests assert RAW response bytes (the text spelling passes on a double-encode):
  `HttpHandlerTest.directiveServesAnOctetBodyByteExactly` and its `HttpHandlerJvmTest` /
  `WasmLispCompilerIntegrationTest` twins.
- **The one exception, deliberately**: the reactor envelope's NO-SINK arm
  (`%http-reactor-body-out`) renders octets as the TEXT their UTF-8 bytes spell, via
  `%http-reactor-body-envelope-text` (`rontolisp::%octets-to-string`), its head being a JSON
  string; a host wanting binary registers `env.writeResponseBody`. Decode, not
  one-char-per-octet flattening (which doubled every octet >= #x80). Stays while the
  envelope's `"body"` key is a JSON string.
