# wasi:http incoming-handler — compile a rontolisp defun into an HTTP component (wasmtime serve / Spin)

Status: DONE for interpreter + WASM component (2026-07-04). `wasmtime serve`
works end to end; Spin is BLOCKED by Spin not enabling wasm-GC. JVM backend and
request/response HEADER marshalling remain (see "Follow-ups" at the bottom).
Created 2026-07-04.

## SHIPPED (commits 068e958, e3e8ffd, f8d6a5f, ea7601f, 215d384)

- Surface + interpreter server (virtual threads) + tests + docs.
- WASM serve component: `HttpHandlerInliner` (cli) rewrites the directive to a
  `%http-dispatch` wasm-export wrapper; `WasmLispCompiler` serve mode
  (`serve` flag, implies component) un-gates wasm-export + stubs WASI like
  --no-wasi while importing memory; `WasmServeComponentBuilder.buildServe` wires
  mem-http + core + `adapter-serve.wasm` and exports
  `wasi:http/incoming-handler@0.2.0`. Runs under
  `wasmtime serve -W gc=y -W component-model-async=y
  -W component-model-async-stackful=y -W component-model-more-async-builtins=y`.
  Verified: GET/POST, path/method/body marshalling, per-request status
  (200/404), 20+ requests; CI test
  `WasmLispCompilerIntegrationTest.httpHandlerServesUnderWasmtimeServe`.
- `ComponentWriter.funcTypeParamsNoResult` (the handle func type).
- Constants derived from `wasm-tools dump` of the `uni-serve` reference: import
  instances 0=io/error,1=io/streams,2=http/types; component types 0-5 (3=input-
  stream,4=output-stream), next free 6; canonical options per wasi func recorded
  in `WasmServeComponentBuilder`.

## Follow-ups

- **Spin**: `spin up` cannot run any rontolisp component -- Spin's embedded
  wasmtime does not enable the wasm-GC proposal. Revisit if Spin exposes a GC
  flag / runtime-config, or gains GC by default.
- **Headers**: v1 marshals only method/path/body (request) and status/body
  (response). Request/response headers are dropped. To add them, extend the
  `%http-dispatch` encoding (e.g. a length-prefixed header block) and the
  adapter-serve.wat request read / response write (the fetch adapter's
  `fields.entries` / `fields.append` loops are the template).
- **Allocator reset**: the mem-http `cabi_realloc` and the core `__ronto_alloc`
  bump pointers never reset, so a very long-lived server eventually exhausts the
  16-page memory. Reset per request (guarded) or grow memory.
- **JVM backend**: a generated class implementing an HttpHandlerSupport SAM
  (X509TrustManager-style) building the request plist + calling the compiled
  handler via `_invoke_1`. Currently a clean "requires --component"/interpreter
  error.

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
  The proof core-serve.wat / uni-serve.wit are in /tmp/serveprobe (ephemeral —
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
  (like `adapter-http.wat`). Collision-free for reasonable response sizes.
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
`adapter-serve.wat` (the proven marshalling calling `%http-dispatch`), and the
compiler integration. Best done as a focused session; iterate against
`wasmtime serve` + curl at each step.

## Remaining WASM work (multi-module, programmatic — native image cannot run wasm-tools)

1. `src/wasm-component/uni-serve.wit` (world `uni-serve`: import wasi:http/types@0.2
   + wasi:io/streams@0.2 + wasi:io/error@0.2; NO export in the wit — the
   incoming-handler export is emitted programmatically like `run`). `core-serve.wat`
   stub (imports the lowered funcs) for `regen.sh` to produce `import-block-serve.bin`.
2. `adapter-serve.wat`: shares `mem` with the rontolisp core; imports the lowered
   wasi:http/io funcs (under "w") AND the core's `%http-dispatch` (under "core");
   exports `serve(request i32, response_out i32)` doing the proof's marshalling but
   calling `%http-dispatch` for the body. Encoding: `%http-dispatch` returns
   `"<content-type>\n<body>"` (status hardcoded 200 in v1, OR add status line;
   check whether the WASM backend can format an integer -> string cheaply, else
   hardcode 200 and note it).
3. `WasmComponentBuilder.buildServe(core)`: mem + core + adapter-serve; derive
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
