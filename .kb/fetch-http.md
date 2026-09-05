# Futures (`rontolisp:await` / `futurep`) and `rontolisp:fetch` (async HTTP)

`rontolisp`-package functions (not CL standard). `fetch` starts the request and immediately returns a
**future** (`.kb/async-await.md` has the future/stream machinery); `await` resolves it, `futurep` is
the type predicate. The promise-era `then`/`promisep` surface is DELETED: composition is
`async-defun`/`async-lambda` + `await`. Futures print `#<FUTURE>`, streams `#<STREAM>`, everywhere.

**Future representation**: interpreter = `LispFuture` (wraps `CompletableFuture<LispVal>`;
`LispEvaluator.awaitValue` joins and flattens); JVM = a bare `CompletableFuture`; WASM `--component`
asyncMode = the first-class `TYPE_FUTURE` struct; Preview 1 = the degenerate `TYPE_P1_FUTURE`
(settled at creation, `FUNC_P1_FUTURE_AWAIT` resolves it).

`await`/`futurep` work on every backend and WASM mode. **fetch needs a transport**:
`WasmFetchCompiler` is a compile error on plain Preview 1 (no host wasi:http) and on `--no-wasi`
WITHOUT `--host-fetch` (the message names the flag). Interpreter (`eval/HttpSupport.requestAsync` --
request-building failures fail the future; the per-request client is deliberately never closed) and
JVM (`JvmFetchRuntimeBuilder`) use the JDK `java.net.http.HttpClient`.

**Error timing** is JS-like: options validated at `fetch` time; request/transport failures surface at
`await` on EVERY backend (on WASM the send result's error arm becomes a `rontolisp:wit-error`
condition, catchable with `handler-case`; interpreter/JVM signal a plain error -- a known type
divergence). A fetch that cannot even start (malformed URL) returns `nil` instead of a future on WASM.

## `--host-fetch` (the `--no-wasi` reactor transport)

`HostFetchLibrary` (eval pkg) splices generated Lisp -- TWO `rontolisp:wasm-import`s riding the
ordinary synthetic-defun machinery, appended after the program so user import ordinals are unchanged:

```
env.fetch(headPtr, headLen) -> (ptr, len)   ; request head JSON -> response HEAD json
env.readResponseBody(ptr, cap) -> i32       ; :bytes, :async t; 0 = EOF, <0 = failed
```

plus envelope defuns whose JSON keys are DERIVED from `FetchResponseShape`'s records (a `request`
record -- url/method/headers/body, `body` an `option<string>` so an absent `:body` crosses as an
absent key; the error arm is `FetchResponseShape.HOST_ENVELOPE_ERROR_KEY` and SIGNALS at the fetch).
`fetch` is a plain defun over an async-defun runner, so it answers the settled `TYPE_P1_FUTURE`
(started == settled: the host call blocks the wasm stack, so `(await (fetch ...))` never suspends,
and a transport failure BEFORE the head signals at the CALL).

Gates: the splice requires the program to reference `rontolisp:fetch`; `hostFetch` requires `noWasi`
and rejects `component`; the CLI rejects `.class`/`--no-gc`. The BUILD prints the host obligation (a
synchronous `env.fetch` is always valid; a `WebAssembly.Suspending` one requires
`WebAssembly.promising` entry + serialised calls -- a re-entered export traps, `.kb/wasm-import.md`)
plus a `NoWasiLoadPathRefusals` line when the LOAD PATH reaches a fetch (a suspending host cannot
serve `_initialize`).

**The `:body` split** is opt-in `--host-boundary=streaming` against a default of `envelope`
(`compiler/HostBoundary`). Without it the import, the counter, the pull thunk and the negative-count
channel are gone, `%host-fetch-body` collapses to its in-band arm over the head's own `"body"` key,
and the module imports `env.fetch` alone. `:body` is the same first-class stream either way. What
the split buys: the reply body used to ride the envelope as one JSON string, so `:body` was an EAGER
STRING -- copied into linear memory twice, a BINARY reply could not cross at all (`ff fe 41` came
back as code point 0x1FE062 and two NULs), and a Worker could not forward a streamed upstream
response. Mechanics, all in `HostFetchLibrary`:

