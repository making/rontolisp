# Futures (`rontolisp:await` / `futurep`) and `rontolisp:fetch` (async HTTP)

`rontolisp`-package functions (not CL). `fetch` returns a **future** at once
(`.kb/async-await.md` has the future/stream machinery); `await` resolves it. Composition is
`async-defun`/`async-lambda` + `await` (no `then`). Futures print `#<FUTURE>`, streams `#<STREAM>`.

**Future representation**: interpreter `LispFuture` (wraps `CompletableFuture<LispVal>`); JVM a bare
`CompletableFuture`; WASM `--component` the `TYPE_FUTURE` struct; Preview 1 the degenerate
`TYPE_P1_FUTURE` (settled at creation).

**fetch needs a transport**: `WasmFetchCompiler` refuses a plain Preview 1 `.wasm` (a `--native`
output fetches through its runner, "--native") and `--no-wasi` without `--host-fetch`. Interpreter
(`eval/HttpSupport.requestAsync`; the per-request client is deliberately never closed) and JVM use
the JDK `HttpClient`. The JVM's transport is Java, not bytecode: `_fetch` (`JvmFetchRuntimeBuilder`)
reads options and renders values through `_strv`, then `runtime/RontoFetch` (which TRAVELS,
`.kb/jvm-export.md`). `RontoFetch.start` settles ONCE to the plist, so every await answers the same
(`eq`) plist and body stream; an unbuildable request fails the future instead of throwing. The plist
key order `RontoFetch.RESPONSE_KEYWORDS` is checked against `FetchResponseShape.responseFields()` at
compile time.

**Error timing** is JS-like: options validated at `fetch`; request/transport failures surface at
`await` on EVERY backend, and again at every later await. WASM signals `rontolisp:wit-error`;
interpreter/JVM a plain error whose report is the host failure's message or its `toString` -- a known
type divergence. An unbuildable URL (a space in the path, `http:x`) is such a failure, and every
transport's message names the URL. A transfer failing mid-body signals at the DRAIN, never reading as
a shorter body.

## `--host-fetch` (the `--no-wasi` reactor transport)

`HostFetchLibrary` splices generated Lisp -- TWO `rontolisp:wasm-import`s, appended after the
program so user import ordinals are unchanged:

```
env.fetch(headPtr, headLen) -> (ptr, len)   ; request head JSON -> response HEAD json
env.readResponseBody(ptr, cap) -> i32       ; :bytes, :async t; 0 = EOF, <0 = failed
```

Envelope JSON keys are DERIVED from `FetchResponseShape`'s records (`body` an `option<string>` so an
absent `:body` is an absent key; `octets` likewise, "The request body"; the error arm
`FetchResponseShape.HOST_ENVELOPE_ERROR_KEY` signals at the fetch). `fetch` answers the settled
`TYPE_P1_FUTURE` (the host call blocks the wasm stack; a failure before the head signals at the CALL).

Gates: the splice requires the program to reference `rontolisp:fetch`; `hostFetch` requires `noWasi`
and rejects `component`; the CLI rejects `.class`/`--no-gc`. The BUILD prints the host obligation (a
`WebAssembly.Suspending` `env.fetch` needs `WebAssembly.promising` entry + serialised calls; a
re-entered export traps, `.kb/wasm-import.md`) plus a `NoWasiLoadPathRefusals` line when the load
path reaches a fetch (a suspending host cannot serve `_initialize`).

**The `:body` split** is opt-in `--host-boundary=streaming` (default `envelope`,
`compiler/HostBoundary`). Without it only `env.fetch` is imported and `:body` comes from the head's
own `"body"` key; `:body` is the same first-class stream either way. Why the split exists: in the
envelope the body is one JSON string, so a BINARY reply cannot cross (`ff fe 41` came back as code
point 0x1FE062) and a Worker cannot forward a streamed upstream reply.

- The pull thunk is the reactor's own transport (`%http-reactor-buffer`/`-chunk`/`-body-stream`,
  `.kb/clack.md`): ONE receive buffer serves both directions. Naming them splices
  `http-reactor.lisp` (and `http-server.lisp`). It CALLS the import rather than `#'name` (the
  suspending-import report follows calls). **Trap**: its parameter is `token`, not `open` --
  `NoWasiFilesystemStubs` rewrites a variable named `open` into a call to `cl:open`.
- **The head's `"body"` key survives as the IN-BAND fallback**; an absent-body reply (HEAD, 204) is
  EOF at the first pull.
