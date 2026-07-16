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
  kind 1 = then chain, 2 = settled (base = memoized result). `_promise_await`
  (`FUNC_PROMISE_AWAIT`, WasmPromiseRuntimeBuilder; a real function because it recurses)
  rewrites the struct to kind 2 in place after resolving, so each chain callback is
  consumed exactly once. There is NO dedicated fetch kind: http.lisp's fetch is
  `(then (%fetch-send ...) #'%fetch-read-response)` -- a kind-1 chain whose base is the
  async `client.send` start token (a `(packed . retptr)` cons) and whose callback is the
  generated await wrapper. (The 0.2-era kind 0 / `FUNC_FETCH_START`/`FUNC_FETCH_AWAIT`
  adapter seam was deleted in the .todo/02 Phase-3 cleanup.)

`await`/`then`/`promisep` compile/run on every backend and WASM mode (Preview 1
included); **only `fetch` is component-only** (`WasmFetchCompiler` is a compile error
outside `--component`; the JVM emits the fetch/await runtime when fetch or await is used).

**Error timing** is JS-like: options are validated at `fetch` time; request/transport
failures surface at `await` -- EVERY backend signals there (on WASM the send result's
error arm becomes a `rontolisp:wit-error` condition, catchable with `handler-case`; the
0.2-era nil-on-failure convention is gone with the wasi:http@0.3 cutover). A fetch that
cannot even start (malformed URL) returns `nil` instead of a promise on WASM. There is
deliberately no onRejected/catch parameter yet (see
`.todo/45-promise-error-callback.md`).

Interpreter (`eval/HttpSupport.requestAsync`, via `HttpClient.sendAsync` -- request
building failures fail the future; the per-request client is deliberately never closed)
and JVM (`JvmFetchRuntimeBuilder`) use the JDK `java.net.http.HttpClient`.

**Uniformly WASI 0.3, one `http.lisp` for fetch AND serve (the .todo/02 cutover,
committed `bef8c1b`).** fetch and serve are ONE Lisp-source library
(`src/main/resources/am/ik/rontolisp/eval/http.lisp` + its embedded `http.wit`, the
vendored `wasi:http@0.3.0` types/handler/client plus a clocks/types shim and four
transparent type aliases: `body-stream`, `trailers-future`, `transmit-future`,
`handle-result`), spliced by `eval/HttpLibrary` with a REACHABILITY-based member filter
from the active roots (`rontolisp:fetch` / `%serve-handle`) -- a fetch-only program binds
no serve member (no task-return) and vice versa. Everything rides the general
`wit-import` canon-lower machinery (`.kb/wit.md`): there is no http blob variant for the
non-serve path and no WAT http adapter anywhere. `WasmFetchCompiler` is a validator that
falls through: under `--component` it runs the compile-time arity / literal-`:method`
check and control reaches the http.lisp defun; in Preview 1 it raises the component-only
compile error.