- The thunk is the reactor's own transport calls (`%http-reactor-buffer`/`-chunk`/`-body-stream`,
  `.kb/clack.md`), so ONE reused receive buffer serves both directions of every reactor boundary.
  **Consequence**: naming them splices `http-reactor.lisp` (and with it `http-server.lisp`). It CALLS
  the import rather than taking `#'name` (the suspending-import report follows calls), and its
  parameter is `token`, not `open` -- `NoWasiFilesystemStubs` rewrites a variable of that name into a
  call to `cl:open`.
- **The head's `"body"` key survives as the IN-BAND fallback.** Absent (the normal case) puts the
  stream over the import; an absent-body reply (a HEAD, a 204) is that stream finding EOF at its
  first pull.
- **One live reply body, no handle -- in the SERIALISED shape.** The host has ONE read cursor, moved
  by each `env.fetch`, so a module-side counter (`%host-fetch-open`) makes draining a SUPERSEDED body
  signal instead of answering the next reply's octets. Under `--reentrant` the handle EXISTS: the
  import becomes `env.readResponseBody(id, ptr, cap)`, the reply head carries its id in
  `FetchResponseShape.HOST_BODY_ID_KEY`, `defaultHost()` keeps one reader per reply, and the counter,
  the superseded error and `lisp.drop` are absent. The id is the REPLY's; the serialised shape is
  byte-unchanged.
- **A NEGATIVE count is the error channel** -- without one a transport that died mid-body would look
  like a short body. It signals at the DRAIN, so the future settles at the HEADERS and only a failure
  before them signals at the call.