- **One live reply body, no handle -- in the SERIALISED shape**: the host has ONE read cursor, so a
  module-side counter (`%host-fetch-open`) makes draining a SUPERSEDED body signal instead of
  reading the next reply's octets. Under `--reentrant` the handle exists:
  `env.readResponseBody(id, ptr, cap)`, the id in `FetchResponseShape.HOST_BODY_ID_KEY`, one reader
  per reply in `defaultHost()`, no counter. The serialised shape is byte-unchanged.
- **A NEGATIVE count is the error channel** (else a transport dying mid-body looks like a short
  body). It signals at the DRAIN.

The host half is GENERATED (`--emit-js-glue`, `.kb/wasm-import.md`; `defaultHost()`):
`examples/cloudflare-workers/dog-fetcher/src/worker.js` is emitted and checked in. Pinned by
`HostFetchLibraryTest`, `FetchResponseShapeTest`, `WasmImportCompilerTest` and
`WasmHostFetchBodyE2eTest` (node host sharing memory: portable drain, `ff fe 41` exact, in-band
fallback, a 256 KiB undrained reply not growing `memory.buffer.byteLength`, mid-body failure,
superseded-body guard).

## `--native` (the runner transport)

A native executable's module is the Preview 1 command module and its runner is the host
(`.kb/native-output.md`, "The network runner"). `HostFetchLibrary.processForRunner` (spliced when
`Options.runnerHosted()`) appends the reactor's request builder, validation, conversions, parser and
`%http-reactor-body-stream` over three imports of the runner's module `rlhttp`
(`FetchResponseShape.RUNNER_*`):

```
rlhttp.start(request-json) -> externref      ; the request is in flight on return
rlhttp.head(reply) -> head-json               ; blocks until the head (or its "error" arm)
rlhttp.readResponseBody(reply, ptr, cap) -> i32  ; 0 = end, -1 = failed mid-body
```

- **The request is in flight when `fetch` returns, and the future settles at its first await** (the
  JDK backends' timing). The future is a DEFERRED `TYPE_P1_FUTURE` (`rontolisp::%future-deferred`,
  kind 3): a thunk `_p1_future_await` calls on every await, which keeps the parsed plist (`eq` on a
  second await) and signals an error head at each await. Emitted only when the program names
  `%future-deferred` (`usesDeferredFutures`), so other modules are byte-identical (`NativeFetchTest`).
- **Where the state machines run** (a Preview 1 module naming `%future-new`, e.g. a Java program
  starting threads, `.kb/async-await.md`) the primitive is a `TYPE_FUTURE` in
  `WasmFutureRuntimeBuilder.STATE_DEFERRED`: `_future_poll` calls the thunk, so an await never
  suspends and blocks the one stack in `rlhttp.head`. Only in a module naming the primitive
  (`usesAsyncDeferredFutures`); `%future-settled-p` answers `t`. Pinned by `NativeFetchTest` and the
  `async` program of `java-http-spec.yaml`.
- **The reply is an `externref` handle**: replies drain independently. When the collector finds the
  handle dead the runner shuts the socket and frees the buffer; the runner itself triggers a
  collection at 256 live replies or 64 MiB buffered (host memory does not make the module collect).
- **The runner's client is modelled on the JDK `HttpClient`** (`runner/src/http/wire.rs`): reserved
  fields (`connection`, `content-length`, `expect`, `host`, `upgrade`) fail the request; reply names
  lowercased and sorted, values ISO-8859-1; no redirects; no decompression; empty
  `POST`/`PUT`/`PATCH` states `Content-Length: 0`. HTTP/1.1 only, one connection per request, ALPN
  `http/1.1`; a thread per request buffers the body. The default User-Agent is added module-side
  (`%host-fetch-agent`).
- **TLS**: rustls over ring. Roots: compiled-in `webpki-roots` (what wasmtime's `wasi:http` trusts),
  or -- REPLACING them -- the PEM bundle `SSL_CERT_FILE` names (an unreadable or empty bundle fails
  each HTTPS request naming the variable, no fallback). The handshake runs in `start`'s thread, so an
  untrusted certificate signals at the await.
- **macOS**: waiting for a head or chunk turns the event loop once the application started
  (`.kb/objc.md`, "--native").
- **Throughput**: the native output drains a large body far faster than the interpreter or JVM, given
  `%http-reactor-chunk`'s `subseq` takes `%replace-bulk` (one `array.copy`, `.kb/subseq-runtime.md`).
  The import wrapper's `_bytes_fill` (one `array.set` per octet: wasm-GC has no memory -> array bulk
  instruction) remains a cost; an 8-octet `i64.load` unrolling measured the same (the per-element
  bounds check is the cost), so the byte loop stays. The `--host-fetch` body takes the same path.
