# Promises (`rontolisp:await` / `then` / `promisep`) and `rontolisp:fetch` (async HTTP)

`rontolisp`-package functions (not CL standard). `fetch` starts the request and
immediately returns a **promise**; the promise operations are generic and fetch is just
their only built-in producer today: `await` resolves (blocking; a non-promise passes
through unchanged, JS-style), `then` derives a chained promise (callback applied lazily
at first await, memoized, promise-returning callbacks flattened), `promisep` is the type
predicate. Promises print as `#<PROMISE>` in all backends. Behavior and limitations live
in the doc site (`doc/*/reference/functions/rontolisp-{fetch,await,then,promisep}.md`).

**Promise representation** (a first-class, distinguishable type everywhere):

- interpreter -- `LispPromise` (core AST package): root shape wraps a
  `CompletableFuture<LispVal>`; chain shape wraps (base, fn) + a memo slot. `await`/`then`
  are registered in `LispEvaluator` / `Environment`; the resolver
  (`LispEvaluator.awaitValue`) lives in the evaluator because applying a chain callback
  needs `apply`.
- JVM -- the promise IS a `java.util.concurrent.CompletableFuture` (nothing else in the
  runtime value representation is one, so `promisep`/printing are single instanceofs and
  consp/car/cdr are untouched). `_fetch` returns `sendAsync`'s future directly; a `then`
  chain is a *completed* future holding the payload `{MARKER, base, fn}` (MARKER =
  interned `"%promise\n"`, unreachable from the reader); `_await`
  (JvmFetchRuntimeBuilder) resolves recursively, applies callbacks via the `_invoke_1`
  dispatcher (arity 1 is force-registered when the fetch runtime is emitted), and
  memoizes with `obtrudeValue({MARKER, value, MARKER})`. The HttpResponse->plist branch
  is ordered after the chain branch so then-only programs never touch `java.net.http`.
- WASM -- a `TYPE_PROMISE` struct `{mut i32 kind, mut eqref base, mut eqref fn}`:
  kind 0 = fetch root (base = i31-boxed wasi:http future handle), 1 = then chain,
  2 = settled (base = memoized result). `_promise_await` (`FUNC_PROMISE_AWAIT`,
  WasmPromiseRuntimeBuilder; a real function because it recurses) rewrites the struct to
  kind 2 in place after resolving, so the wasi:http response (handed out once) and each
  chain callback are consumed exactly once; the former `GLOBAL_PROMISE_CACHE` alist and
  the adapter's never-drop-futures workaround are gone -- the adapter drops settled
  futures again since handles are no longer cache keys.

`await`/`then`/`promisep` compile/run on every backend and WASM mode (Preview 1
included); **only `fetch` is component-only** (WasmFetchCompiler throws outside
`--component`; the JVM emits the fetch/await runtime when fetch or await is used).

**Error timing** is JS-like: options are validated at `fetch` time; request/transport
failures surface at `await` (interpreter/JVM signal; WASM returns `nil` -- and a fetch
that cannot start returns `nil` instead of a promise). A failure skips chained callbacks
on interpreter/JVM; on WASM the callback receives the `nil` (no failure representation).
There is deliberately no onRejected/catch parameter yet (see
`.todo/45-promise-error-callback.md`).

Interpreter (`eval/HttpSupport.requestAsync`, via `HttpClient.sendAsync` -- request
building failures fail the future; the per-request client is deliberately never closed)
and JVM (`JvmFetchRuntimeBuilder`) use the JDK `java.net.http.HttpClient`.

