# wasi:http incoming-handler — compile a rontolisp defun into an HTTP component (wasmtime serve / Spin)

Status: DONE for interpreter + WASM component + JVM (2026-07-04).
`wasmtime serve` works end to end; Spin is BLOCKED by Spin not enabling wasm-GC
(and offering no flag to). Runtime survey 2026-07-04: the serve component is
plain WASI 0.2 -- `wasmtime serve -W gc=y` alone suffices (the
`component-model-async` flags in the SHIPPED notes below are unnecessary), and
two alternative hosts were verified working: jco (`npx @bytecodealliance/jco
serve app.wasm`, jco 1.24.6 / Node 22; 50 requests + POST OK) and wasmCloud
(wash 2.5.1 `wash dev` with `dev.wasm_proposals: [gc]`, or `wash host
--wasm-proposal gc`; fails without it: "rec group usage requires `gc`
proposal"). WasmEdge (component model experimental) and Wasmer (no component
model) cannot run it. Docs/comments updated accordingly.
Request/response HEADER marshalling (JVM + WASM) remains (see "Follow-ups" at
the bottom). Created 2026-07-04.

## SHIPPED (commits 068e958, e3e8ffd, f8d6a5f, ea7601f, 215d384)

- Surface + interpreter server (virtual threads) + tests + docs.
- WASM serve component: `HttpHandlerInliner` (cli) rewrites the directive to a
  `%http-dispatch` wasm-export wrapper; `WasmLispCompiler` serve mode
  (`serve` flag, implies component) un-gates wasm-export + stubs WASI like
  --no-wasi while importing memory; `WasmServeComponentBuilder.buildServe` wires
  mem-http-client + core + `adapter-http-server.wasm` and exports
  `wasi:http/incoming-handler@0.2.0`. Runs under
  `wasmtime serve -W gc=y -W component-model-async=y
  -W component-model-async-stackful=y -W component-model-more-async-builtins=y`.
  Verified: GET/POST, path/method/body marshalling, per-request status
  (200/404), 20+ requests; CI test
  `WasmLispCompilerIntegrationTest.httpHandlerServesUnderWasmtimeServe`.
- `ComponentWriter.funcTypeParamsNoResult` (the handle func type).
- Constants derived from `wasm-tools dump` of the `uni-http-server` reference: import
  instances 0=io/error,1=io/streams,2=http/types; component types 0-5 (3=input-
  stream,4=output-stream), next free 6; canonical options per wasi func recorded
  in `WasmServeComponentBuilder`.
- JVM backend, exactly per the follow-up design below (2026-07-04): the
  generated class implements `HttpHandlerSupport.Handler` (shared no-arg ctor
  with the tls-connect trick; `handle` is an extra `--optimize` shaker root),
  `JvmHttpHandlerCompiler` stores the `#'name`-resolved funcref in
  `_httpHandlerFn` and emits `HttpHandlerSupport.serve(port, new Prog())`, and
  `JvmHttpHandlerRuntimeBuilder` injects `handle(Request)` (request plist ->
  `_invoke_1` -> `:status`/`:body`, headers dropped,
  `Collections.emptyList()` because interface-static `List.of()` is illegal at
  class version 50). The compiled class needs the rontolisp jar on the runtime
  classpath. Tests: `HttpHandlerJvmTest` (compile + curl round trips incl.
  `--optimize`), `JvmLispCompilerTest.compileHttpHandlerImplementsHandlerInterface`
  / `compileHttpHandlerRejectsUnquotedHandlerName`. Docs/examples updated
  (reference page, tcp-sockets guide, examples/http-handler.lisp header).

## Follow-ups

- **Spin**: `spin up` cannot run any rontolisp component -- Spin's embedded
  wasmtime does not enable the wasm-GC proposal. Revisit if Spin exposes a GC
  flag / runtime-config, or gains GC by default.
- **Headers (WASM)**: the WASM serve component marshals only method/path/body
  (request) and status/body (response); headers are dropped. Extend the
  `%http-dispatch` encoding (e.g. a length-prefixed header block) and the
  adapter-http-server.wat request read / response write (the fetch adapter's
  `fields.entries` / `fields.append` loops are the template).
  JVM headers DONE 2026-07-04: `JvmHttpHandlerRuntimeBuilder.handle()` walks
  `Request.headers()` into the `:headers` alist and the response `:headers`
  alist back into `Response` headers (interpreter-lenient: malformed entries
  skipped). Tests: `HttpHandlerJvmTest.compiledDirectiveMarshalsRequestHeaders`
  / `MarshalsResponseHeaders`.