- **`read-all` of a BINARY body** must take the native lenient decode, not the per-byte loop, which
  exhausted the GC heap on a large `--native` body.
- **`read-all` on the JDK backends**: an octet is a byte
  ([packed-integer-vectors.md](packed-integer-vectors.md)), the chunk list is dropped once joined,
  and the decode writes its result once at a pre-counted size; what remains is the octets once plus
  the string. Pinned by `ReadAllHeapBoundE2eTest` (32 MiB binary body in a 320 MiB serial-GC heap,
  both backends).

## The cross-backend corpus and its known divergences

`src/test/resources/fetch-spec.yaml` (`FetchSpecE2eTest`) runs one corpus against one local origin
on every transport: interpreter, JVM, `--native` output and `--component` under
`wasmtime run -S http=y`, one program per leg, cut into cases at a printed end mark per case (NOT by
line counts, so one short case cannot shift every later case). In `./mvnw test` (native leg skips
without the host's pair, component leg without wasmtime) and in CI's native-image-verify job. The
`--host-fetch` reactor has no leg (`WasmHostFetchBodyE2eTest`).

Same test, second corpus `clojure-http-spec.yaml`: `rontolisp.http-client` and clojure.java.io's
`http:` reads (`.kb/clojure-frontend.md`, "HTTP client", "clojure.java.io"). Its one skip: the
`--native` leg of a timed `deref` of a pending reply (a Preview 1 async body runs to its end at the
call).

Third, `java-http-spec.yaml` (`#javaHttpClient`): the Java subset's `java.net.http`
(`.kb/java-frontend.md`, "java.net.http"), three `.java` programs per leg cut at `== name` lines; the
JDK itself is a fifth leg, the oracle. One program starts threads (`sendAsync`), so its wasm legs run
the state machines.

A skipping case names the divergence. Not pinned (the corpus looks fields up by name):
- **Component** `:headers` lack `transfer-encoding` (the host strips it).
- **JDK backends over HTTPS** negotiate HTTP/2 where offered, so no hop-by-hop fields; the runner
  reports `connection` / `transfer-encoding` as sent.

Component traps the corpus found:
- **The readable end of the request's transmission future must stay open until the reply body is
  released** (`%fetch-body-finish`): wasmtime's wasi-http `send` ties the connection's I/O task to
  that future as an abort-on-drop handle, so dropping it after `send` cut every body that paused or
  outran the head. The Lisp-driven wasi:http tests
  `WasmLispCompilerIntegrationTest.componentImportLetsLispDriveWasiHttpAcrossSeparatelyImportedInterfaces`
  and `componentImportDropsResourcesSoALispRequestCanCarryABody` drop it after the body for the same
  reason.
- A region catching a signal on a RESUMED frame must not restore `$rt` to the resume route
  (`.kb/wasm-landing-pad-refresh.md`, "`$rt`").
- The method is validated at the call (outside any handler), and `%fetch-run` builds and sends
  INSIDE its async body, so build or transport failure rejects the future.
- **wasi:http ends a cut-short body exactly like a whole one** and puts the `error-code` in the
  TRAILERS future; the reply's release reads it at the end and signals (corpus case
  `a-body-cut-short-signals-rather-than-reading-short`).

## Timeouts

**`:connect-timeout` and `:first-byte-timeout`, positive integers of milliseconds; a transport carries
its timeout arm only in a program that NAMES one of the two keywords (`HttpLibrary.namesTimeout`), so
every other program compiles to the same bytes.** A non-positive-integer is refused at the call in the
same words everywhere (`expects a positive integer of milliseconds`); an expired timeout fails the
future (signals at the await). `:first-byte-timeout` is the JDK's `HttpRequest.Builder.timeout`: the
HEAD is due, counted from the call; the body is not timed.

- **Interpreter, JVM**: `RontoFetch.request` sets `timeout`, `RontoFetch.client` `connectTimeout`.
  Errors `request timed out` / `HTTP connect timed out`.
