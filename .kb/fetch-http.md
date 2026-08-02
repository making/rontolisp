# Futures (`rontolisp:await` / `futurep`) and `rontolisp:fetch` (async HTTP)

`rontolisp`-package functions (not CL standard). `fetch` starts the request and
immediately returns a **future** (`.kb/async-await.md` has the full future/stream
machinery); `await` resolves it, `futurep` is the type predicate. The promise-era
`then`/`promisep` surface is DELETED (the todo-139 breaking redesign): composition is
`async-defun`/`async-lambda` + `await`. Futures print as `#<FUTURE>`, streams as
`#<STREAM>`, on every backend.

**Future representation per backend**: interpreter = `LispFuture` (wraps a
`CompletableFuture<LispVal>`; the resolver `LispEvaluator.awaitValue` joins and
flattens); JVM = a bare `java.util.concurrent.CompletableFuture` (nothing else in the
runtime value representation is one); WASM `--component` asyncMode = the first-class
`TYPE_FUTURE` struct (state machines + scheduler, `.kb/async-await.md`); Preview 1 =
the internal degenerate `TYPE_P1_FUTURE` struct (settled at creation;
`FUNC_P1_FUTURE_AWAIT` resolves it).

`await`/`futurep` compile/run on every backend and WASM mode (Preview 1 included);
**only `fetch` is component-only** (`WasmFetchCompiler` is a compile error outside
`--component`; the JVM emits the fetch/await runtime when fetch or await is used).

**The result plist is `(:status <int> :headers <alist> :body <stream>)` on every
backend** -- `:body` is an asynchronous stream drained with
`(rontolisp:await (rontolisp:read-all ...))`. It is the response half of the
one-place plist shape, `compiler/HttpPlistShape` (see the `http-handler`
section below), so its keys and order are derived, not hand-written, in each
backend's fetch runtime.

**Error timing** is JS-like: options are validated at `fetch` time; request/transport
failures surface at `await` -- EVERY backend signals there (on WASM the send result's
error arm becomes a `rontolisp:wit-error` condition, catchable with `handler-case`; the
interpreter/JVM signal a plain error there, a known type divergence). A fetch that
cannot even start (malformed URL) returns `nil` instead of a future on WASM.

Interpreter (`eval/HttpSupport.requestAsync`, via `HttpClient.sendAsync` -- request
building failures fail the future; the per-request client is deliberately never closed)
and JVM (`JvmFetchRuntimeBuilder`) use the JDK `java.net.http.HttpClient`.