- **Allocator reset** -- DONE 2026-07-04. The mem-http-client `cabi_realloc` and core
  `__ronto_alloc` bump pointers never rewound, so an instance-reusing host (jco
  serve, wasmCloud -- verified: jco reuses one instance across requests, unlike
  `wasmtime serve` which instantiates per request) grew linear memory by
  roughly the response size per request (`__ronto_alloc` memory.grows).
  `adapter-http-server.wat` now snapshots the core heap ptr (@84), the runtime intern
  count (@100) and the mem `$hp` global (newly exported as `"hp"`) after init,
  and restores both allocators at each `serve` entry; the core heap restore is
  guarded by the intern count (runtime interning ratchets the snapshot up
  instead, since `_intern` records reference token bytes in place). The init
  flag AND the snapshots live in ADAPTER-LOCAL GLOBALS, not linear memory: a
  response > ~48 KiB sweeps the core bump heap across the whole 0x50000 scratch
  page, so linear-memory state written by request N is garbage by request N+1
  (observed as a jco crash: `$hp` snapshot read back as 0x78787878 -> realloc
  out of bounds; the historical 0x50300 init flag survived only because any
  nonzero body byte still read as "initialized"). GC-heap state (globals,
  hash tables) is untouched by the reset -- verified with a counter handler on
  jco (1,2,3,... across requests) and 10 x 256 KiB requests on one instance.
- **Response chunking + outparam ordering** -- DONE 2026-07-04.
  `blocking-write-and-flush` accepts at most 4096 bytes per call, and
  adapter-http-server.wat wrote the whole body in one call, so any response > 4096
  bytes failed on EVERY host ("Buffer too large... expected at most 4096" ->
  500). Now chunked at 4096 like adapter-http-client.wat's request-body loop, AND
  `response-outparam.set` moved BEFORE the body writes: the host only starts
  consuming the body stream after the outparam is set, so set-after-write
  deadlocks as soon as the body exceeds one host buffer (the old order only
  ever worked because a single <= 4096-byte write fit the buffer). Order now:
  ctor/status/body -> resp_set -> write chunks -> drop -> finish. Test:
  `WasmLispCompilerIntegrationTest.httpHandlerServesResponseLargerThanIoChunkUnderWasmtimeServe`
  (8 KiB); 256 KiB verified manually on wasmtime serve + jco.