- **fetch (outgoing)**: `%fetch-send` builds the request resource, writes the body via
  the `body-stream` alias built-ins, and calls `%http-client:send` -- an `async func`
  member, so it async-lowers (`canon lower ... async`): the start wrapper returns a
  `(packed . retptr)` token and the generated await wrapper drives a
  `waitable-set.new/join/wait` loop, then lifts the `result<response, error-code>` at
  retptr. The public defun is `(rontolisp:then (start ...) #'awaited)`, so the EXISTING
  promise machinery does the async part and awaiting SIGNALS the error arm
  (`rontolisp:wit-error`). Run flags:
  `wasmtime run -W gc=y -W exceptions=y -W component-model-more-async-builtins=y -S http=y`
  (async LOWER is in the default feature set, so no stackful flag; `-S http=y` links the
  host's `wasi:http`). Non-fetch components do not import `wasi:http`.
- **serve (incoming)**: the handler implements `handler.handle: async func(request) ->
  result<response, error-code>` as a stackful async lift; the response is delivered
  MID-TASK via `canon task.return` (the `<alias>-task-return` member kind) before the
  body is streamed -- a stackful task's core return would otherwise complete the task
  before the host could read the contents stream (the built-ins are RENDEZVOUS,
  unbuffered). Serve and serve+fetch are ONE component shape over ONE import block
  (`import-block-http-server.bin`, regenerated from the 0.3 `uni-http-server` world);
  `WasmServeComponentBuilder.build` lowers http.lisp's own wasi:http surface FROM the
  block (`lowerServeIoFromBlock` emits every appendUserImports member kind against the
  block's instances) and an ADDITIONAL user `wit-import` (e.g. wasi:keyvalue) rides
  `appendUserImports` alongside. Run flags: `wasmtime serve -W gc=y -W exceptions=y
  -W component-model-async-stackful=y -W component-model-more-async-builtins=y`
  (stackful lift needs the stackful flag; the `service` world's client import is
  host-provided by default -- no `-S http=y`).
- **Bodies (shared, symmetric)**: request and response are the same 0.3 shape
  (`contents: option<stream<u8>>` + a trailers future), so `%http-consume-text` /
  `%http-write-body` serve both directions. `consume-body` MOVES its resource (dropping
  the handle afterwards traps); an unfinished body traps, so the guest resolves its
  transmit future ok once the body is read. Drops are straight-line in http.lisp (the
  `with-*` scope-guarantee macro was deliberately deferred). http.lisp accepts the
  per-call bump-heap growth of an async start (the start wrapper must not pop its
  staging -- args + retptr outlive the call until await).

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
- **WASM component (implemented, `--component`; serve and serve+fetch are ONE
  shape)** -- the HTTP glue is **`http.lisp`** over wit-imported `wasi:http@0.3.0`
  (see the fetch section above for the splice and the async machinery). There is
  no hand-written serve adapter and no wide/narrow block split: `HttpLibrary.process`
  (spliced in the CLI right after `WitImportInliner`, before `UserMacroExpander`,
  gated to `--component` serve -- and off for a `wit-export` world) replaces the
  `rontolisp:http-handler` directive with the serve half of http.lisp, a
  `(defun %serve-dispatch (r) (HANDLER r))` bridge, and a
  `(rontolisp:wasm-export '%serve-handle :as "handle" :params '(:int) :returns :void)`.
  The core `handle` export is `[i32 request] -> []` and is lifted
  `canon lift (memory, utf8, async)` against
  `async func(request: own<request>) -> result<own<response>, error-code>` -- the
  function type must be built over the block's NAMED aliases (request/response/
  error-code): the component-model export rule requires every non-structural type an
  exported function references to be NAMED (anonymous structural types there fail
  validation with "instance not valid to be used as export"). The response is
  delivered mid-task via `canon task.return`, then the body streams (rendezvous
  order: task.return -> stream.write -> drop-writable -> future.write trailers).
  **Headers are marshalled both directions** (`fields-copy-all` in,
  `fields.append` out). http.lisp uses `handler-case`, so a serve component is
  EH-mode. Run with `wasmtime serve -W gc=y -W exceptions=y
  -W component-model-async-stackful=y -W component-model-more-async-builtins=y`.
  An ADDITIONAL `rontolisp:wit-import` (e.g. `wasi:keyvalue`, so a handler's state
  lives in a real store) rides `appendUserImports` alongside the fixed surface.
  `adapter-http-server-p1.wat` is the preview1 bridge (instantiated BEFORE the
  core), rewritten over the 0.3 service interfaces + stream/future built-ins: it
  implements `random_get` over `wasi:random`, `clock_time_get` over `wasi:clocks`,
  and `fd_write` (fd 1/2) over the cli stdout/stderr path, so `random` / time
  built-ins / `print` work inside a served handler; `environ_*` report a zero
  environment (`getenv` -> nil), `fd_read` is immediate EOF, `path_open` returns
  errno 76 (file streams stay unavailable -- the serve world has no filesystem).
  The canonical-ABI allocator (`mem-http-client.wat`, bump pointer in the
  `CABI_HP_CELL_ADDR` = 0x10000 linear cell, base 0x10008) is reset at the top of
  the serve `handle` wrapper for hosts that reuse one instance across requests
  (wasmtime serve re-instantiates per request, so it never sees the growth).
  serve + `rontolisp:tcp-*` is still a compile error (no serve blob variant with
  wasi:sockets); Preview-1 WASM output is a compile error ("requires
  --component"). Hosts: wasmtime 46+; wasmCloud's WASI P3 support is experimental
  (`wasip3`-feature wash targeting an RC -- interop with final-`@0.3.0`
  components unverified); jco cannot run the 0.3 async ABI; Spin has no wasm-GC.
  Tests: the serve cases in `WasmLispCompilerIntegrationTest` (echo / big
  response / random-clock-print / keyvalue / fetch-inside-serve proxy, all
  through the `compileServeComponent` CLI-path helper).
