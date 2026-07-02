# `rontolisp:fetch` / `rontolisp:await` (asynchronous outgoing HTTP, JS `fetch`-style)

`rontolisp`-package functions (not CL standard). `fetch` starts the request and
immediately returns a **promise**; `await` blocks until it settles and returns the
`(:status :body :headers)` plist. Behavior, options, and limitations live in the doc site
(`doc/*/reference/functions/rontolisp-fetch.md` / `rontolisp-await.md`).

**Promise representation** mirrors the stream convention (opaque, backend-local integer
handle): interpreter -- a `Map<Long, CompletableFuture<HttpResult>>` in `Environment` (the
handle is a `LispInteger`); JVM -- a static `_promises` `ArrayList` of futures in the
emitted class, handle = `Long` index (`JvmFetchRuntimeBuilder` emits `_fetch` + `_await`);
WASM -- the wasi:http `future-incoming-response` handle itself, boxed as an i31 integer.

**Error timing** is JS-like: options are validated at `fetch` time; request/transport
failures surface at `await` (interpreter/JVM signal; WASM returns `nil` -- and a fetch
that cannot start returns `nil` instead of a promise, `(await nil)` -> `nil`). A settled
promise can be awaited repeatedly: `join()` is idempotent, and on WASM
(`future-incoming-response.get` consumes the response) `rontolisp:await` caches every
settled plist in the `GLOBAL_PROMISE_CACHE` alist keyed by the promise handle. That key
is only valid because the adapter deliberately NEVER drops settled futures -- wasmtime
recycles handle indices after a drop, and a recycled handle made awaits cross wires
(fetch B returning fetch A's cached response).

Interpreter (`eval/HttpSupport.requestAsync`, via `HttpClient.sendAsync` -- request
building failures fail the future; the per-request client is deliberately never closed)
and JVM (`JvmFetchRuntimeBuilder`) use the JDK `java.net.http.HttpClient`. **WASM is
component-only** (`WasmFetchCompiler`/`WasmAwaitCompiler` throw in Preview 1 mode -- there
is no host `wasi:http`).

**Hybrid**: a fetch component keeps base I/O on WASI 0.3 but adds the WASI 0.2 http
machinery (`wasi:http@0.2` + `wasi:io@0.2`), because async `wasi:http@0.3` does not exist
upstream yet (see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md` for the upgrade path). So a
fetch component needs `-S http=y` in addition to the async flags; non-fetch components
don't import `wasi:http`. To avoid forcing `-S http=y` on every component, the http
machinery lives in a parallel blob set (`import-block-http.bin`/`mem-http.wasm`/
`adapter-http.wasm`, sources `uni-http.wit`/`core-http.wat`/`mem-http.wat`/
`adapter-http.wat`, `deps/*-0.2` + `deps/http`); `WasmComponentBuilder.build(core,
usesHttp)` -> `buildHttp`, emitted only when the program uses fetch/await. The rontolisp
core imports a version-agnostic two-function seam (module "http": `fetch-start` (8 x i32,
last = handle out-pointer) and `fetch-await` (6 x i32), function indices
`FUNC_FETCH_START`=8 / `FUNC_FETCH_AWAIT`=9, trap stubs when unused so `FUNC_START` stays
10 in every mode); only `adapter-http.wat` + `import-block-http.bin` + `buildHttp` bind to
a WASI http version (so the future 0.3 upgrade is isolated). The adapter's `fetch-start`
parses the URL, builds/sends the outgoing-request (streaming the request body) and hands
back the `future-incoming-response` handle without waiting; `fetch-await` does
`subscribe`/`pollable.block`/`get` and serializes status/headers/body -- so multiple
requests genuinely overlap between start and await. The core serializes headers and
rebuilds the result plist via `WasmFetchRuntimeBuilder` helpers; `:method` is resolved
statically by `WasmFetchCompiler.methodDiscriminant` (unsupported literal = compile error;
runtime-computed = GET). The 0x60000/0x70000 response buffers are shared scratch, safe
because the core copies them into GC values before the next `fetch-await`. Regenerating/
re-wiring follows `src/wasm-component/README.md` (adding adapter exports + core imports
needed NO `buildHttp` constant re-derivation: instantiation args bind the whole adapter
instance to the "http" module name, so new names resolve automatically).

**Browser playground**: `java.net.http` can't be GraalVM Web Image-compiled, so a
web-profile substitution (`src/web/java/.../eval/Target_HttpSupport.java`) substitutes
`requestAsync`: it runs a synchronous `XMLHttpRequest` (`web/BrowserHttp.java`, `@JS`,
subject to CORS) at fetch time and returns an already-settled
(`completedFuture`/`failedFuture`) promise -- same semantics, no overlap.

Tests: interpreter/JVM use a local `HttpServer` (including awaited-twice and two-in-flight
out-of-order cases); `WasmLispCompilerIntegrationTest` has deterministic error-path + `-S
http` gate tests plus an opt-in (`RONTOLISP_HTTP_E2E=1`) success test that also exercises
two overlapping promises awaited out of order.