- **JVM backend** — DONE (2026-07-04, see SHIPPED above). Original design
  (reuses two proven mechanisms):
  1. The compiled program class IMPLEMENTS `HttpHandlerSupport.Handler`
     (`Response handle(Request)`), exactly like the TLS-insecure trick where the
     class implements `javax.net.ssl.X509TrustManager`
     (`JvmLispCompiler` ~L297-317, `writeInterfaces`, the injected methods, and
     the extra `--optimize` shaker roots gated on `usesTlsConnect`). Gate a new
     `usesHttpHandler = programUsesSymbol(program, rontolisp:http-handler)`.
  2. The injected `handle(HttpHandlerSupport$Request)` method body:
     - reads `request.method()/path()/body()` (invokevirtual on the record),
     - builds the request plist `(:method m :path p :headers nil :body b)` as
       cons cells in the shared runtime value rep -- mirror
       `JvmFetchRuntimeBuilder` which already builds
       `(:status .. :body .. :headers ..)` (interned `:status` etc. via
       `cp.addString`),
     - applies the compiled handler funcref via the `_invoke_1` dispatcher (the
       same one fetch/await/mapcar use; force-registered when the fetch runtime is
       emitted -- do the same when `usesHttpHandler`),
     - reads `:status`/`:body` back from the response plist (a plist-get loop; see
       the interpreter's `httpPlistGet` / the WASM `%http-encode` for the shape)
       and `return new HttpHandlerSupport.Response(status, List.of(), body)`.
     v1 can drop request/response headers like the WASM path (List.of()).
  3. The `(rontolisp:http-handler 'name port)` directive site (JvmExprCompiler,
     currently throws) emits `HttpHandlerSupport.serve(port, this)` -- `this` is
     the program instance implementing Handler. Resolve the handler funcref from
     `'name` (a Pass-1 defun) the way `#'name` does.
  Tests: extend `HttpHandlerTest` (or a JVM variant) to compile+run the server on
  a background thread and curl it, like the interpreter directive test. Then flip
  `JvmExprCompiler`'s http-handler case from the compile error to the emit, and
  update `JvmLispCompilerTest.compileHttpHandlerIsCompileError` to a
  compile+serve round trip. Remember: HttpHandlerSupport is already `public` and
  the web substitution (`Target_HttpHandlerSupport`) already stubs `serve`.

## Original status line (historical)

IN PROGRESS (2026-07-04). Interpreter backend DONE (see below);
WASM component pipeline PROVEN end-to-end; Java integration remaining.

## Progress log

- Surface `rontolisp:http-handler` registered (LispNames.HTTP_HANDLER,
  PackageRegistry externals, PackageIntrospection RONTOLISP_FUNCTION_NAMES).
- Request plist `(:method :path :headers :body)`, response plist
  `(:status :headers :body)` — mirrors fetch.
- Interpreter: `HttpHandlerSupport` (embedded JDK `HttpServer`, ONE VIRTUAL
  THREAD PER REQUEST via `newVirtualThreadPerTaskExecutor`) + `LispEvaluator`
  registration + `invokeHttpHandler` translation. `serve()` blocks; `start()` is
  the non-blocking test seam; `stopAllForTesting()`. 6 tests in `HttpHandlerTest`
  pass; curl round trip verified against `examples/http-handler.lisp`.
- WASM PROOF (standalone, /tmp/serveprobe): a hand-written core exporting
  `wasi:http/incoming-handler@0.2.0#handle` + importing wasi:http/types +
  wasi:io/streams, wired by `wasm-tools component new`, runs under
  `wasmtime serve` and returns "hello /world" to curl. Confirms: the export
  wiring, the incoming-side marshalling WAT, and `wasmtime serve`. Key derived
  core ABI signatures (all validated):
  - `[method]incoming-request.path-with-query` -> `(param i32 i32)`;
    ret area: option disc@0, ptr@4, len@8.
  - `[method]incoming-request.method` -> `(param i32 i32)` (variant disc@0,
    other-string ptr/len@4/8).
  - `[method]incoming-request.consume` -> `(param i32 i32)` (result disc@0,
    incoming-body handle@4); `[method]incoming-body.stream` same shape.
  - `[constructor]fields` -> `(result i32)`; `[constructor]outgoing-response`
    (headers)-> `(param i32)(result i32)`.
  - `[method]outgoing-response.set-status-code` -> `(param i32 i32)(result i32)`.
  - `[method]outgoing-response.body` -> `(param i32 i32)` (result disc@0,
    outgoing-body handle@4).
  - `[method]outgoing-body.write` -> `(param i32 i32)` (result disc@0,
    output-stream handle@4); `[static]outgoing-body.finish` ->
    `(param i32 i32 i32 i32)` (self, opt-trailers disc, val, ret).
  - `[method]output-stream.blocking-write-and-flush` ->
    `(param i32 i32 i32 i32)` (self, ptr, len, ret).
  - **`[static]response-outparam.set` -> `(param i32 i32 i32 i32 i64 i32 i32 i32
    i32)`**: self, then the `result<own<outgoing-response>, error-code>`
    flattened into 8 slots. OK case = (self, 0 disc, resp-handle, 0, 0i64,
    0,0,0,0). This was the one non-obvious signature.
  The proof core-http-server.wat / uni-http-server.wit are in /tmp/serveprobe (ephemeral —
  the derived signatures above are the durable record).

## Architecture decision (2026-07-04, after a deep memory-model review)

Use the **component-mode core sharing the buildHttp memory model**, NOT a
`--no-wasi` core:

- A `--no-wasi` core exports its OWN memory, only **4 pages**, and `__ronto_alloc`
  is a bump allocator that does NOT `memory.grow` (over `HEAP_PTR_ADDR`), and its
  signature is `(i32)->i32` — it CANNOT serve as the canonical `cabi_realloc`
  `(i32 i32 i32 i32)->i32`. 4 pages leaves no room for the 0x50000+ adapter
  scratch either. So `--no-wasi` is unsuitable.
- The proven model (buildHttp): a **separate 16-page `mem` module** exports
  `memory` + a 4-arg `cabi_realloc`; the rontolisp core IMPORTS that memory. Core
  linear usage (static data / interns / string content) grows up from
  `rtInternBase` (~0x2000); the adapter uses fixed scratch at 0x50000-0x90000
  (like `adapter-http-client.wat`). Collision-free for reasonable response sizes.
- To make the component-mode core EXPORT `%http-dispatch`, wasm-export must be
  UN-GATED for this "serve" mode. Today it is gated `!this.component` in
  `WasmLispCompiler` (the `exportDecls` loop ~L998, `exportUsesMemory` ~L981, the
  `__ronto_alloc`/`_str_from_mem` helper emission, and the export section ~L1592/
  L1669). Add a `serve`/`httpHandler` flag (implies component) that flips those
  `!this.component` guards to `(!this.component || serve)` and emits the
  `%http-dispatch` wrapper + the two memory helpers even in component mode, while
  keeping every `FUNC_*` index stable. Memory-layout constants:
  `HEAP_PTR_ADDR=84`, `RT_INTERN_BASE_ADDR=152`, rtInternBase ~0x2000,
  fetch/adapter scratch 0x40000+/0x50000+.

This is delicate index/layout surgery in `WasmLispCompiler` plus a `buildServe`
that mirrors `buildHttp` (~185 lines, constants from a `wasm-tools dump`), an
`adapter-http-server.wat` (the proven marshalling calling `%http-dispatch`), and the
compiler integration. Best done as a focused session; iterate against
`wasmtime serve` + curl at each step.

## Remaining WASM work (multi-module, programmatic — native image cannot run wasm-tools)

1. `src/wasm-component/uni-http-server.wit` (world `uni-http-server`: import wasi:http/types@0.2
   + wasi:io/streams@0.2 + wasi:io/error@0.2; NO export in the wit — the
   incoming-handler export is emitted programmatically like `run`). `core-http-server.wat`
   stub (imports the lowered funcs) for `regen.sh` to produce `import-block-http-server.bin`.
2. `adapter-http-server.wat`: shares `mem` with the rontolisp core; imports the lowered
   wasi:http/io funcs (under "w") AND the core's `%http-dispatch` (under "core");
   exports `serve(request i32, response_out i32)` doing the proof's marshalling but
   calling `%http-dispatch` for the body. Encoding: `%http-dispatch` returns
   `"<content-type>\n<body>"` (status hardcoded 200 in v1, OR add status line;
   check whether the WASM backend can format an integer -> string cheaply, else
   hardcode 200 and note it).
3. `WasmComponentBuilder.buildServe(core)`: mem + core + adapter-http-server; derive
   wiring constants from `wasm-tools dump` of a `regen.sh` reference (mirror
   buildHttp). Lift adapter's `serve` as a SYNC export (not the async `run` lift)
   and export instance `wasi:http/incoming-handler@0.2.0`. Instantiation order:
   mem(0), rontolisp core(1, imports mem only — no-wasi so no adapter dep), adapter(2,
   imports mem + core.%http-dispatch + w). No circularity.
4. rontolisp core serve mode: compile no-wasi-style (8 WASI imports stubbed) +
   export `%http-dispatch` via the existing `wasm-export` memory-ABI machinery.
   Splice synthesized Lisp AST at compile time (like defstruct/json):
   `%http-dispatch`/`%http-encode` defuns calling the user handler + a
   `(rontolisp:wasm-export '%http-dispatch :params '(:string :string :string)
   :returns :string)` directive; enable wasm-export emission in component mode for
   this internal use.
5. Compiler integration: WasmLispCompiler collects the http-handler decl (Pass 1),
   splices the wrapper, selects serve mode; cli threads `usesHttpHandler` ->
   buildServe. WasmExprCompiler: http-handler top-level directive is a no-op on the
   Preview-1 (non-component) path -> compile error "http-handler requires
   --component" (like fetch).
6. Verify: `wasmtime serve` + curl; `spin up` with examples/http-handler/spin.toml
   (spin 4.0.2 installed). Docs en/ja + catalog + guide + .kb.

## ORIGINAL PLAN (kept for reference)

## Goal

Let a rontolisp program export an HTTP request handler as a WASM **component**
that exports `wasi:http/incoming-handler`, so it runs under **`wasmtime serve`**
and **`spin up`** (Spin v2+ runs plain wasi:http components). Today `rontolisp:
fetch` is the *outgoing* side (client) over a WASI 0.2 http adapter; this adds
the *incoming* side (server), turning `examples/http-hello.lisp` from a raw-TCP
server into a real serverless HTTP component.

Primary acceptance target: `spin up` serving a rontolisp handler that returns
"Hello from rontolisp" (plus an echo of method/path), AND the same component
under `wasmtime serve`.

## Proposed surface

A directive marking one defun as the handler, mirroring `rontolisp:wasm-export`:

```lisp
(defun handle (request)
  ;; request is a plist: (:method "GET" :path "/" :headers ((k . v) ...) :body "...")
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (concatenate 'string "hello " (getf request :path))))
(rontolisp:http-handler 'handle)   ; or (rontolisp:wasm-export 'handle :http t)
```

Reuse the **fetch request/response plist representation** for symmetry
(`.kb/fetch-http.md`): request `:method`/`:path`(or `:uri`)/`:headers`/`:body`,
response `:status`/`:headers`/`:body`. This keeps one HTTP value model across
incoming and outgoing.

## Where it plugs in (read these .kb files first)

- `.kb/wasi-component.md` — the `--component` output path, `WasmComponentBuilder`
  / `ComponentWriter`, the core module keeps fixed `FUNC_*` indices and the
  adapter bridges component-model to core. This is the same machine that must
  now *export* incoming-handler (today it only *imports*).
- `.kb/fetch-http.md` — the existing WASI 0.2 http adapter for outgoing
  requests; the incoming handler shares the `wasi:http/types` request/response/
  fields/body-stream resources, so much of the marshalling (headers <-> fields,
  body <-> input/output-stream) can be shared/mirrored.
- `.kb/wasm-export-no-wasi.md` — `rontolisp:wasm-export` + reactor mode: how a
  defun is already exposed as a host-callable WASM export. incoming-handler is
  a *component* export, so it is the component-path analogue of this.

## Sketch of the work

1. **Directive + AST**: add `rontolisp:http-handler` (LispNames + PackageRegistry
   + introspection list; a compile-time directive like `wasm-export`, a no-op /
   error on interpreter+JVM, or better: on interpreter/JVM run a tiny embedded
   HTTP server that calls the handler so `rontolisp app.lisp` also serves —
   decide; the WASM/component path is the real target).
2. **Component export**: teach `WasmComponentBuilder`/`ComponentWriter` to
   *export* `wasi:http/incoming-handler@0.2.x` with the canonical signature
   `handle(request: incoming-request, response-out: response-outparam)`.
3. **Adapter glue**: in the http adapter, read method/path/headers/body from the
   `incoming-request` (+ its `incoming-body` input-stream) into the core
   module's plist representation (strings in linear memory, same helpers fetch
   uses), invoke the exported core handler function, then build an
   `outgoing-response` (status + fields + outgoing-body output-stream) from the
   returned plist and `response-out.set`.
