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
machinery lives in a parallel blob set (`import-block-http.bin`/`mem-http.wasm`/
`adapter-http.wasm`, sources `uni-http.wit`/`core-http.wat`/`mem-http.wat`/
`adapter-http.wat`, `deps/*-0.2` + `deps/http`); `WasmComponentBuilder.build(core,
usesHttp)` -> `buildHttp`, emitted only when the program uses fetch. The rontolisp
core imports a version-agnostic two-function seam (module "http": `fetch-start` (8 x i32,
last = handle out-pointer) and `fetch-await` (6 x i32), function indices
`FUNC_FETCH_START`=8 / `FUNC_FETCH_AWAIT`=9, trap stubs when unused so `FUNC_START` stays
10 in every mode); only `adapter-http.wat` + `import-block-http.bin` + `buildHttp` bind to
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
`(:method :path :headers :body)` and returns a response plist
`(:status :headers :body)`; missing keys default to `:status 200` / empty body.

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
- **JVM / WASM component (IN PROGRESS)** -- both are a clear compile error for
  now (`JvmExprCompiler` / `WasmExprCompiler` throw "interpreter backend").
  The WASM design is PROVEN end-to-end (a hand-wired component exporting
  `wasi:http/incoming-handler@0.2.0` runs under `wasmtime serve`) and fully
  specified -- adapter WAT mirroring the fetch adapter's request/response
  marshalling but calling a `wasm-export`-emitted core `%http-dispatch`
  (`"<status>\n<body>"` encoding), plus a `WasmComponentBuilder.buildServe`
  wiring derived from a `wasm-tools` dump. All derived wasi:http@0.2 core ABI
  signatures (notably `[static]response-outparam.set` ->
  `(param i32 i32 i32 i32 i64 i32 i32 i32 i32)`) are recorded in
  `../.todo/51-wasi-http-incoming-handler-spin.md`.
