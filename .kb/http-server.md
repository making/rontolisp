# The server-side HTTP value model (`rontolisp:http-handler` = Clack)

Since the todo-258 cutover a `rontolisp:http-handler` handler RECEIVES the Clack
environment plist and RETURNS the Clack response list — a Clack application IS a
rontolisp handler, and `clack.handler.rontolisp` converts nothing per request.
`rontolisp:fetch` keeps its `(:status :headers :body)` result plist unchanged
(the client side, `compiler/FetchResponseShape`, `.kb/fetch-http.md`).

## The invariant

**The shape is declared once; the construction is native to each backend.**

- `runtime/RontoClackEnv.FIELDS` — the ordered 15-key declaration, re-exported
  name for name by `compiler/ClackEnv` (which adds the AST scan). Every consumer
  switches over the fields with a `default ->` that throws, so adding a key
  fails each backend loudly until its extraction is written. `ClackEnvTest`
  pins the key set.
- `src/main/resources/am/ik/rontolisp/eval/http-server.lisp` (served by
  `eval/HttpServerLibrary`) — the ONE Lisp copy of the model: the environment
  builder (`%http-make-env` over a positional raw tuple), the response
  normalizer (`%http-normalize-response`), percent-decoding, the header table,
  the Host split, the buffered-body Gray class. Self-contained by rule (core
  built-ins + own defuns only).

Why the construction is NOT one shared Lisp function on every backend: the
first cut had every backend hand a raw tuple to `%http-serve-request`, and on
the interpreter building the environment in INTERPRETED Lisp cost 3.1x the
native-path throughput (18880 -> 6125 rps) and slowed the Clack path too. The
measured division that stands:

- **Interpreter** — `LispEvaluator.buildClackEnv` / `normalizeClackResponse` /
  `responseHeaders` / `responseBody`, all Java; only the cold response arms
  (an `(unsigned-byte 8)` vector body) delegate to the library's
  `%http-body-string`, whose packed vector `responseBody` then reads out as the
  raw octets it answers. The library loads EAGERLY at server start
  (`ensureHttpServerLoaded` — a lazy first-request load races
  one-virtual-thread-per-request, `.kb/concurrent-served-requests.md`), and
  lazily when a program calls a `RONTOLISP::%HTTP-*` function directly (the
  ci-spec shape cases; the usocket/restart `resolveFunction` pattern).
- **JVM** — the injected `handle(Request)` is THIN GLUE
  (`JvmHttpHandlerRuntimeBuilder`): hand-assembling the environment would buy
  nothing a Java method does not already give at the same speed and without the
  `maxStack` risk, so it is built by `runtime/RontoHttpClack.buildEnv` — real Java
  speaking the JVM
  runtime value rep, including the `_hash*` HashMap convention (prin1-text
  keys) — and the response marshalled back by its `toResponse`. The emitted
  bytecode keeps only what must call into the generated class: the
  `:raw-body` construction, `_invoke_1` + `_await` handler dispatch, a DIRECT
  INVOKESTATIC of the compiled `%http-normalize-response` (mangled name via
  `ClackEnv.NORMALIZE_RESPONSE`; the direct call is the edge that keeps the
  normalizer chain alive under `--optimize`'s class shaker), and `_drain_body`
  over the triple's body. `usesHashTables` is forced on by `usesHttpHandler`.
  Both classes, and the two declarations they read (`RontoClackEnv` for the key
  set, `RontoHashTable` for the table shape), live in `am.ik.rontolisp.runtime`
  and TRAVEL with the compiled output, so a served program runs on
  `java -cp . App` (`.kb/jvm-export.md`, "What travels"). That is the whole
  reason those four import nothing of the project's — the key set is declared
  in `RontoClackEnv` and `compiler/ClackEnv` re-exports it name for name, so
  the AST-scanning half can keep its rontolisp imports.
- **WASM component** — compiled Lisp: http.lisp's `%serve-handle` builds the
  raw tuple and calls the library's `%http-serve-request` (env build + run +
  normalize, ONE async frame). Compiled, so the interpreter's cost argument
  does not apply.