The host half of both imports is GENERATED (`--emit-js-glue`, `.kb/wasm-import.md`):
`examples/cloudflare-workers/dog-fetcher/src/worker.js` is emitted from these two declarations and
checked in. Without the split the host half is generated too (`defaultHost()`), since `env.fetch`'s
two directions are both fixed by `FetchResponseShape`. That is the line the emitter draws, and it IS
the split: a body out of band is a reader the host owns and a cursor whose lifetime only it can see.
Pinned by `HostFetchLibraryTest`, `FetchResponseShapeTest`, `WasmImportCompilerTest` and
`WasmHostFetchBodyE2eTest` (node, a JS host sharing the module's memory: the portable drain, `ff fe
41` crossing exactly, the in-band fallback, a 256 KiB reply never drained leaving
`memory.buffer.byteLength` where it was, the mid-body failure, the superseded-body guard).

## The response plist

**`(:status <int> :headers <alist> :body <stream>)` on every backend.** `:body` is drained with
`(rontolisp:await (rontolisp:read-all ...))`. The shape is written once in
`compiler/FetchResponseShape`, so keys and order are derived in each backend's fetch runtime
(`Environment`'s fetch result, `JvmAsyncRuntimeBuilder`, the `%http-response-plist` helpers
`HttpLibrary` splices). The SERVER side no longer shares it (`.kb/http-server.md`).

**`:body` is a stream of OCTET chunks on every backend.** Every chunk a fetched reply's `:body`
answers -- and every chunk a served request's default `:raw-body` answers -- is an
`(unsigned-byte 8)` vector holding the wire's bytes; nothing on the transport decodes. That makes
`(list status headers (getf res :body))` byte-exact. Before, the stream was a CHARACTER stream and a
relayed body was re-encoded by the sink, so a JPEG's `ff d8 ff` came out `c3 bf d8` -- silent,
content-dependent corruption. The bivalent-stream alternative was rejected: it needs a second read
primitive on four stream runtimes and a carry inside each.

Per backend: `HttpSupport.BodyPump` writes one `LispIntVector` per publisher batch (interpreter);
`_fetch` takes the reply with `BodyHandlers.ofByteArray()`, `_await` queues ONE `long[]{8, ...}` from
`_iv_of_bytes`, `_drain_body` refuses a mixed stream, and `usesIntArray` is forced on by
`usesFetch || usesHttpHandler` (JVM); the `stream<u8>` READ lift answers a packed vector
(`_bytes_from_mem`, `.kb/wit.md`) so `%http-body-value` needs no change (`--component`);
`%http-reactor-body-stream` is `%stream-new` over `%http-reactor-octet-source` (`--no-wasi`).

`read-all` (prelude) joins the chunks (`%octets-join`, one blit) and decodes once with
`rontolisp::%octets-to-string` -- LENIENT UTF-8, the rule `http-server.lisp`'s request decoder
applies, one Lisp definition compiled on the compile paths and mirrored natively by `Environment`
(`LispPreludeLibraryTest` pins the two). The per-byte loop is only the FALLBACK: the definition first
offers the vector to the native `rontolisp::%octets-to-string-strict`, so valid UTF-8 is a platform
decode on interpreter/JVM and one `array.copy` on wasm (500 KB body: 102 -> 2 ms wasm, 19 -> 3 ms
JVM). **The raw copy is sound only because the validator is STRICT** -- `_str_char_at`'s lead ranges
are NOT that validator. Gates: ci-spec `read-all-decodes-an-octet-chunk-stream` (all four backends),
`HttpHandlerTest.directiveRelaysAFetchedBodyByteExactlyAndReadAllStillDecodesIt` + its
`HttpHandlerJvmTest` twin,
`WasmLispCompilerIntegrationTest.httpHandlerRelaysAFetchedBodyByteExactlyUnderWasmtimeServe`.

## The default User-Agent

**The ONE request header added on the caller's behalf is `User-Agent: rontolisp/<version>
(<git-commit>)`** (RFC 9110 comment after the product token). Dropped entirely when the build had no
git repository (never `(unknown)`); anything not a plain hash is dropped rather than escaped (a
parenthesis would end the comment, and the string is baked into generated Lisp source where a quote
would end the literal). Added only when the caller's `:headers` alist names no user-agent field --
case-insensitive, and any caller value wins, the empty string included.

Declared once in `compiler/FetchResponseShape` (`USER_AGENT_HEADER`, `defaultUserAgent()` over the
`userAgent(version, commit)` seam, `isUserAgentHeader`), because three transports default
differently: the JDK writes `Java-http-client/<jdk>` when no field is set and the component writes
NOTHING (fly.io's edge answers an agent-less request `402 Payment Required`). So the JDK paths set it
EXPLICITLY (`HttpSupport.requestAsync`; a scan pass plus a conditional `Builder.header` in the
emitted `_fetch`) and the component through generated `%http-user-agent-header` /
`%http-default-user-agent` defuns, dropped by the reachability walk in a serve-only component.
**Sending NO user-agent is deliberately not offered.** Two exceptions, because of WHO OWNS THE FIELD:
the **browser playground** (`User-Agent` is a forbidden header a page may not set) and
**`--host-fetch` reactors** (the host's own `fetch` supplies it). Pins:
`LispEvaluatorTest#fetchSendsADefaultUserAgent`,
`JvmLispCompilerTest#compileAndRunFetchSendsADefaultUserAgent`, the `/agent` leg of
`componentFetchOverHttp`, `FetchResponseShapeTest` / `HttpLibraryTest`.

## Uniformly WASI 0.3: one `http.lisp` for fetch AND serve

fetch and serve are ONE Lisp-source library (`src/main/resources/am/ik/rontolisp/eval/http.lisp` +
its embedded `http.wit`: vendored `wasi:http@0.3.0` types/handler/client, a clocks/types shim, and
four transparent aliases `body-stream`, `trailers-future`, `transmit-future`, `handle-result`),
spliced by `eval/HttpLibrary` with a REACHABILITY-based member filter from the active roots
(`rontolisp:fetch` / `%serve-handle`). Everything rides the general `wit-import` canon-lower
machinery (`.kb/wit.md`): no http blob variant for the non-serve path, no WAT http adapter.
`WasmFetchCompiler` is a validator that falls through -- under `--component` it runs the
compile-time arity / literal-`:method` check and control reaches the http.lisp defun.

- **fetch (outgoing)**: `%fetch-send` builds the request resource, writes the body via the
  `body-stream` alias built-ins, and calls `%http-client:send` -- an `async func` member, so it
  async-lowers (`canon lower ... async`): the start wrapper returns a `(packed . retptr)` token,
  which `rontolisp::%subtask-future` turns into a first-class PENDING `TYPE_FUTURE`. The public
  `send` binding is an async-defun that awaits and unwraps the `result<response, error-code>`
  envelope, so awaiting SIGNALS the error arm (`rontolisp:wit-error`); `rontolisp:fetch` composes it
  through the async-defun `%fetch-run` and keeps the nil-on-start-failure contract. Run:
  `wasmtime run -S http=y`. Non-fetch components do not import `wasi:http`.
- **serve (incoming)**: the handler implements
  `handler.handle: async func(request) -> result<response, error-code>` as a CALLBACK async lift
  (stub callback; blocking is the parked waitable-set.wait inside the wrappers); the response is
  delivered MID-TASK via `canon task.return` before the body streams -- the task's core return would
  otherwise complete the task before the host could read the contents stream (the built-ins are
  RENDEZVOUS, unbuffered). Serve and serve+fetch are ONE component shape over ONE import block
  (`import-block-http-server.bin`, from the 0.3 `uni-http-server` world);
  `WasmServeComponentBuilder.build` lowers http.lisp's own wasi:http surface FROM the block
  (`lowerServeIoFromBlock`), and an ADDITIONAL user `wit-import` rides `appendUserImports` alongside.
  Run: `wasmtime serve`.
- **Bodies (shared, symmetric)**: request and response are the same 0.3 shape
  (`contents: option<stream<u8>>` + a trailers future), so `%http-body-value` serves both directions:
  it runs `consume-body` (which MOVES its resource) eagerly and wraps the (stream, trailers,
  transmit-res) protocol into a first-class stream via `rontolisp::%stream-new` -- the read thunk
  issues one built-in read per call, and the close thunk (run ONCE, at EOF or an early stream-close)
  drops the readable end + unread trailers and resolves the transmit future ok (an unfinished body
  traps). `%serve-handle` stream-closes the request body after dispatch; a STREAM response body
  drains inside `%http-serve-request` via `rontolisp::%http-drain`, which lives in http-server.lisp
  (both libraries stay prelude-free). http.lisp accepts the per-call bump-heap growth of an async
  start (the start wrapper must not pop its staging -- args + retptr outlive the call until the lift).

## Browser playground

Truly async. The Web Image runtime runs inside a Web Worker (`web/ronto-worker.js`); the web-profile
substitution (`src/web/java/.../eval/Target_HttpSupport.java`) calls `BrowserHttp.start`, which posts
the request plus a growable `SharedArrayBuffer` to the main thread, where `brokerFetch`
(`web/playground.html`) runs the real browser `fetch()` concurrently (subject to CORS) and writes
`[i32 state, i32 len, utf8...]`. The pending `BrowserFuture`'s `join()` blocks via `Atomics.wait`;
`newIncompleteFuture()` propagates the settler through `thenApply`. Web Image has no JSPI and no
threads (verified against GraalVM 25), so blocking-in-JS is the only way to await there.
`SharedArrayBuffer` needs cross-origin isolation: GitHub Pages gets COOP/COEP from
`web/coi-serviceworker.min.js` (MIT, vendored). Without isolation -- or on the main thread -- `start`
returns `"sync"` and the substitution falls back to the synchronous XHR (settled future, no overlap).

Fetch tests: interpreter/JVM use a local `HttpServer` (awaited-twice, two-in-flight out-of-order
cases); Preview-1 await passthrough in `WasmLispCompilerIntegrationTest.promiseOpsWorkInPreview1Mode`;
deterministic component error-path + `-S http` gate tests plus an opt-in (`RONTOLISP_HTTP_E2E=1`)
success test; ci-spec `async-defun-await-futurep` / `await-passes-non-futures-through`.

## `rontolisp:http-handler` (incoming HTTP / serving)

Its value model is CLACK'S, not fetch's: the handler (a quoted defun name, like `wasm-export`)
receives the Clack ENVIRONMENT plist (`:request-method` keyword, decoded `:path-info`,
`:query-string`, the `:headers` equal table, `:raw-body`, ...) and returns the Clack RESPONSE list
`(status headers [body])`. Full contract -- `compiler/ClackEnv`, the shared `http-server.lisp` model,
the per-backend construction division, the two `:raw-body` modes -- is `.kb/http-server.md`; the
directive takes `:raw-body :stream|:buffered` after the optional port. Query-string DECODING policy:
`.kb/url.md`; `:query-string` stays raw.

**ONE VIRTUAL THREAD PER REQUEST is a correctness constraint on everything a handler touches**:
process-wide mutable state reached from a handler must be thread-safe
(`.kb/concurrent-served-requests.md`; locks in `.kb/mutexes.md`).

### Interpreter and JVM

`RontoHttpServer` (**`runtime` pkg** -- it TRAVELS with a compiled program, `.kb/jvm-export.md`;
`public` for the web substitution): a blocking JDK `com.sun.net.httpserver.HttpServer`, one virtual
thread per request. `serve(port, handler)` blocks forever; `start(port, handler)` is the non-blocking
test seam (port 0 = ephemeral), `stopAllForTesting()` shuts servers down. Registered in
`LispEvaluator` (not `Environment`) because serving applies the handler via the evaluator's `apply`.
It also carries the STOPPABLE per-server seam behind `rontolisp::%http-server-start/-join/-stop/-port`
(the clack-handler-rontolisp acceptor, `.kb/clack.md`); the JVM lowering
(`JvmHttpServerSeamCompiler`) reuses the directive's `_httpHandlerFn` slot and injected `handle`
runtime, so `usesHttpHandler` also fires on `%http-server-start` -- which is why there is ONE handler
slot and one Clack server per process.

**Trap: every entry point reachable from `LispEvaluator` must have a matching `@Substitute` in
`Target_RontoHttpServer` (`src/web/java`).** GraalVM Web Image's points-to analysis reaches
`java.lang.VirtualThread.runContinuation` -- which calls a `Thread.isInterrupted()` substitution
unavailable on `svm-wasm` -- from ANY un-substituted method still touching the real
`HttpServer`/`Executors.newVirtualThreadPerTaskExecutor`. BUILD failure, not runtime, and only the
`Deploy playground to GitHub Pages` job runs `-Pweb native-image --tool:svm-wasm`. Add the stub in
the SAME commit as any new entry point and verify with `./mvnw -Pweb -DskipTests package`.

The JVM reuses it: the generated class ITSELF implements `RontoHttpServer.Handler` (same mechanism
as the tls-connect trust-all `X509TrustManager`; the public no-arg constructor is shared, and
`handle` joins the trust methods as an extra `--optimize` shaker root). `JvmHttpHandlerCompiler`
resolves the quoted handler name against the Pass-1 function registry like `#'name`, stores the
funcref in `_httpHandlerFn`, and emits `RontoHttpServer.serve(port, new Prog())` (port default 8080;
a non-literal port compiles as `(int) Long`; the trailing `:raw-body` pair is validated and dropped
-- the mode is the compile-time constant `ClackEnv.usesBufferedBody`). The injected
`public handle(Request)` (`JvmHttpHandlerRuntimeBuilder`) is thin glue over
`runtime/RontoHttpClack.buildEnv`, `_invoke_1` + `_await`, the compiled `%http-normalize-response` +
`_drain_body`, and `RontoHttpClack.toResponse`. The compiled class IS standalone: the two runtime
classes import nothing but `java.base` + `jdk.httpserver` and travel at their canonical names, so
`java -cp . App` serves with no rontolisp jar. Tests: `HttpHandlerTest`, `HttpHandlerJvmTest`,
`JvmLispCompilerTest.compileHttpHandlerImplementsHandlerInterface`,
`JvmHttpHandlerTravellingRuntimeTest`.

### WASM component (`--component`; serve and serve+fetch are ONE shape)

`HttpLibrary.process` (spliced right after `WitImportInliner`, before `UserMacroExpander`, gated to
`--component` serve and off for a `wit-export` world) replaces the directive with the serve half of
http.lisp, a `(defun %serve-dispatch (r) (HANDLER r))` bridge, a mode-matched `%serve-request-body`,
and `(rontolisp:wasm-export '%serve-handle :as "handle" :params '(:int) :returns :void)`. The
directive is detected NESTED inside a defun body too (quoted data excluded, first name wins, the call
site lowers to nil) -- the clack shim's `run` is the driving shape, and
`HttpHandlerInliner.usesHttpHandler` walks the same way. Bridge + export are appended AFTER the
program so a package-qualified nested handler name resolves against its own spliced defpackage.

The core `handle` export is `[i32 request] -> []`, lifted `canon lift (memory, utf8, async)` against
`async func(request: own<request>) -> result<own<response>, error-code>`. **The function type must be
built over the block's NAMED aliases** (request/response/error-code): the component-model export rule
requires every non-structural type an exported function references to be NAMED (anonymous structural
types fail validation with "instance not valid to be used as export"). Response delivered mid-task
via `canon task.return`, then the body streams (rendezvous order: task.return -> stream.write ->
drop-writable -> future.write trailers). **Headers are marshalled both directions** (`fields-copy-all`
in, `fields.append` out). http.lisp uses `handler-case`, so a serve component is EH-mode.

**Top-level init runs on the FIRST handle call, once per instance** -- and an instance serves MANY
requests: `wasmtime serve` (and Spin) retire a WASIp3 instance after `--max-instance-reuse-count`
requests, 128 by default. What the instance lifetime makes per-request-visible is what `_start`
costs: hence serve mode pre-grows a 1 MiB GC heap instead of 16 MiB
(`.kb/wasm-gc-heap-pregrow.md`, +30% rps at the default reuse count). A serve component never lifts
`run`, so the `handle` wrapper (`WasmExportCompiler.emitBody`) calls `_start` under a serve-only
`(mut i32)` init flag (`serveInitGlobalIndex`, the last module global; non-serve output
byte-identical) before the request task begins -- without it NO top-level form ran and every
defvar/defparameter global read back null inside a handler (a "cast failure" trap on first
arithmetic). Pinned by `httpHandlerReadsATopLevelGlobalUnderWasmtimeServe`; the serve+tcp composition
by `httpHandlerConnectsTcpUnderWasmtimeServe` -- tcp under `wasmtime serve` additionally needs
`-S cli=y -S tcp=y -S inherit-network=y` (`.kb/tcp-sockets.md`).

`adapter-http-server-p1.wat` is the preview1 bridge (instantiated BEFORE the core) over the 0.3
service interfaces, so `random` / time built-ins / `print` work inside a handler; `environ_*` report
a zero environment, `fd_read` is immediate EOF, `path_open` returns errno 76. The canonical-ABI
allocator (`mem-http-client.wat`, bump pointer in the `CABI_HP_CELL_ADDR` = 0x10000 linear cell, base
0x10008) is reset at the top of the serve `handle` wrapper for hosts that reuse one instance.

On Preview-1 WASM the directive is a CALL-time error stub (same "requires --component" message -- the
clack shim's `run` carries the directive as dead code there), and so are
`stream-read`/`stream-close`/`streamp` when no stream type exists; an uncaught error is a silent trap
on Preview 1, so pin messages through handler-case.

Hosts: wasmtime 46+; wasmCloud (wash 2.5.2, `dev.wasm_proposals: [gc, exception-handling,
component-model-async]`); Spin canary (4.1.0-pre0, wasmtime 47) with a plain `spin.toml`; jco cannot
run the 0.3 async ABI. Spin EXERCISES the allocator reset. **Why released Spin 4.0.2 cannot host it,
and the re-evaluation trigger**: it embeds wasmtime 44, whose p3 WIT is the `0.3.0-rc-2026-03-15`
SNAPSHOT of every WASI package, not the released `0.3.0` we emit -- the imports fail to link
("instance export `fields` has the wrong type") even with GC forced on. The gate is the wasmtime
version a host embeds, NOT the GC proposal: a host on wasmtime 46+ needs no flags.

Tests: the serve cases in `WasmLispCompilerIntegrationTest` (echo / big response /
random-clock-print / keyvalue / fetch-inside-serve proxy, through the `compileServeComponent`
CLI-path helper).