- **Component**: `%fetch-request-options` (wasi:http `set-connect-timeout` / `set-first-byte-timeout`,
  ns). wasmtime counts the first byte from the connection, so the connection gets no longer than
  the first byte (a blackholed address gives up at the first-byte timeout, the head may take it once
  more). Errors: `:CONNECTION-TIMEOUT` / `:CONNECTION-READ-TIMEOUT`. wasmtime 51 answers as 49.
- **`--native`**: the request JSON carries `connect-timeout` / `first-byte-timeout`
  (`%host-fetch-timeout` validates); `wire.rs` uses `TcpStream::connect_timeout` against the earlier
  instant and a `Deadline` arming read timeouts through TLS handshake and head. A first-byte deadline
  passing during connect reports `connect timed out`, as the JDK does.
- **Refused by name**: a `--host-fetch` reactor at the call (its host owns the connection;
  `FetchResponseShape.hostRequestFields`); a component that SERVES at the call (`%fetch-untimed`:
  `import-block-http-server.bin` states no `request-options`); the browser playground at the await
  (`Target_HttpSupport`). Each is `.todo/148`.
- **The gate**: `HttpLibrary.untimed` substitutes the pre-timeout forms; `HostFetchLibrary` generates
  the timed members only for a timed program. **Trap**: a program that builds the keyword at run time
  (`intern`) and never spells it gets no timeout.
- **Pinned by** `fetch-spec.yaml` `a-reply-head-is-due-within-its-first-byte-timeout` (four legs; it
  makes that corpus timed, so the untimed forms are covered by the Clojure corpus and the Java
  `async` program), `java-http-spec.yaml` `a-request-and-a-connection-have-time-limits`,
  `HttpLibraryTest`, `HostFetchLibraryTest`, `FetchResponseShapeTest`, the runner's
  `a_head_is_due_within_the_first_byte_timeout_and_a_body_is_not`. An expiring CONNECT timeout has no
  pin (no local origin can hold a connect unanswered).

## The response plist

**`(:status <int> :headers <alist> :body <stream>)` on every backend**, drained with
`(rontolisp:await (rontolisp:read-all ...))`. The shape is written once in
`compiler/FetchResponseShape`, derived by each backend. The server side does not share it
(`.kb/http-server.md`).

**`:headers` ORDER is part of the contract**: names lowercased, ONE pair per value, ascending name
order, one field's values in wire order -- what the JDK's `HttpHeaders` (`TreeMap`), the runner and a
JS `Headers` iteration already answer; the component sorts (`%http-sorted-fields`, stable) on the
fetch reply only. **HTTP/2 pseudo-fields are NOT fields** (RFC 9113 8.3) and are dropped. Both JDK
backends list fields through `RontoFetch.responseFields`, pinned by `RontoFetchTest`; the order by
corpus case `fields-come-sorted-by-name-a-fields-values-in-wire-order`.