The transports keep only what is genuinely theirs (reading the wire, writing
the answer): `RontoHttpServer.Request` carries the 11 raw transport facts —
target verbatim (still percent-encoded, query included), headers in wire order
with duplicates kept, the body as BYTES, protocol/scheme/local/remote, and the
mount point (`scriptName`, a RAW prefix of the target; `""` on every
root-mounted transport — only the transport knows where it is mounted, so the
value enters through the tuple, not through a special case in the library).

## The fifth transport: the Servlet war (`-o app.war`)

A `rontolisp:http-handler` program compiles to a Servlet war that deploys
unmodified on any Servlet 6 container. The transport is two classes in
`runtime` — `RontoHttpServlet` (an eleven-field `Request` fill in, a `Response`
write out; byte-exact, no encode of its own; its `scriptName` is
`getContextPath() + getServletPath()` — the context path is UNDECODED per the
Servlet spec, which is what makes the sum a raw strippable prefix of the
target, and the sum generalizes past `/*`: a servlet prefix-mapped at `/api/*`
inside context `/myapp` mounts the application at `/myapp/api`) and
`RontoHttpServletInitializer`
(`@HandlesTypes(RontoHttpServer.Handler.class)`: the container hands over the
program class BECAUSE implementing `Handler` is already what the JVM backend
emits, so the war carries no name and no `web.xml`; its only non-class file is
the one-line `ServletContainerInitializer` service declaration, identical in
every war). They travel on the THIRD list,
`JvmHttpHandlerRuntimeBuilder.WAR_RUNTIME_CLASS_FILES`, reached only by a war
compile — their `jakarta.servlet` import is the one sanctioned exception to
the runtime package importing nothing (`.kb/jvm-export.md`, "What travels");
`jakarta.servlet-api` sits in the root pom in `provided` scope and reaches no
other artifact.

War mode is a reader feature (`Features.JVM_SERVLET`, `:rontolisp-servlet` —
the reactor precedent, so the Clack shim can branch on features and nothing
else) selected when `-o` ends in `.war`, and `JvmLispCompiler.servlet`:

- the top level moves into `<clinit>` exactly as a `jvm-export` moves it; the
  initializer runs it through `Class.forName(name, true, loader)` (a container
  loads `@HandlesTypes` candidates WITHOUT initializing). A war without the
  move deploys and 500s on every request with an unfilled handler slot — the
  initializer's reflective post-check turns that into a FAILED DEPLOYMENT, and
  a signalling top level (`ExceptionInInitializerError`) is rethrown as
  `ServletException` for the same reason;
- the directive stores the funcref and RETURNS (a written port warns once at
  compile time — the container owns the port); the `%http-server-start` seam
  REGISTERS AND RETURNS rather than refusing (handle 0, `join` returns at
  once, `stop` a no-op, `port` answers 0) — the register spelling is what
  makes the Clack servlet leg a feature-gated spelling of the same call;
- a program with no handler is refused at compile time in `JvmLispCompiler`
  (nothing for the container to call);
- the servlet is ASYNC by default and that is the concurrency invariant, not a
  tuning knob (`.kb/concurrent-served-requests.md`): `startAsync` +
  `setTimeout(0)` + one virtual thread per request, `complete()` in a
  `finally`. Opt-out: `rontolisp.async` context param `false`; a filter chain
  that refuses async falls back to sync with one warning. One handler slot per
  WEBAPP (per class loader), so two rontolisp wars in one container do not
  collide;
- `JvmWarWriter` is `JvmJarWriter` with a different entry prefix and manifest
  (no `Main-Class`); byte-identical across compiles
  (`.kb/emitted-output-determinism.md`).

Pinned by `WarE2eTest` (opt-in `-Drontolisp.war.e2e=true`): the spike table on
BOTH embedded Tomcat and Jetty (initializer discovery is a container behavior,
not a spec guarantee), the octet-body row on RAW bytes, the distinct-thread
concurrency pin, and both deployment-failure shapes. Structure:
`RontoLispCliTest`'s war tests and
`JvmHttpHandlerTravellingRuntimeTest.aWarCarriesTheServletTransportWhoseOnlyOutsideReferenceIsTheServletApi`.
An embedded Jetty needs `AnnotationConfiguration` added before it runs
initializers at all; a standalone Jetty enables its `annotations` module for a
deployed webapp on its own. Tomcat needs nothing.