4. **World/version**: incoming-handler is WASI **0.2** (same world as fetch), so
   fetch + http-handler CAN coexist in one component (unlike tcp 0.3). Confirm
   the 0.2/0.3 mixing rule in `WasmExprCompiler` still forbids only 0.2+0.3.
5. **Interpreter/JVM fallback**: simplest is an embedded `com.sun.net.httpserver`
   (interpreter) / hand-assembled server (JVM) that adapts the same handler
   plist — or, to start, make `http-handler` interpreter/JVM = run a blocking
   server built on the existing tcp built-ins in Lisp. Decide scope; the WASM
   component is the headline.

## Spin specifics

- Spin v2+ runs components exporting `wasi:http/incoming-handler@0.2.0`. A
  `spin.toml` with `[[trigger.http]]` + `component.source = "app.wasm"` should
  run our `--component` output directly.
- Verify locally: `spin up` (needs `spin` on PATH) and, in parallel,
  `wasmtime serve -S cli app.wasm` then `curl localhost:8080/`.
- Pin the tested Spin + wasmtime versions in the .kb page once working.

## Verification checklist (follow the four-backend + native + docs workflow)

- New `WasmLispCompilerTest` / integration test compiling a handler component
  and (if wasmtime available) `wasmtime serve` + curl round trip.
- `examples/http-hello-component.lisp` (or extend http-hello) as the demo, with
  a `spin.toml`; header comment carries the `spin up` / `wasmtime serve` cmds.
- Docs: `doc/en+ja` reference page for `rontolisp:http-handler`, catalog entry,
  guide section; `.kb/` page (or extend `.kb/fetch-http.md`).
- Native E2E only if introspection/ci-spec changes.

Related: `.todo/52-wasi-keyvalue.md` (the next component to add; pairs with the
kv-server example). Both came out of the 2026-07 "interesting wasmtime
components" discussion.
