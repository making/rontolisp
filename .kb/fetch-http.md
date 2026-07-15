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
included); **only `fetch` is component-only** (`WasmFetchCompiler` is a compile error
outside `--component`; the JVM emits the fetch/await runtime when fetch or await is used).

**Error timing** is JS-like: options are validated at `fetch` time; request/transport
failures surface at `await` (interpreter/JVM signal; WASM returns `nil` -- and a fetch
that cannot start returns `nil` instead of a promise). A failure skips chained callbacks
on interpreter/JVM; on WASM the callback receives the `nil` (no failure representation).
There is deliberately no onRejected/catch parameter yet (see
`.todo/45-promise-error-callback.md`).

Interpreter (`eval/HttpSupport.requestAsync`, via `HttpClient.sendAsync` -- request
building failures fail the future; the per-request client is deliberately never closed)
and JVM (`JvmFetchRuntimeBuilder`) use the JDK `java.net.http.HttpClient`.

**Hybrid, and split by mode.** A fetch component keeps base I/O on WASI 0.3 but adds the
WASI 0.2 http machinery (`wasi:http@0.2` + `wasi:io@0.2`), because async `wasi:http@0.3`
does not exist upstream yet. Either way a fetch component needs `-S http=y` in addition to
the async flags; non-fetch components don't import `wasi:http`. HOW that http surface
arrives depends on whether the program also SERVES:

- **Non-serve fetch (plain `wasmtime run`) -- `fetch.lisp`, no adapter blob.**
  `rontolisp:fetch` is a spliced Lisp-source library
  (`src/main/resources/am/ik/rontolisp/eval/fetch.lisp`, spliced by `eval/FetchLibrary` when
  the program references `rontolisp:fetch` and does not define it) over a
  `rontolisp:wit-import`ed `wasi:http` / `wasi:io` surface (`fetch.wit`, a classpath resource
  whose WIT text travels inline in the lowered `%component-import` form). It rides the same
  `canon lower` machinery as any user `wit-import` (`.kb/wit.md`, "Component imports"): the
  **base** blob set is selected and `wasi:http@0.2` + `wasi:io@0.2` come in as canon-lowered
  user imports, so there is NO `http-client` blob variant and NO WAT adapter here.
  `WasmFetchCompiler` is now a **validator that falls through**: on `component` (serve OR
  non-serve) it runs the compile-time arity / literal-`:method` check and returns WITHOUT
  emitting, so control reaches the `fetch.lisp` defun. There is no `emitHttpImport` /
  `http` core seam any more (`FUNC_FETCH_START`/`FUNC_FETCH_AWAIT` are permanent trap stubs):
  fetch is fetch.lisp over canon-lowered `wasi:http` on every `--component` path, serve
  included. The promise API is preserved WITHOUT a synchronous fetch: the defun is
  `(then (%http-send url options) #'%http-read-response)` -- `%http-send` calls `wasi:http`'s
  `outgoing-handler.handle` (non-blocking, request in flight) and returns the
  `future-incoming-response` handle, and `%http-read-response` blocks (`pollable.block`) only
  when the promise is awaited. Two WASM-subset gotchas `fetch.lisp` hit: **no `search`** and
  **no `position :start`**, so it splits the URL with `subseq` off the first colon.
- **Serve + fetch (`rontolisp:http-handler` with `rontolisp:fetch` inside) -- collapsed into
  serve.lisp + fetch.lisp (todo 135 step 6).** Both halves are Lisp over wit-imported
  `wasi:http` now: `serve.lisp` handles the incoming side (`wasi:http/incoming-handler`),
  `fetch.lisp` the outgoing side (`wasi:http/outgoing-handler`). The CLI splices BOTH
  (`FetchLibrary.process` then `ServeLibrary.process`), and their overlapping `wasi:http/types`
  / `wasi:io/streams` bindings are merged into one component-level import
  (`WasmComponentImportCompiler.mergeByIface`); the two `%`-packages keep their own Lisp
  wrappers, whose duplicate core imports of the same host function are deduplicated onto one
  import by `WasmLispCompiler`'s import-slot pass (the component model forbids a core module
  importing one (module,field) twice). `WasmServeComponentBuilder.build` selects the **wide**
  block (`import-block-http-server-client.bin`: adds `wasi:io/poll` + `wasi:http/outgoing-handler`
  + the outgoing-request / future / incoming-response members) over the narrow plain-serve
  block, and lowers the whole surface from it (`lowerServeIoFromBlock`, deduping by field). The
  preview1 bridge is the SAME `adapter-http-server-p1.wasm` as plain serve -- once fetch is
  fetch.lisp the core imports no `http` function, so the extended bridge and the WAT serve
  adapter are both **deleted**. Run with `wasmtime serve -W gc=y -W exceptions=y -S http=y`
  (EH-mode, outbound HTTP). Regenerating/re-wiring follows `src/wasm-component/README.md`.