## The environment contract (all verified against upstream Clack)

`:REQUEST-METHOD` upcased interned keyword / `:SCRIPT-NAME` the application's
mount point, percent-decoded — `""` on every root-mounted transport, which is
all of them but the Servlet war under a context path (Rack/PSGI: `SCRIPT_NAME`
is the mount point, `PATH_INFO` the remainder; `lack/app/mount` and the session
middleware `setf getf` these two keys on the same convention). The raw prefix
comes off the target BEFORE percent-decoding (what makes it strippable at
all); a `scriptName` that is not a prefix of the target degrades to the
root-mounted split rather than signalling. Pinned on all four backends by
ci-spec `http-clack-script-name`, end to end by `WarE2eTest`'s context-path
leg / `:PATH-INFO`
percent-decoded, with the raw mount prefix stripped first (lenient decoder: a
malformed escape copies verbatim; `+` is
NOT decoded — that is a query-string rule); a request for the mount point
itself gets `""`, not `/` / `:QUERY-STRING` raw text after the
first `?`, nil when absent / `:SERVER-NAME` + `:SERVER-PORT` from the `Host`
header (port = last colon followed only by digits, so IPv6 literals survive),
else the listening address / `:SERVER-PROTOCOL` keyword / `:REQUEST-URI` the
raw target verbatim / `:URL-SCHEME` / `:REMOTE-ADDR` + `:REMOTE-PORT` (real on
the JDK backends; nil on the component — `wasi:http@0.3.0` exposes no peer
accessor; **re-evaluation trigger**: if a later wasi:http adds one, wire it in
`%serve-handle`'s raw tuple and delete this clause) / `:HEADERS` equal hash
table, lowercased names, repeats joined `", "`, never nil / `:CONTENT-TYPE` /
`:CONTENT-LENGTH` integer or nil / `:RAW-BODY`. The plist is freshly consed and
proper per request (lack-request `rplacd`s it; mount/session `setf getf` into
it). Pinned across all four backends by ci-spec `http-clack-environment-shape`
/ `http-percent-decode`.

**The UTF-8 decoder is a RANGE decoder** (`%http-utf8-decode-octets v start
end`, plus `%http-utf8-length` for one lead byte and `%http-utf8-complete-end`
for where a range's last COMPLETE sequence ends). `%http-utf8-decode` — the
percent-decoder's list spelling — is one line over it. The range shape is not
generality for its own sake: a CHUNKED body source (`.kb/clack.md`, "The head
and the body source") has to ask where a chunk may be cut without splitting a
code point, which is exactly `%http-utf8-complete-end`, and it decodes octets
that arrive in a packed vector rather than a list. Lenient in both spellings: a
byte that leads no valid sequence, and a sequence the range truncates, come
back as their own characters, so attacker input never signals.

## `:raw-body` — two modes, a compile-time constant

The directive (and the `rontolisp::%http-server-start` seam) take
`:raw-body :stream` (default) / `:buffered`; `ClackEnv.usesBufferedBody` scans
the program (BEFORE `HttpLibrary` rewrites the directive away — order in
`RontoLispCli`), and one flag describes the program (one handler slot).

The HOST-DRIVEN REACTOR carries the same two modes but decides them at RUN
time, registered with the application (`%http-reactor-register app
[:buffered]`), because there is no `http-handler` call left on that transport
— `.kb/clack.md`, "The head and the body source". Since todo-341 Phase 2 the
directive's mode reaches it: it used to be dropped, and the reactor always
buffered.

- **`:stream`** — rontolisp-native: the asynchronous stream, drained with
  `(rontolisp:await (rontolisp:read-all ...))`; a bodiless request gets an
  already-closed stream. Nothing is buffered on the component (the body
  streams from the host). Its chunks are OCTET vectors on every backend (the
  todo-370 rule every HTTP body stream follows, `.kb/fetch-http.md`); `read-all`
  decodes.
- **`:buffered`** — what Clack needs (its `:raw-body` is a SYNCHRONOUS stream;
  a synchronous read cannot block on a WASI future, so buffering is the only
  shape a Clack app can consume): the body is read in full and wrapped in a
  BIVALENT in-memory stream — `read-line`/`read-char` AND
  `read-byte`/`read-sequence` off ONE byte cursor, with a REAL
  `file-position`. That is what lets the `lack:builder` ->
  `circular-streams` -> `http-body:parse` chain run on the native server
  (the pre-cutover `%make-string-input-stream` body rejected `read-byte`, so
  sessions/CSRF/ningle could not work there — the `LackEcosystem*E2eTest`
  served-body legs pin the fix, on all four backends). A bodiless request gets
  `:raw-body nil` (upstream guards with `(when raw-body ...)`) and pays
  nothing.

The buffered construction is per backend too, and this is a measured decision:

- Interpreter: `eval/HttpRequestBodyStream` — a Java `byte[]` + cursor entry in
  the stream table (opened/closed via `Environment`'s
  `httpBodyStreamOpener/Closer` hooks; the TRANSPORT closes it when the
  request ends, so the table never grows per request). `read-line` /
  `read-char` / `peek-char` / `file-position` have concrete-type arms in
  `Environment`; `read-byte`/`listen` take it through the existing
  `InputStream` arm. Serving reads through the Gray class instead cost a
  per-character generic dispatch in interpreted Lisp: a measured -36% POST
  throughput.
- JVM + WASM: the compiled Gray class (`http-request-body-stream` in
  http-server.lisp, over `rontolisp:fundamental-binary-input-stream`) built by
  `%http-body-stream` — over the request's OCTETS on both (the JVM's `handle`
  hands `RontoHttpClack.bodyOctets` over; it used to pass
  `bodyString()`, a decode the constructor then re-encoded, doubling every
  octet >= #x80 of a binary POST — closed with todo-370); the class carries a single-pass `stream-read-line`
  method so even compiled code never pays per-character generic dispatch for
  the hot line read. The Gray `:raw-body` needs `GrayStreamsLibrary.process`'s
  call-site rewrite, which is why every harness that drives a compiler
  directly must run `HttpServerLibrary.process` THEN (after macro expansion)
  `GrayStreamsLibrary.process`, mirroring the CLI.

`%http-body-stream` takes the TEXT a transport read **or** the OCTETS a
byte-shaped one did, and the second spelling is a correctness rule, not a
convenience: the class it builds is a byte stream, so a body that already is
octets is stored as it is. Encoding is what a text body needs and re-encoding is
what a byte body must never get — the decoder here is lenient by construction, a
byte that starts no sequence answering its own character, so a binary body
decoded to text and encoded again comes back doubled (`ff fe 41` →
`c3 bf c3 be 41`). That is the request-side twin of the loss `%http-body-string`
stopped inflicting on the way out, and it is why a reactor's `:buffered` drain
answers octets (`.kb/clack.md`).

**The `:raw-body` element type is `character`, on every construction.** The Gray
class subclasses BOTH input base classes, which is what makes its bivalence
declared rather than merely implemented, and the bivalent rule
(`.kb/gray-streams.md`) then answers `character`; the interpreter's Java-backed
twin, a stream HANDLE, answers `character` through the lite built-in. The two
constructions are meant to be observably identical and this is the one place they
were not: the Gray class was binary-only, so `stream-element-type` answered
`(unsigned-byte 8)` there and `character` on the JDK-served interpreter path.
Why `character` and not the octet type the body IS: the answer's job is to size a
buffer -- `(make-array content-length :element-type (stream-element-type
stream))` is how tiny-routes' `read-stream-to-string` allocates one -- and the
octet buffer it produced was then handed to `write-sequence` on a
`with-output-to-string` sink, which signals here and on SBCL alike. Upstream's
`:raw-body` is a flexi-stream, which answers the character type for the same
reason. **This costs the byte-exact relay nothing**: nothing on the read or drain
path consults `stream-element-type` (`read-sequence` picks bytes vs. characters
off the SEQUENCE), the octets are still stored and still handed over unchanged,
and `read-byte` / `file-position` are untouched. Pinned by ci-spec
`http-buffered-body-stream` (all four backends) and, on the reactor path a Clack
app actually takes, `LispEvaluatorAsdfTest.aBufferedRawBodyAnswersACharacterElementType`.

**Module-size filter**: `HttpServerLibrary.process(program, bufferBody)` drops
the buffered-body half (the Gray class + `%http-body-stream` +
`%http-utf8-encode`) from a default-mode program — on a WASM serve component
that machinery was a measured 35% of the module for code no request could
reach. `HttpLibrary` likewise SYNTHESIZES `%serve-request-body` per mode
(pass-through vs drain+wrap) instead of branching on a runtime flag, so a
default-mode component carries no reference to the dropped half. A program
naming `%http-body-stream` directly (ci-spec `http-buffered-body-stream`)
keeps it regardless.

## The response contract

`(status headers [body])`, normalized by ONE function
(`%http-normalize-response` — the interpreter mirrors it in Java, arm for arm):

- `status` must be an integer — a non-integer car SIGNALS (no more implicit
  200). The two-element bodyless form is legal — lack's `finalize-response`
  answers it when `make-response` was given NO body argument. A ningle 404 is
  NOT that shape: its `make-context` passes the body argument, so `has-body`
  is true and the 404 is the three-element `(404 () (NIL))` (measured on SBCL
  and here; the attribution correction of todo-304).
- `headers`: keyword plist, or (widening) a dotted alist so a fetch result's
  `:headers` passes straight through. Every pair becomes its own header line
  (repeated `:set-cookie` correct by construction); `content-length` /
  `transfer-encoding` are dropped (the transport computes framing).
- `body`: a LIST of strings (joined), nil, an `(unsigned-byte 8)` vector, or a
  rontolisp stream (drained — the one extra await). The last TWO come back
  UNCHANGED (todo-341 Phase 3b): only the transport knows what it can carry, so
  the normalizer answers a string, octets or a stream, and every transport that
  writes the wire itself writes the octets AS THEY ARE (byte-exact, see below).
  Only the reactor envelope's no-sink arm renders them, as the lenient UTF-8
  text they spell (`%http-reactor-body-envelope-text`), because its head is a
  JSON string. A stream DRAINS (`%http-drain`, and the reactor's synchronous
  `%http-reactor-body-drain`) to ONE octet vector when its chunks are octets —
  every HTTP body stream's shape, so a proxied fetch reply goes out byte-exact
  — and to one string when they are strings (a guest `make-stream`); a mixed
  stream is refused. The join is `%http-octets-join`, an aref/aset blit written
  in this prelude-free library (the prelude's `%octets-join` is the same loop
  for `read-all`). A
  **NIL element inside the list**
  contributes the empty string rather than signalling: that is how upstream
  renders it (clack-handler-hunchentoot writes every chunk through
  `flex:string-to-octets`, which answers `#()` for NIL), and it is the ordinary
  shape of a controller that returned nil — every ningle 404 is
  `(404 () (NIL))`. **A BARE STRING SIGNALS**,
  upstream-faithful: lack's `finalize-response` wraps a string controller
  result in a list (its `(pathnamep body)` branch stopped claiming strings
  when the pathname became a distinct value, todo-304 / `.kb/pathnames.md`),
  so a bare string reaching the transport is a malformed response. A PATHNAME
  body — `lack/app/file`'s file-serving form — is refused by the
  unsupported-type arm until the transport can serve a file.
- A FUNCTION response is Clack's DELAYED form only — called with a responder
  that captures the real response; the streaming WRITER protocol is refused by
  the closure the responder returns.

Migration hazard (also in the docs): the response side fails loudly, the
request side fails SILENTLY — `(getf env :method)` just returns nil in a
half-migrated handler.

Pinned across all four backends by ci-spec `http-response-normalizer`; the
served round trips by `HttpHandlerTest` / `HttpHandlerJvmTest` /
`WasmLispCompilerIntegrationTest` serve cases / `ClackE2eTest` /
`LackEcosystemE2eTest` + `LackEcosystemWasmE2eTest`.

## A binary response body is byte-exact — everywhere but one named place

**An `(unsigned-byte 8)` response body reaches the wire as the octets it holds,
on every transport that writes the wire.** It used to reach it double-encoded:
the body was flattened one code point per octet and the transport then wrote
`getBytes(UTF_8)`, so `ff fe 41` went out as `c3 bf c3 be 41` (measured on the
interpreter's JDK server, 2026-08-13). The comment that justified the flattening
— "the transport writes those characters back out one byte each, so the bytes
survive" — was never true of any transport.

The fix is one shape repeated per transport: **carry the octets, do not render
them.**

- The shared normalizer stopped flattening (todo-341 Phase 3b, the response
  contract above) — the precondition for all of it, pinned by ci-spec
  `http-response-normalizer`.
- `%http-serve-request` hands a string OR an octet vector to the transport and
  resolves only a STREAM body (that is the arm that needs the await).
- **JDK (interpreter + JVM)**: `RontoHttpServer.Response` holds `byte[] body`
  — the shape `Request` already had — with a `Response.of(status, headers,
  String)` factory for the ordinary text body. `LispEvaluator.responseBody`
  answers octets (its cold arm reads the packed `LispIntVector`
  `%http-body-string` handed back; its stream arm drains octet chunks to bytes)
  and `RontoHttpClack.toResponse` reads the JVM `long[]{width, e0, ...}`
  (`_drain_body` answers one for an octet-chunk stream); `writeResponse` no
  longer encodes anything.
- **`--component`**: the octets cross `%http:body-stream-write` as a `list<u8>`,
  and the canonical lowering takes a packed `(unsigned-byte 8)` vector for a
  `list<u8>` / `stream<u8>` parameter — `WasmComponentImportCompiler`'s
  `emitStageBytesParam`, which stages the raw array bytes instead of
  `_str_to_mem`'s UTF-8 (`.kb/wit.md`, "list<u8> = string"). Any other value
  takes the string path unchanged, so a `string` parameter is byte-identical.
- **Reactor**: the byte-shaped `env.writeResponseBody` sink takes them as they
  are (`WasmReactorResponseBodyE2eTest`).

Pinned by `HttpHandlerTest.directiveServesAnOctetBodyByteExactly`, its
`HttpHandlerJvmTest` twin and
`WasmLispCompilerIntegrationTest.httpHandlerServesAnOctetBodyByteExactlyUnderWasmtimeServe`
— all three assert the RAW response bytes, because the text spelling passes on
the double-encode. The STREAM body (a relayed fetch reply) is pinned the same
way on all four backends by the todo-370 relay tests (`.kb/fetch-http.md`).

**The one exception, deliberately**: the reactor envelope's NO-SINK arm
(`%http-reactor-body-out`) renders octets — an octet body, or a stream's octet
chunks, joined — as the TEXT their UTF-8 bytes spell, leniently, through
`%http-reactor-body-envelope-text` (`rontolisp::%octets-to-string`). Its head
is a JSON string, which is text; a host that wants a binary response registers
`env.writeResponseBody` — taking bytes out of band is what the sink is FOR. Why
DECODE rather than the one-char-per-octet flattening the arm used to apply
(todo-370): the host UTF-8 encodes the JSON string, so flattening doubled every
octet >= #x80 — a page a Clack app answered through `flex:string-to-octets`
crossed the envelope mojibake'd — while decoding hands text-in-octets over
intact, and binary is lost under either rendering. **Re-evaluation trigger**:
this stays as long as the envelope's `"body"` key is a JSON string. Give the
envelope a byte-shaped spelling and the arm goes. (`%http-octets-string` /
`%http-body-text`, the flattening helpers, are deleted.)
