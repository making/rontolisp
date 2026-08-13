# The server-side HTTP value model (`rontolisp:http-handler` = Clack)

Since the todo-258 cutover a `rontolisp:http-handler` handler RECEIVES the Clack
environment plist and RETURNS the Clack response list — a Clack application IS a
rontolisp handler, and `clack.handler.rontolisp` converts nothing per request.
`rontolisp:fetch` keeps its `(:status :headers :body)` result plist unchanged
(the client side, `compiler/FetchResponseShape`, `.kb/fetch-http.md`).

## The invariant

**The shape is declared once; the construction is native to each backend.**

- `compiler/ClackEnv.FIELDS` — the ordered 15-key declaration. Every consumer
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
  `%http-body-string`. The library loads EAGERLY at server start
  (`ensureHttpServerLoaded` — a lazy first-request load races
  one-virtual-thread-per-request, `.kb/concurrent-served-requests.md`), and
  lazily when a program calls a `RONTOLISP::%HTTP-*` function directly (the
  ci-spec shape cases; the usocket/restart `resolveFunction` pattern).
- **JVM** — the injected `handle(Request)` is THIN GLUE
  (`JvmHttpHandlerRuntimeBuilder`): a compiled http-handler class is not
  standalone anyway (it needs the rontolisp jar), so the environment is built
  by `eval/HttpHandlerJvmRuntime.buildEnv` — real Java speaking the JVM
  runtime value rep, including the `_hash*` HashMap convention (prin1-text
  keys) — and the response marshalled back by its `toResponse`. The emitted
  bytecode keeps only what must call into the generated class: the
  `:raw-body` construction, `_invoke_1` + `_await` handler dispatch, a DIRECT
  INVOKESTATIC of the compiled `%http-normalize-response` (mangled name via
  `ClackEnv.NORMALIZE_RESPONSE`; the direct call is the edge that keeps the
  normalizer chain alive under `--optimize`'s class shaker), and `_drain_body`
  over the triple's body. `usesHashTables` is forced on by `usesHttpHandler`.
- **WASM component** — compiled Lisp: http.lisp's `%serve-handle` builds the
  raw tuple and calls the library's `%http-serve-request` (env build + run +
  normalize, ONE async frame). Compiled, so the interpreter's cost argument
  does not apply.

The transports keep only what is genuinely theirs (reading the wire, writing
the answer): `HttpHandlerSupport.Request` carries the 10 raw transport facts —
target verbatim (still percent-encoded, query included), headers in wire order
with duplicates kept, the body as BYTES, protocol/scheme/local/remote.

## The environment contract (all verified against upstream Clack)

`:REQUEST-METHOD` upcased interned keyword / `:SCRIPT-NAME` `""` / `:PATH-INFO`
percent-decoded (lenient decoder: a malformed escape copies verbatim; `+` is
NOT decoded — that is a query-string rule) / `:QUERY-STRING` raw text after the
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
  streams from the host).
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
  `%http-body-stream`; the class carries a single-pass `stream-read-line`
  method so even compiled code never pays per-character generic dispatch for
  the hot line read. The Gray `:raw-body` needs `GrayStreamsLibrary.process`'s
  call-site rewrite, which is why every harness that drives a compiler
  directly must run `HttpServerLibrary.process` THEN (after macro expansion)
  `GrayStreamsLibrary.process`, mirroring the CLI.

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
- `body`: a LIST of strings (joined), nil, an `(unsigned-byte 8)` vector (one
  char per octet; see the known non-byte-exact bug below), or a rontolisp
  stream (drained — the one extra await). A **NIL element inside the list**
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

## Known bug in the same area (pre-existing, NOT part of the cutover)

An `(unsigned-byte 8)` response body is not byte-exact: each octet becomes a
code point and `writeResponse` re-encodes UTF-8, so any octet >= 0x80 is
mangled. The fix is a fourth `binaryp` slot on the canonical response written
as ISO-8859-1.