**Uniformly WASI 0.3, one `http.lisp` for fetch AND serve (the todo-002 cutover,
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
  `(packed . retptr)` token, which `rontolisp::%subtask-future` turns into a
  first-class PENDING `TYPE_FUTURE` (registry + task waitable-set; the generated
  LIFT wrapper reads the result out at retptr when the scheduler sees the subtask's
  RETURNED event). The public `send` binding is an async-defun that awaits and
  unwraps the `result<response, error-code>` envelope, so awaiting SIGNALS the error
  arm (`rontolisp:wit-error`); `rontolisp:fetch` composes it through the async-defun
  `%fetch-run` and keeps the nil-on-start-failure contract. `:body` comes back as a
  `TYPE_WASI_STREAM` (see `.kb/async-await.md`). Run flags:
  `wasmtime run -W gc=y -W exceptions=y -S http=y`
  (everything is base component-model-async, default-on; `-S http=y` links the
  host's `wasi:http`; `-W exceptions=y` because asyncMode forces EH mode).
  Non-fetch components do not import `wasi:http`.
- **serve (incoming)**: the handler implements `handler.handle: async func(request) ->
  result<response, error-code>` as a CALLBACK async lift (stub callback; the task's
  blocking is the parked waitable-set.wait inside the wrappers); the response is delivered
  MID-TASK via `canon task.return` (the `<alias>-task-return` member kind) before the
  body is streamed -- the task's core return would otherwise complete the task
  before the host could read the contents stream (the built-ins are RENDEZVOUS,
  unbuffered). Serve and serve+fetch are ONE component shape over ONE import block
  (`import-block-http-server.bin`, regenerated from the 0.3 `uni-http-server` world);
  `WasmServeComponentBuilder.build` lowers http.lisp's own wasi:http surface FROM the
  block (`lowerServeIoFromBlock` emits every appendUserImports member kind against the
  block's instances) and an ADDITIONAL user `wit-import` (e.g. wasi:keyvalue) rides
  `appendUserImports` alongside. Run flags: `wasmtime serve -W gc=y -W exceptions=y
  ` -- no gated feature flags (the `service` world's client import is
  host-provided by default -- no `-S http=y`).
- **Bodies (shared, symmetric)**: request and response are the same 0.3 shape
  (`contents: option<stream<u8>>` + a trailers future), so `%http-body-value` serves
  both directions: it runs `consume-body` (which MOVES its resource) eagerly and
  wraps the (stream, trailers, transmit-res) protocol into a first-class stream
  value via `rontolisp::%wasi-stream-new` -- the read thunk issues one built-in read
  per call (an in-flight chunk = a PENDING future the scheduler settles, so the task
  keeps running), and the close thunk (run ONCE, at EOF or an early stream-close) drops
  the readable end + the unread trailers and resolves the transmit future ok (an
  unfinished body traps). `%serve-handle` stream-closes the request body after
  dispatch so the protocol completes even when the handler never read it, and
  drains a STREAM response body via its private `%http-drain` (http.lisp stays
  self-contained -- no prelude dependency). http.lisp accepts the per-call
  bump-heap growth of an async start (the start wrapper must not pop its staging --
  args + retptr outlive the call until the lift).

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
out-of-order cases); Preview-1 await passthrough runs under wasmtime in
`WasmLispCompilerIntegrationTest.promiseOpsWorkInPreview1Mode`; deterministic
component error-path + `-S http` gate tests plus an opt-in (`RONTOLISP_HTTP_E2E=1`)
success test exercise overlap and out-of-order awaits; ci-spec has network-free
`async-defun-await-futurep` / `await-passes-non-futures-through` cases covering all
four backends.

## `rontolisp:http-handler` (incoming HTTP / serving)

The incoming counterpart of `fetch`, sharing the HTTP value model: the handler
(a quoted defun name, like `wasm-export`) takes a request plist
`(:method :path :query :headers :body)` and returns a response plist
`(:status :headers :body)`; missing keys default to `:status 200` / empty body.

**The plist SHAPE is written once — `compiler/HttpPlistShape`**, a WIT `record`
pair (the house `record` = keyword plist convention, `WitTypeMapper.Rep.PLIST`)
parsed at class load; `HttpPlistShapeTest` pins it. Every backend derives its
builders and readers from the parsed fields: the interpreter
(`LispEvaluator.invokeHttpHandler`, `Environment`'s fetch result) and the JVM
(`JvmHttpHandlerRuntimeBuilder`, `JvmAsyncRuntimeBuilder`'s fetch result) loop
over the fields in Java, and the WASM component path calls generated Lisp
helper defuns (`%http-request-plist` / `%http-response-plist` builders +
`%http-response-status`-style accessors, `HttpPlistShape.lispHelpersSource()`
spliced by `HttpLibrary` next to `http.lisp`). The response defaults
(`:status 200`, empty body) are shape constants consumed the same three ways.
A record field with no per-backend value extraction — the one part that cannot
be derived — fails that backend loudly at build/compile time (a switch default
or `requireResponseHandled`), so a field change is one record edit plus the
extraction it demands, never a silent per-backend drift. Shape deviations from
the settled WIT mapping (dotted-pair `:headers` alist, the response defaults, a
string response body) are documented on the class.

`:path` is the path only and `:query` the raw query string without the `?`
(nil when the request has none; `""` for a bare trailing `?`). The split at
the first `?` is VALUE EXTRACTION, not shape, so it remains per-backend code:
`HttpHandlerSupport.Request.of` (interpreter and JVM inherit it) and
`%serve-read-request` in `http.lisp` on the component path. Decoding policy
lives in the URL library (`.kb/url.md`), not here.

ONE VIRTUAL THREAD PER REQUEST is a correctness constraint on everything a handler
touches, not just an implementation note: process-wide mutable state reached from a
handler must be thread-safe. The rule, the bugs it has already produced (the stream
table, the interpreter's lazy library loads) and the shape new code must follow are in
`.kb/concurrent-served-requests.md`; locks for program-level state are in
`.kb/mutexes.md`.

- **Interpreter (implemented)** -- `HttpHandlerSupport` (eval pkg, `public` for
  the future web substitution): a blocking JDK `com.sun.net.httpserver.HttpServer`,
  ONE VIRTUAL THREAD PER REQUEST (`Executors.newVirtualThreadPerTaskExecutor`).
  `serve(port, handler)` blocks forever (Ctrl-C to stop); `start(port, handler)`
  is the non-blocking test seam (port 0 = ephemeral) and `stopAllForTesting()`
  shuts servers down. Registered in `LispEvaluator` (not `Environment`) because
  serving a request applies the handler via the evaluator's `apply`;
  `invokeHttpHandler` builds the request plist and reads the response plist.
  Since todo-228 the class also carries the STOPPABLE per-server seam behind
  the internal `rontolisp::%http-server-start/-join/-stop/-port` functions
  (`startServer`/`joinServer`/`stopServer`/`serverPort`: handler as a FUNCTION
  VALUE, bind address, opaque integer handle -- the socket/mutex convention --
  idempotent stop, interrupt-tolerant join) -- the clack-handler-rontolisp
  acceptor, `.kb/clack.md`. The JVM lowering (`JvmHttpServerSeamCompiler`)
  reuses the directive's `_httpHandlerFn` slot and injected `handle` runtime,
  so `JvmLispCompiler`'s `usesHttpHandler` gate also fires on
  `%http-server-start` -- which is also why there is ONE handler slot and so
  one Clack server per process.
  Tests: `HttpHandlerTest` (Java seam round trip + directive round trip via a
  background thread + validation + the stoppable-seam group).
  **Every entry point reachable from `LispEvaluator` must have a matching
  `@Substitute` in `Target_HttpHandlerSupport` (`src/web/java`).** GraalVM Web
  Image's points-to analysis reaches `java.lang.VirtualThread.runContinuation`
  -- which calls a `Thread.isInterrupted()` substitution unavailable on the
  `svm-wasm` platform -- from ANY un-substituted method that still touches the
  real `HttpServer`/`Executors.newVirtualThreadPerTaskExecutor` (build failure,
  not a runtime one: `[1/8] Performing analysis...` reports "Method ...
  Target_java_lang_Thread.isInterrupted() is not available in this platform"
  while parsing `VirtualThread.runContinuation`). `serve` alone was substituted
  when the directive shipped; todo-228 added `startServer`/`joinServer`/
  `stopServer`/`serverPort` without extending the substitution, which passed
  every JVM-side test (the class compiles and runs fine there) but broke the
  `Deploy playground to GitHub Pages` build, because the pages workflow is the
  only CI job that actually runs `-Pweb` `native-image --tool:svm-wasm`. Add
  the stub to `Target_HttpHandlerSupport` in the SAME commit as any new
  `HttpHandlerSupport` entry point, and verify with
  `./mvnw -Pweb -DskipTests package` (needs a `wasm-as` on `PATH`, e.g. from
  the Binaryen release the pages workflow installs) -- `./mvnw test` does not
  catch this.
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
  `:status`/`:headers`/`:body` back with plist-get loops (the `:headers` alist
  of `(name . value)` string pairs is marshalled into an `ArrayList` of
  `Header`, skipping malformed entries like the interpreter does) and returns
  `new Response(status, hdrs, body)`. CONSEQUENCE:
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
  Since todo-228 the directive is detected NESTED inside a defun body too (a
  `(rontolisp:http-handler '<literal-name> ...)` call still yields a static
  handler name; quoted data excluded, first name wins, the call site lowers to
  nil) -- the clack-handler-rontolisp shim's `run` is the driving shape, and
  `HttpHandlerInliner.usesHttpHandler` (the CLI's serve-mode switch) walks the
  same way. The bridge + export are appended AFTER the program so a
  package-qualified nested handler name resolves against its own spliced
  defpackage. `.kb/clack.md` has the whole flow.
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
  `.
  **Top-level init runs on the FIRST handle call, once per instance**: a serve
  component never lifts `run`, so the `handle` wrapper
  (`WasmExportCompiler.emitBody`) calls `_start` under a serve-only
  `(mut i32)` init flag (`serveInitGlobalIndex`, the last module global;
  non-serve output is byte-identical) before the request task begins --
  without it NO top-level form ran and every defvar/defparameter global read
  back null inside a handler (the "cast failure" trap on first arithmetic;
  the 0.2-era serve adapter used to run `run` once as init, and the callback
  cutover lost that). Init runs inside the handle call's task context, so a
  top-level suspension drives through the blocking event loop as under
  `wasmtime run`. Pinned by
  `httpHandlerReadsATopLevelGlobalUnderWasmtimeServe`; the serve+tcp
  composition (the shape that surfaced the bug) by
  `httpHandlerConnectsTcpUnderWasmtimeServe` -- tcp under `wasmtime serve`
  additionally needs `-S cli=y -S tcp=y -S inherit-network=y`
  (`.kb/tcp-sockets.md`).
  An ADDITIONAL `rontolisp:wit-import` (e.g. `wasi:keyvalue`, so a handler's state
  lives in a real store) rides `appendUserImports` alongside the fixed surface.
  `adapter-http-server-p1.wat` is the preview1 bridge (instantiated BEFORE the
  core), rewritten over the 0.3 service interfaces + stream/future built-ins: it
  implements `random_get` over `wasi:random`, `clock_time_get` over `wasi:clocks`,
  and `fd_write` (fd 1/2) over the cli stdout/stderr path, so `random` / time
  built-ins / `print` work inside a served handler; `environ_*` report a zero
  environment (`uiop:getenv` -> nil), `fd_read` is immediate EOF, `path_open` returns
  errno 76 (file streams stay unavailable -- the serve world has no filesystem).
  The canonical-ABI allocator (`mem-http-client.wat`, bump pointer in the
  `CABI_HP_CELL_ADDR` = 0x10000 linear cell, base 0x10008) is reset at the top of
  the serve `handle` wrapper for hosts that reuse one instance across requests
  (wasmtime serve re-instantiates per request, so it never sees the growth).
  serve + `rontolisp:tcp-*` COMPILES now (sockets.lisp is one more user WIT
  import beside the fixed wasi:http surface, `.kb/tcp-sockets.md`); on Preview-1
  WASM the directive is a CALL-time error stub since todo-228 (same "requires
  --component" message; was a compile error -- the clack shim's `run` carries
  the directive as dead code there, the todo-195 socket policy), and so are
  `stream-read`/`stream-close`/`streamp` when no stream type exists (Preview 1
  or a non-async component; an uncaught error is a silent trap on Preview 1,
  so pin messages through handler-case). Hosts: wasmtime 46+; wasmCloud
  hosts it (released wash 2.5.2, `wash dev` with `dev.wasm_proposals:
  [gc, exception-handling, component-model-async]` -- verified 2026-07-16 on
  examples/wasmcloud/http-handler); Spin hosts it from the canary build
  (https://github.com/spinframework/spin/releases/tag/canary -- 4.1.0-pre0,
  wasmtime 47 -- gc + exceptions default-on, `wasi:http@0.3.0`
  final) with a plain `spin.toml`, verified 2026-07-30 on
  examples/net/http-handler (plus magic-8-ball for wasi:random and dog-fetcher
  for outbound fetch, the latter needing `allowed_outbound_hosts`); jco cannot
  run the 0.3 async ABI. Spin is the host that actually EXERCISES the allocator
  reset above -- it serves 128 requests per instance by default (measured, and
  the count is `--max-instance-reuse-count`; `.kb/tcp-sockets.md` has the
  three-host instance-lifetime comparison), so the "hosts that reuse one
  instance across requests" clause is no longer hypothetical.
  Why released Spin 4.0.2 cannot host it, and the re-evaluation trigger: it
  embeds wasmtime 44, whose p3 WIT is the `0.3.0-rc-2026-03-15` SNAPSHOT of
  every WASI package (`wasi:http`, `wasi:cli`, `wasi:clocks`, `wasi:random`),
  not the released `0.3.0` we emit -- so the imports fail to link
  ("instance export `fields` has the wrong type") even with GC forced on. The
  gate is the wasmtime version a host embeds, NOT the GC proposal any more:
  a host on wasmtime 46+ needs no flags at all. (4.0.2's
  `--experimental-wasm-feature gc` exists in the source but is behind the
  `experimental-wasm-features` cargo feature, which spin's release workflow
  passes only for canary builds, so the released binary has no such option.)
  Tests: the serve cases in `WasmLispCompilerIntegrationTest` (echo / big
  response / random-clock-print / keyvalue / fetch-inside-serve proxy, all
  through the `compileServeComponent` CLI-path helper).