**`:body` is a stream of OCTET chunks on every backend** (and a served request's default `:raw-body`):
`(unsigned-byte 8)` vectors of wire bytes, nothing decodes, so relaying is byte-exact. A character
stream silently corrupted binary bodies (`ff d8 ff` -> `c3 bf d8`); a bivalent stream was rejected (a
second read primitive on four stream runtimes).

Per backend: interpreter `HttpSupport.BodyPump`, one `LispIntVector` per publisher batch; JVM
`RontoFetch` with `BodyHandlers.ofPublisher()`, future settling at the head, `RontoFetch.BodyPump`
queuing one `byte[]` per batch -- a mid-body failure is a failed future ahead of the pill; a
program-closed stream cancels the subscription; `_drain_body` refuses a mixed stream; `usesIntArray`
forced on by `usesFetch || usesHttpHandler`. The pump travels as a second class file
(`JvmFetchRuntimeBuilder.RUNTIME_CLASS_FILES`). Pinned by `RontoFetchTest` (a held reply's first part
before the rest; a cut transfer) and the clojure-http corpus' `/held` case on JVM. Component: the
`stream<u8>` READ lift answers a packed vector (`_bytes_from_mem`, `.kb/wit.md`). `--no-wasi`:
`%stream-new` over `%http-reactor-octet-source`.

`read-all` (prelude) joins the chunks (`%octets-join`) and decodes once with
`rontolisp::%octets-to-string` -- LENIENT UTF-8 as `http-server.lisp`'s request decoder, mirrored
natively by `Environment` (`LispPreludeLibraryTest`). A packed octet vector goes to the native
`rontolisp::%octets-to-string-packed` (one lenient transcode on JDK backends; validate-then-`array.copy`
on wasm, `.kb/async-await.md`); the per-byte loop is only the general-array fallback. **The wasm raw
copy is sound only because its validator is STRICT** -- `_str_char_at`'s lead ranges are NOT that
validator. Gates: ci-spec `read-all-decodes-an-octet-chunk-stream`,
`HttpHandlerTest.directiveRelaysAFetchedBodyByteExactlyAndReadAllStillDecodesIt` + its
`HttpHandlerJvmTest` twin,
`WasmLispCompilerIntegrationTest.httpHandlerRelaysAFetchedBodyByteExactlyUnderWasmtimeServe`.

## Decompression

**`rontolisp:fetch` never decodes a content coding; the decoder is a separate internal primitive the
Clojure client reads through** (`.kb/clojure-frontend.md`, "HTTP client"):
`(rontolisp::%inflate-new kind)` (0 raw DEFLATE, 1 zlib, 2 gzip), `%inflate-update` (decodable
octets up to a limit, or a sticky message string), `%inflate-finish` (nil, 1 gzip header/trailer
cut, 2 data cut). Semantics are `java.util.zip`'s, the oracle: zlib's messages, `GZIPInputStream`'s
header/trailer messages, concatenated members, a zlib preset dictionary read as the end.
- **`GZIPInputStream` after a trailer is not one rule across JDKs**: GraalVM 25.0.4 / OpenJDK 21
  always try a next member; GraalVM 25.0.5.0.3 (and jdk master by default,
  `jdk.util.gzip.tryReadAheadAfterTrailer`) only when `in.available() > 0 || n > 26`, which makes the
  answer depend on buffer boundaries. The decoders stay with always-try: the only rule that is a
  function of the octets (keeps the chunk invariant below) and reports a cut reply instead of
  dropping it. `InflateCases.oracle` answers `available()` 1 so every JDK reads that way.
- **Two implementations, unit for unit**: `runtime/RontoInflate` (pure Java, not `Inflater`: native
  zlib is not in the playground's Web Image) for the interpreter (`Environment.defineInflate`) and the
  JVM (classes TRAVEL, `JvmLispCompiler.INFLATE_RUNTIME_CLASS_FILES`); `eval/inflate.lisp`, spliced
  by `InflateLibrary` into a wasm program naming a primitive, for wasm. The Lisp one is ~140x too
  slow interpreted and ~1 MB/s compiled on the JVM.
- **Streaming**: a unit (block header, symbol + extra bits, match, header, trailer) the input runs out
  inside is THROWN back (`%inflate-stop`) and redecoded when more arrives, **so the output never
  depends on chunk boundaries**. Huffman blocks follow zlib's `inflate_fast` (locals while >= 10
  input octets remain; 9-bit table + canonical bit walk). Every value stays a fixnum on 31-bit wasm
  (bit buffer <= 23 bits, CRC-32 in two 16-bit halves).
- **Pins**: `RontoInflateTest` (every `testsupport/InflateCases` case in chunks of 1, 3, 64 and all,
  and one octet at a time under a limit, read as `GZIPInputStream`/`InflaterInputStream` read it);
  `InflateLibraryTest` (Lisp decoder equal to `RontoInflate`; splice gating);
  `clojure-http-spec.yaml` on the four legs.
- **A host that decodes says so**: a JavaScript `fetch` decodes gzip/deflate/br and keeps
  `content-encoding` and `content-length`; the generated `defaultHost()` (`HostGlueEmitter`) and the
  playground (`brokerFetch`, `BrowserHttp`) drop both when every coding is `gzip`, `x-gzip`,
  `deflate` or `br`, so `:headers` describe the octets `:body` holds. A hand-written host has the
  same obligation. Pinned by `ClojureHttpClientHostFetchE2eTest` and the regenerated
  `examples/cloudflare-workers/*/src/worker.js` (`HostGlueEmitterTest`).

## The request body

**`:body` is a string (sent as UTF-8) or an `(unsigned-byte 8)` vector (sent as its octets) on every
transport.** Interpreter `Environment.fetchBody` (anything else signals naming both kinds); JVM hands
the vector's `byte[]` to `RontoFetch`; component `%http-write-body`; `--host-fetch` and `--native`
the request record's `octets` key, one character per octet (below 256, so it stays JSON text);
playground `BrowserHttp` as ISO-8859-1 tagged `hasBody` "2". Pins: `fetch-spec.yaml`
`an-octet-vector-body-is-sent-as-it-is` (four legs),
`ClojureHttpClientHostFetchE2eTest#anOctetBodyLeavesThroughTheHostsFetchAsItsOctets`, the runner's
`reads_the_request_record_the_module_writes`.

## The request on the wire (JDK backends)

**Both JDK backends build the request in ONE place, `RontoFetch.request`**, and put on the wire what
the runner does:
- **`http:` speaks HTTP/1.1**: the client's default HTTP/2 sent an `h2c` upgrade on every cleartext
  request (deprecated, RFC 9113 3.1). `https:` keeps HTTP/2 via ALPN (per-request `version`).
- **A bodiless GET / HEAD / DELETE states no `Content-Length`** (RFC 9110 8.6): `method(m, noBody())`
  writes `Content-Length: 0`, `GET()`/`HEAD()`/`DELETE()` none. Residual: bodiless OPTIONS still
  says 0; empty POST/PUT/PATCH say it on purpose. (A GET with `Content-Length: 0` made Clack's
  `wrap-request-body` read a nil `:raw-body`, `.kb/http-server.md`.)
- Body framing is the transport's: the component sends chunked, no `Content-Length` even when empty.
  Pin: `fetch-spec.yaml` `a-bodiless-request-offers-no-upgrade-and-states-no-length`.

## The default User-Agent

**The ONE header added on the caller's behalf is `User-Agent: rontolisp/<version> (<git-commit>)`.**
The comment is dropped without a git repository (never `(unknown)`) and when the commit is not a plain
hash (a parenthesis would end the comment; a quote would end the generated Lisp literal). Added only
when the caller's `:headers` names no user-agent (case-insensitive; any caller value wins, even "").

Declared once in `compiler/FetchResponseShape` (`USER_AGENT_HEADER`, `defaultUserAgent()`,
`isUserAgentHeader`) because transports default differently: the JDK writes `Java-http-client/<jdk>`,
the component writes NOTHING (fly.io's edge answers an agent-less request `402`). So JDK paths set it
explicitly and the component through generated `%http-user-agent-header` /
`%http-default-user-agent` (dropped in a serve-only component). **Sending NO user-agent is
deliberately not offered.** Exceptions, by who owns the field: the **browser playground** (forbidden
header) and **`--host-fetch` reactors** (the host's `fetch`). Pins:
`LispEvaluatorTest#fetchSendsADefaultUserAgent`,
`JvmLispCompilerTest#compileAndRunFetchSendsADefaultUserAgent`, the `/agent` leg of
`componentFetchOverHttp`, `FetchResponseShapeTest` / `HttpLibraryTest`.

## Uniformly WASI 0.3: one `http.lisp` for fetch AND serve

fetch and serve are ONE Lisp library (`src/main/resources/am/ik/rontolisp/eval/http.lisp` + embedded
`http.wit`: vendored `wasi:http@0.3.0`, a clocks/types shim, four transparent aliases), spliced by
`eval/HttpLibrary` with a REACHABILITY member filter from the roots (`rontolisp:fetch` /
`%serve-handle`). Everything rides the general `wit-import` canon-lower machinery (`.kb/wit.md`); no
WAT http adapter. `WasmFetchCompiler` only validates (arity, literal `:method`) and falls through.

- **fetch (outgoing)**: `%http-client:send` is an `async func`, so it async-lowers and
  `rontolisp::%subtask-future` turns the start token into a PENDING `TYPE_FUTURE`; the `send` binding
  awaits and unwraps `result<response, error-code>`, so awaiting SIGNALS the error arm.
  `rontolisp:fetch` validates the method at the call (`%fetch-method-variant`) and hands the rest to
  `%fetch-run`. `%fetch-send` answers `(send-future . transmission)`, the transmission's readable end
  kept open until body release ("The cross-backend corpus"). Run `wasmtime run -S http=y`. Non-fetch
  components do not import `wasi:http`.
- **serve (incoming)**: `handler.handle` as a CALLBACK async lift; the response is delivered MID-TASK
  via `canon task.return` before the body streams (the stream built-ins are RENDEZVOUS, so returning
  would complete the task before the host reads). Serve and serve+fetch are ONE component shape over
  ONE import block (`import-block-http-server.bin`); `WasmServeComponentBuilder.build` lowers
  http.lisp's wasi:http surface FROM the block (`lowerServeIoFromBlock`), user `wit-import`s via
  `appendUserImports`. Run `wasmtime serve`.
- **Bodies (shared, symmetric)**: `%http-body-value` serves both directions: runs `consume-body`
  eagerly and wraps it into a stream via `rontolisp::%stream-new`. A read that finds the end or is in
  flight goes through async-defun `%http-body-next`, so the END is found in Lisp. The RELEASE runs ONCE
  (end or early close): drops readable end and trailers, resolves the transmit future ok, plus the
  owner's finish -- for a fetched reply `%fetch-body-finish`, which at the END reads the trailers
  (error arm = transfer failure, signalled at that read) then drops the transmission future. An early
  close does not read the trailers.
  **Trap**: `%http-body-next`'s first parameter is `pending`, not `read` -- library splices trigger on
  symbols a program spells, variables included, and `read` spliced the prelude reader into every fetch
  component (`RontoLispCliTest.aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice`).
  `%serve-handle` stream-closes the request body after dispatch; a STREAM response drains via
  `rontolisp::%http-drain` (in http-server.lisp; both libraries prelude-free). An async start's
  wrapper must not pop its staging (args + retptr outlive the call until the lift).

## Browser playground

Truly async. The Web Image runtime runs in a Web Worker (`web/ronto-worker.js`); the substitution
(`src/web/java/.../eval/Target_HttpSupport.java`) calls `BrowserHttp.start`, which posts the request
and a growable `SharedArrayBuffer` to the main thread, where `brokerFetch` (`web/playground.html`) runs
the real `fetch()` (CORS applies) and writes `[i32 state, i32 len, utf8...]`; the `BrowserFuture`'s
`join()` blocks via `Atomics.wait`. Web Image has no JSPI and no threads, so blocking in JS is the
only way to await. Cross-origin isolation for `SharedArrayBuffer` comes from
`web/coi-serviceworker.min.js` (MIT, vendored). Without isolation, or on the main thread, `start`
answers `"sync"` and the synchronous XHR is used.

**The async path is opt-in: the worker's host sets `self.rontoFetchBroker = true`**
(`ronto-worker.js?fetch=broker`), declaring its main thread answers `{type:"ronto-fetch"}`.
Isolation alone is not consent: the service worker's scope covers every page under `/rontolisp/`
(docs, decks), and a deck without a broker blocked 10 s in `Atomics.wait` and took the request for a
result. The docs' Run cells (`doc/assets/docs.js`) use the worker without the query (XHR path). A
host that sets the flag carries its own broker copy (`brokerFetch`; `broker` in
`lispresen/src/player.lisp`): a rendered deck is one self-contained file.

Fetch tests: the corpus above; interpreter/JVM local `HttpServer` cases (awaited twice, two in flight
out of order); `WasmLispCompilerIntegrationTest.promiseOpsWorkInPreview1Mode`; component error-path
and `-S http` gate tests plus opt-in (`RONTOLISP_HTTP_E2E=1`) success test; ci-spec
`async-defun-await-futurep` / `await-passes-non-futures-through`.

## `rontolisp:http-handler` (incoming HTTP / serving)

Its value model is CLACK's: the handler (a quoted defun name) receives the Clack ENVIRONMENT plist and
returns `(status headers [body])`. Full contract (`compiler/ClackEnv`, `http-server.lisp`, the two
`:raw-body` modes): `.kb/http-server.md`. Query-string decoding: `.kb/url.md`.

**ONE VIRTUAL THREAD PER REQUEST is a correctness constraint on everything a handler touches**:
process-wide mutable state reached from a handler must be thread-safe
(`.kb/concurrent-served-requests.md`; locks in `.kb/mutexes.md`).

### Interpreter and JVM

`RontoHttpServer` (**`runtime` pkg**, TRAVELS with a compiled program, `.kb/jvm-export.md`): JDK
`com.sun.net.httpserver.HttpServer`, one virtual thread per request; `start(port, handler)` is the
non-blocking test seam (port 0 ephemeral), `stopAllForTesting()`. Registered in `LispEvaluator` (it
applies the handler via the evaluator). It also carries the stoppable seam behind
`rontolisp::%http-server-start/-join/-stop/-port` (`.kb/clack.md`); the JVM lowering
(`JvmHttpServerSeamCompiler`) reuses the directive's `_httpHandlerFn` slot, so there is ONE handler
slot and one Clack server per process.

**Trap: every entry point reachable from `LispEvaluator` must have a matching `@Substitute` in
`Target_RontoHttpServer` (`src/web/java`).** Web Image's points-to analysis reaches
`VirtualThread.runContinuation` (unsupported on `svm-wasm`) from ANY unsubstituted method touching the
real `HttpServer`/virtual-thread executor -- a BUILD failure seen only by the `Deploy playground to
GitHub Pages` job. Add the stub in the same commit; verify with `./mvnw -Pweb -DskipTests package`.

JVM: the generated class ITSELF implements `RontoHttpServer.Handler` (`handle` is an extra
`--optimize` shaker root). `JvmHttpHandlerCompiler` resolves the handler name like `#'name`, stores it
in `_httpHandlerFn`, and emits `RontoHttpServer.serve(port, new Prog())` (default 8080; `:raw-body`
is the compile-time `ClackEnv.usesBufferedBody`). The injected `handle(Request)`
(`JvmHttpHandlerRuntimeBuilder`) is glue over `runtime/RontoHttpClack`. The two runtime classes import
only `java.base` + `jdk.httpserver`, so `java -cp . App` serves without the rontolisp jar. Tests:
`HttpHandlerTest`, `HttpHandlerJvmTest`, `JvmLispCompilerTest.compileHttpHandlerImplementsHandlerInterface`,
`JvmHttpHandlerTravellingRuntimeTest`.

### WASM component (`--component`; serve and serve+fetch are ONE shape)

`HttpLibrary.process` (right after `WitImportInliner`, before `UserMacroExpander`; `--component` serve,
off for a `wit-export` world) replaces the directive with http.lisp's serve half, a `%serve-dispatch`
bridge, a mode-matched `%serve-request-body`, and a `wasm-export` of `%serve-handle` as `"handle"`.
The directive is detected NESTED in a defun body too (quoted data excluded, first wins, call site
lowers to nil; the clack shim's `run` is that shape; `HttpHandlerInliner.usesHttpHandler` walks the
same way). Bridge + export are appended AFTER the program so a package-qualified handler resolves.

The core `handle` export is `[i32 request] -> []`, lifted `(memory, utf8, async)`. **The function type
must be built over the block's NAMED aliases** (request/response/error-code): anonymous structural
types fail with "instance not valid to be used as export". Rendezvous order: task.return ->
stream.write -> drop-writable -> future.write trailers. Headers marshalled both ways. A serve
component is EH-mode (http.lisp uses `handler-case`).

**Top-level init runs on the FIRST handle call, once per instance**, and an instance serves many
requests (`wasmtime serve`/Spin reuse up to `--max-instance-reuse-count`, 128): hence a 1 MiB GC heap
pregrow instead of 16 MiB (`.kb/wasm-gc-heap-pregrow.md`, +30% rps). The `handle` wrapper
(`WasmExportCompiler.emitBody`) calls `_start` under a serve-only init flag (`serveInitGlobalIndex`,
the last global; non-serve output byte-identical); without it every global read null in a handler.
Pinned by `httpHandlerReadsATopLevelGlobalUnderWasmtimeServe`; serve+tcp by
`httpHandlerConnectsTcpUnderWasmtimeServe` (needs `-S cli=y -S inherit-network=y`,
`.kb/tcp-sockets.md`).

`adapter-http-server-p1.wat` is the preview1 bridge (instantiated BEFORE the core) over the 0.3
interfaces: `random`, time, `print` work in a handler; `environ_*` empty, `fd_read` EOF, `path_open`
errno 76. The canonical-ABI allocator (`mem-http-client.wat`, cell `CABI_HP_CELL_ADDR` = 0x10000) is
reset at the top of `handle` for hosts that reuse an instance (Spin exercises it).

On Preview 1 the directive is a CALL-time error stub (the clack shim's `run` carries it as dead
code), as are `stream-read`/`stream-close`/`streamp` without a stream type; an uncaught error is a
silent trap there, so pin messages through handler-case.

Hosts: wasmtime 46+; wasmCloud (wash 2.5.2, `dev.wasm_proposals: [gc, exception-handling,
component-model-async]`); Spin 4.1.0+ with a plain `spin.toml`; jco cannot run the 0.3 async ABI.
**Spin 4.0.2 cannot host it**: its wasmtime 44 has the `0.3.0-rc-2026-03-15` WASI snapshot, not the
released `0.3.0` ("instance export `fields` has the wrong type"). The gate is the embedded wasmtime
version, not the GC proposal.

Tests: the serve cases in `WasmLispCompilerIntegrationTest` (echo / big response /
random-clock-print / keyvalue / fetch-inside-serve proxy, via `compileServeComponent`).