**URL/body staging (`fetch.lisp` marshals through the canonical ABI, all paths).** The old
serve+fetch WAT staging note (fixed 2026-07-11) is retired with the WAT adapter; for the
record it worked like this: the fetch call site
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
- **WASM component, plain serve (implemented, `--component`)** -- the HTTP glue is
  now **`serve.lisp`** (a Lisp-source library, `eval/ServeLibrary`, the mirror of
  `fetch.lisp`): fetch IMPORTS `wasi:http/outgoing-handler`, serve EXPORTS
  `wasi:http/incoming-handler`, and both drive `wasi:http/types` from Lisp. There is
  **no hand-written serve adapter** on this path (the WAT serve adapter is deleted; the
  serve+fetch variant below is the SAME builder over a wider block, also adapter-free).
  `ServeLibrary.process` (spliced in the CLI right after `WitImportInliner`, before
  `UserMacroExpander`, gated to `--component` serve -- plain OR serve+fetch -- and off for
  a `wit-export` world) replaces the `rontolisp:http-handler`
  directive with: `serve.lisp` (its own `wasi:io/error` + `wasi:io/streams` +
  `wasi:http/types` `wit-import`s lowered by `ServeLibrary` itself, like `FetchLibrary`),
  a `(defun %serve-dispatch (r) (HANDLER r))` bridge to the program's handler, and a
  `(rontolisp:wasm-export '%serve-handle :as "handle" :params '(:int :int) :returns :void)`.
  The core `handle` wrapper == a plain `wasm-export` of two handle-carrying `:int` params
  (a resource handle boxes/unboxes exactly as an `:int`), so there is no new export-side
  marshalling. `WasmServeComponentBuilder.build` lowers serve.lisp's `wasi:io` /
  `wasi:http/types` calls **FROM the import block** (`aliasInstanceFunc` + `canon lower`,
  the same canonical ABI `fetch.lisp` uses -- NOT `appendUserImports`, which would
  re-import them), then lifts the core's `handle` export against the
  `own<incoming-request>` / `own<response-outparam>` function type into
  `wasi:http/incoming-handler@0.2.0`. **serve on WASM grows headers here**: `serve.lisp`
  reads request headers (`incoming-request.headers` + `fields.entries`) and writes
  response headers (`fields.append`) -- both were silently dropped by the old WAT adapter.
  serve.lisp uses `handler-case` (EOF on `blocking-read`), so the component is EH-mode:
  run with `wasmtime serve -W gc=y -W exceptions=y`. An ADDITIONAL `rontolisp:wit-import`
  (e.g. `wasi:keyvalue`, so a handler's state lives in a real store) rides
  `appendUserImports` alongside the fixed surface, exactly like the other variants.
  `WasmServeComponentBuilder.build`'s wiring carries no hardcoded per-function canonical
  option table -- the block's expanded import set (`core-http-server.wat`, regen'd via
  `regen.sh`) plus `needsMemory` decide it. Tests: the serve cases in
  `WasmLispCompilerIntegrationTest` (echo / big response / random-clock-print / keyvalue,
  all through the `compileServeComponent` CLI-path helper).
- **WASM component, serve+fetch (implemented, `--component` + `rontolisp:fetch`)** -- the
  SAME `WasmServeComponentBuilder.build` as plain serve, selecting the wide block: serve.lisp
  and fetch.lisp are both spliced (see the serve+fetch bullet above), `rontolisp:fetch`
  resolves to fetch.lisp's defun (not `WasmFetchCompiler`), and the component exports
  `wasi:http/incoming-handler@0.2.0` while importing `wasi:http/outgoing-handler` for the
  outbound leg. Run with `wasmtime serve -W gc=y -W exceptions=y -S http=y`. There is no
  serve adapter and no extended bridge -- on BOTH serve paths
  `adapter-http-server-p1.wat` is the preview1 bridge: instantiated BEFORE the core, it
  implements `random_get` over
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
- **Serve adapter hardening + fetch-inside-serve (2026-07-04, SUPERSEDED by todo 135)** --
  the hand-written serve adapter (`adapter-http-server.wat`) and the extended serve+fetch
  bridge (`adapter-http-server-client-p1.wat`) are both DELETED. Their behaviours are now
  serve.lisp's: response bodies chunked in 4096-byte `blocking-write-and-flush` calls (larger
  buffers are rejected), `response-outparam.set` BEFORE the body writes (set-after-write
  deadlocks past one host buffer), child streams dropped before finishing the parent
  outgoing-body. The old adapter's per-request bump-allocator reset (for jco/wasmCloud, which
  reuse one instance where wasmtime serve re-instantiates) is a FOLLOW-UP for stateful
  handlers -- serve.lisp is stateless per request today (HEAP_PTR is re-seeded by an active
  data segment at instantiation, so a stateless handler needs no reset trigger). serve +
  `rontolisp:tcp-*` is still a compile error (no serve blob variant with wasi:sockets).
  Interpreter/JVM needed no changes (both sides are `java.net.http.HttpClient` / `HttpServer`).
  Test: `WasmLispCompilerIntegrationTest.httpHandlerFetchInsideServeUnderWasmtimeServe` (the
  fetch backend is itself a plain rontolisp serve component, so the test stays offline).
- **v1 limitations** -- on the WASM component, request/response headers are
  dropped (the handler sees `:headers nil`, response `:headers` is ignored).
  The interpreter and the JVM backend (since 2026-07-04,
  `JvmHttpHandlerRuntimeBuilder`: request `List<Header>` -> `:headers` alist,
  response alist -> `Response` headers, malformed entries skipped) pass headers
  through.