**Hybrid**: a fetch component keeps base I/O on WASI 0.3 but adds the WASI 0.2 http
machinery (`wasi:http@0.2` + `wasi:io@0.2`), because async `wasi:http@0.3` does not exist
upstream yet (see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md` for the upgrade path). So a
fetch component needs `-S http=y` in addition to the async flags; non-fetch components
don't import `wasi:http`. To avoid forcing `-S http=y` on every component, the http
machinery lives in a parallel blob set (`import-block-http-client.bin`/`mem-http-client.wasm`/
`adapter-http-client.wasm`, sources `uni-http-client.wit`/`core-http-client.wat`/`mem-http-client.wat`/
`adapter-http-client.wat`, `deps/*-0.2` + `deps/http`); `WasmComponentBuilder.build(core,
usesHttp)` -> `buildHttp`, emitted only when the program uses fetch. The rontolisp
core imports a version-agnostic two-function seam (module "http": `fetch-start` (8 x i32,
last = handle out-pointer) and `fetch-await` (6 x i32), function indices
`FUNC_FETCH_START`=8 / `FUNC_FETCH_AWAIT`=9, trap stubs when unused so `FUNC_START` stays
10 in every mode); only `adapter-http-client.wat` + `import-block-http-client.bin` + `buildHttp` bind to
a WASI http version (so the future 0.3 upgrade is isolated). The adapter's `fetch-start`
parses the URL, builds/sends the outgoing-request (streaming the request body) and hands
back the `future-incoming-response` handle without waiting; `fetch-await` does
`subscribe`/`pollable.block`/`get`, serializes status/headers/body and drops the settled
future -- so multiple requests genuinely overlap between start and await. The core
rebuilds the result plist inside `_promise_await` via `WasmFetchRuntimeBuilder` helpers;
`:method` is resolved statically by `WasmFetchCompiler.methodDiscriminant` (unsupported
literal = compile error; runtime-computed = GET). The 0x60000/0x70000 response buffers
are shared scratch, safe because the core copies them into GC values before the next
`fetch-await`. Regenerating/re-wiring follows `src/wasm-component/README.md`.

**URL/body staging (fixed 2026-07-11, with todo 92 Tier 3)**: the fetch call site
stages the URL and request-body bytes into the linear heap via `_str_to_mem` before
calling `fetch-start` (fixed cells `FETCH_URL_PTR/LEN_ADDR` + `FETCH_REQ_BODY_PTR/LEN_ADDR`
at 0x4001C-0x40028; HEAP_PTR is advanced past the copies so `_fetch_ser_headers`' own
HEAP_PTR scratch cannot clobber them, then popped after the call). It previously read the
string struct's field 0 as a linear pointer — an IDENTITY id since the wasm-GC string
redesign ([[27]]), valid only by accident for the FIRST runtime-built string (id counter
and heap scratch both start at heapBase), so a program building two strings before
fetching silently requested the wrong URL, and a `wasm-export` argument (`_str_from_mem`,
always fresh) never worked. Header strings were already staged correctly
(`emitWriteField`). Regression pinned by `componentFetchWithRuntimeBuiltUrls`
(opt-in `RONTOLISP_HTTP_E2E=1`).

**Browser playground**: truly async. The Web Image runtime runs inside a Web Worker
(`web/ronto-worker.js`); the web-profile substitution
(`src/web/java/.../eval/Target_HttpSupport.java`) calls `BrowserHttp.start`, which posts
the request plus a growable `SharedArrayBuffer` to the main thread. The main-thread
broker (`brokerFetch` in `web/playground.html`) runs the real browser `fetch()`
concurrently (overlap works; subject to CORS) and writes
`[i32 state, i32 len, utf8...]` into the buffer. The pending future is a `BrowserFuture`
whose `join()` runs a settler that blocks via `Atomics.wait`
(`BrowserHttp.awaitResponse`) and completes the root; `newIncompleteFuture()` propagates
the settler through `thenApply`, which is how Environment's derived future stays
joinable. Web Image has no JSPI and no threads (verified against GraalVM 25), so
blocking-in-JS is the only way to await there. `SharedArrayBuffer` needs cross-origin
isolation: GitHub Pages gets COOP/COEP from `web/coi-serviceworker.min.js` (MIT,
vendored; one automatic reload on first visit). Without isolation -- or on the main
thread, e.g. `compile-run.html` -- `start` returns `"sync"` and the substitution falls
back to the synchronous XHR (`BrowserHttp.request`, settled future, no overlap).

Tests: interpreter/JVM use a local `HttpServer` (awaited-twice, two-in-flight
out-of-order, then-chain cases); Preview-1 promise ops run under wasmtime in
`WasmLispCompilerIntegrationTest.promiseOpsWorkInPreview1Mode`; deterministic
component error-path + `-S http` gate tests plus an opt-in (`RONTOLISP_HTTP_E2E=1`)
success test exercise overlap, out-of-order awaits and a then chain; ci-spec has a
network-free `promise-generic-await-then-promisep` case covering all four backends.

## `rontolisp:http-handler` (incoming HTTP / serving)

The incoming counterpart of `fetch`, sharing the HTTP value model: the handler
(a quoted defun name, like `wasm-export`) takes a request plist
`(:method :path :query :headers :body)` and returns a response plist
`(:status :headers :body)`; missing keys default to `:status 200` / empty body.
`:path` is the path only and `:query` the raw query string without the `?`
(nil when the request has none; `""` for a bare trailing `?`): the split at
the first `?` happens once in `HttpHandlerSupport.Request.of` (interpreter and
JVM inherit it) and in the synthesized `%http-request` Lisp helper on the WASM
component path. Decoding policy lives in the URL library (`.kb/url.md`), not
here.

- **Interpreter (implemented)** -- `HttpHandlerSupport` (eval pkg, `public` for
  the future web substitution): a blocking JDK `com.sun.net.httpserver.HttpServer`,
  ONE VIRTUAL THREAD PER REQUEST (`Executors.newVirtualThreadPerTaskExecutor`).
  `serve(port, handler)` blocks forever (Ctrl-C to stop); `start(port, handler)`
  is the non-blocking test seam (port 0 = ephemeral) and `stopAllForTesting()`
  shuts servers down. Registered in `LispEvaluator` (not `Environment`) because
  serving a request applies the handler via the evaluator's `apply`;
  `invokeHttpHandler` builds the request plist and reads the response plist.
  Tests: `HttpHandlerTest` (Java seam round trip + directive round trip via a
  background thread + validation).
- **JVM (implemented)** -- reuses the interpreter's `HttpHandlerSupport` server:
  the generated class ITSELF implements `HttpHandlerSupport.Handler` (the same
  mechanism as the tls-connect trust-all `X509TrustManager`; the public no-arg
  constructor is shared between the two and emitted when either is used, and
  `handle` joins the trust methods as an extra `--optimize` shaker root because
  the server invokes it through the interface). The directive site
  (`JvmHttpHandlerCompiler`) resolves the quoted handler name against the Pass-1
  function registry like `#'name`, stores the funcref in the `_httpHandlerFn`
  static field, and emits `HttpHandlerSupport.serve(port, new Prog())` (port
  default 8080; a non-literal port expression compiles as `(int) Long`). The
  injected `public handle(Request)` method (`JvmHttpHandlerRuntimeBuilder`)
  builds the request plist `(:method m :path p :query q :headers <alist> :body b)`
  (q is null = nil when `Request.query()` is null) as cons
  cells in the shared runtime value rep (quote-wrapped strings, like
  `JvmFetchRuntimeBuilder`), applies the handler via the `_invoke_1` dispatcher
  (arity 1 is force-registered like the fetch runtime does), reads
  `:status`/`:body` back with a plist-get loop and returns
  `new Response(status, Collections.emptyList(), body)` (`List.of()` would need
  an interface-static invokestatic, illegal in class version 50). CONSEQUENCE:
  the compiled class is NOT standalone -- it needs the rontolisp jar on the
  runtime classpath (`java -cp rontolisp.jar:. App`), unlike every other JVM
  program. Tests: `HttpHandlerJvmTest` (eval pkg for the shutdown seam;
  compile + curl round trips incl. `--optimize`) and
  `JvmLispCompilerTest.compileHttpHandlerImplementsHandlerInterface`.
- **WASM component (implemented, `--component`)** -- a `HttpHandlerInliner` cli
  pre-pass rewrites the directive into a `%http-dispatch` wasm-export wrapper
  (`"<status>\n<body>"` encoding; a `%http-request` helper splits the adapter's
  path-with-query at the first `?` into `:path`/`:query` before building the
  request plist), `WasmLispCompiler` serve mode un-gates
  wasm-export in component mode, and `WasmServeComponentBuilder.buildServe`
  wires mem + `adapter-http-server-p1.wasm` + core + `adapter-http-server.wasm` into a
  `wasi:http/incoming-handler@0.2.0` component for `wasmtime serve -W gc=y`.
  `adapter-http-server-p1.wat` is the preview1 bridge: instantiated BEFORE the core
  (the serve adapter imports the core's `%http-dispatch`, so unlike the
  `wasmtime run` adapter it cannot also provide the core's
  `wasi_snapshot_preview1` imports), it implements `random_get` over
  `wasi:random/random@0.2.0`, `clock_time_get` over `wasi:clocks/{wall,
  monotonic-}clock@0.2.0` and `fd_write` (fd 1/2) over `wasi:cli/{stdout,
  stderr}@0.2.0` streams in 4096-byte `blocking-write-and-flush` chunks (the
  stream handles are cached in module globals), so `random` / time built-ins /
  `print` work inside a served handler; `environ_*` report a zero environment
  (`getenv` -> nil), `fd_read` is immediate EOF, `path_open` returns errno 76
  (the core traps on it -- file streams stay unavailable, the proxy world has
  no filesystem). Test:
  `WasmLispCompilerIntegrationTest.httpHandlerRandomClockAndPrintUnderWasmtimeServe`.
  The serve component is plain WASI 0.2: unlike the `wasmtime run` path for
  regular components, none of the `component-model-async` flags are needed, so
  any `wasi:http` 0.2 host with the wasm-GC proposal enabled can serve it.
  Verified 2026-07-04: jco (`npx @bytecodealliance/jco serve app.wasm`, jco
  1.24.6 / Node 22 -- V8 enables wasm-GC by default; 50 sequential requests +
  POST body OK) and wasmCloud (wash 2.5.1 `wash dev` with
  `dev.wasm_proposals: [gc]` in `.wash/config.yaml`; `wash host` takes
  `--wasm-proposal gc` / `WASH_WASM_PROPOSALS=gc`; without the proposal it
  fails with "rec group usage requires `gc` proposal to be enabled").
  All derived wasi:http@0.2 core ABI signatures (notably
  `[static]response-outparam.set` ->
  `(param i32 i32 i32 i32 i64 i32 i32 i32 i32)`) are recorded in
  `../.todo/51-wasi-http-incoming-handler-spin.md`. Spin cannot run it (no
  wasm-GC in Spin's wasmtime and no flag to enable it -- exactly the gap
  wasmCloud's proposal switch fills); Preview-1 WASM output is a compile error
  ("requires --component").
- **Serve adapter hardening (2026-07-04)** -- three fixes in `adapter-http-server.wat`:
  (1) response bodies are written in 4096-byte chunks (`blocking-write-and-flush`
  rejects larger buffers, so any response > 4 KiB used to 500 on every host);
  (2) `response-outparam.set` runs BEFORE the body writes -- the host only
  consumes the body stream after the outparam is set, so set-after-write
  deadlocks past one 4096-byte host buffer; (3) both bump allocators (mem
  module `cabi_realloc` via the newly exported `"hp"` global, core
  `__ronto_alloc` heap ptr @84) are reset per request to their post-init
  snapshots -- `wasmtime serve` instantiates per request, but jco/wasmCloud
  reuse one instance, where memory otherwise grew by ~response size per
  request. The core-heap restore is guarded by the runtime intern count (@100):
  if `_intern` appended records since the snapshot (their (off,len) reference
  token bytes in place), the snapshot ratchets up instead. The init flag and
  snapshots are ADAPTER-LOCAL GLOBALS, never linear memory -- a large response
  sweeps the core bump heap across the 0x50000 scratch page and would corrupt
  them (the response body sweeping that page is otherwise harmless: all
  per-request scratch there is written before use).
- **fetch inside a served handler (serve+fetch variant, 2026-07-04)** -- a
  program using BOTH `rontolisp:http-handler` and `rontolisp:fetch` compiles to
  a parallel serve blob set (proxy/aggregator shapes): the preview1
  bridge is swapped for `adapter-http-server-client-p1.wat` (= adapter-http-server-p1 + the
  fetch-start/fetch-await bodies of adapter-http-client.wat + the errno-returning tcp
  stubs), instantiated BEFORE the core so it can satisfy the core's
  `http`/`sock` imports (the serve adapter, which comes after the core, cannot).
  `WasmServeComponentBuilder.buildHttp` wires it over
  `import-block-http-server-client.bin` (world `uni-http-server-client` = the serve surface +
  `wasi:io/poll` + `wasi:http/outgoing-handler`; io/poll is dependency-hoisted
  to instance 0, shifting every serve instance index by one -- constants are
  independent of `build()` and re-derived from `wasm-tools dump`).
  `WasmLispCompiler` passes `emitHttpImport` through
  `WasmComponentBuilder.buildServe(core, usesHttp)`. Still the plain proxy
  world: run with `wasmtime serve -W gc=y -S http=y` (no async flags). Memory
  safety: the fetch response-body scratch (0x70000) overlaps the serve
  adapter's request-body scratch, which is safe because `%http-dispatch`
  marshals the request into GC strings before the handler (and any fetch)
  runs. serve + `rontolisp:tcp-*` is a compile error ("cannot be used in a
  rontolisp:http-handler --component program yet") -- no serve blob variant
  with wasi:sockets. Interpreter/JVM needed no changes (both sides are
  `java.net.http.HttpClient` / `HttpServer`). Test:
  `WasmLispCompilerIntegrationTest.httpHandlerFetchInsideServeUnderWasmtimeServe`
  (the fetch backend is itself a plain rontolisp serve component, so the test
  stays offline).
- **v1 limitations** -- on the WASM component, request/response headers are
  dropped (the handler sees `:headers nil`, response `:headers` is ignored).
  The interpreter and the JVM backend (since 2026-07-04,
  `JvmHttpHandlerRuntimeBuilder`: request `List<Header>` -> `:headers` alist,
  response alist -> `Response` headers, malformed entries skipped) pass headers
  through.
