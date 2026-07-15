# serve's HTTP glue through WIT (`wit-export` learns interface exports)

**Status: DONE 2026-07-15 (session 3).** Plain-serve MUST committed `bc29daa` (session 2);
step 6 (serve+fetch collapse) done + verified in session 3, committed alongside this update.
Serve AND serve+fetch are now `serve.lisp` + `fetch.lisp` over wit-imported wasi:http, one
`WasmServeComponentBuilder.build` (narrow/wide `ServeBlock`); all WAT serve/fetch adapters
deleted. See "SESSION STATE (session 3)" below. Only leftover = the block-deletion "later
refinement" (structurally deferred -- a minimal preview1-bridge block is impossible).

**Historical status (pre-session-2):** open, unstarted, DEFERRED behind `.todo/136` (user
decision 2026-07-14, after the survey below). **UNBLOCKED 2026-07-14**: `.todo/133` landed, so
capability 1 below is
done — a `result` argument crosses (as the `(:ok . V)` / `(:error . E)` envelope), and so
does `error-code`, whose cases carry `record`s and `option<string>`s (todo 133 had to go
past its own "flat payloads only" plan for exactly this). Capability 2 (`wit-export` learns
INTERFACE exports) is now the only blocker. Do it AFTER `.todo/136` — same machinery,
bigger blob, lower risk there, and 136 pays for the shared prerequisite (resource `drop`
binding, below) and proves that Lisp can drive `wasi:http` over `canon lower` on the client
half, where the oracle is a plain fetch test rather than a server.

This is `.todo/124`'s stated follow-on prize ("`rontolisp:http-handler` becomes a program
implementing the `wasi:http/incoming-handler` world, with the request plist **derived** from
the WIT `record` instead of hand-shaped differently per backend"), made concrete.

## What goes away — and what does NOT

| artifact | size | fate |
|---|---|---|
| `adapter-http-server.wasm` | 1.9 KB | **deleted** — the HTTP glue becomes Lisp |
| `import-block-http-server.bin` + `-client` | 2.9 + 4.4 KB | **deleted** (generated from the WIT) |
| `adapter-http-server-p1.wasm` | 1.1 KB | **STAYS** |

The preview1 bridge stays, and this is the thing to understand before starting: a serve
component has no `wasi:cli/run`, so there is nowhere to hang the WASI 0.3 adapter, and the
rontolisp core still imports `wasi_snapshot_preview1` for print / clocks / random. That
bridge is a miniature of the base adapter and is **structurally not externalizable** (the
core's Preview-1-identical import layout is what every `FUNC_*` constant rests on). Do not
plan to delete it.

So the blob win here is smaller than `.todo/136`'s. The *design* win is what justifies it.

## The two capabilities this needs

**1. Rich parameters — DONE (`.todo/133`, landed).** The single call that sends a response takes a
`result` argument:

```wit
set: static func(param: response-outparam,
                 response: result<outgoing-response, error-code>);
```

**2. `wit-export` must learn INTERFACE exports.** Today it implements plain function
exports of a world and rejects an interface export outright ("wit-export implements plain
function exports only"). But `incoming-handler` is an interface:

```wit
interface incoming-handler {
  use types.{incoming-request, response-outparam};
  handle: func(request: incoming-request, response-out: response-outparam);
}
```

The **lift itself is the easiest one there is** — two `i32` handles in, nothing out, no
canonical options. What is new is the wiring around it:

- exporting an *instance* (already done for `wasi:cli/run`, `ComponentWriter.exportInstance`);
- the exported func's type must reference the **imported** resource types
  (`own<incoming-request>`), i.e. alias them out of the imported `wasi:http/types` instance
  and use those type indices in the lifted functype. Every encoder for this exists
  (`WitComponentTypeEncoder`, `aliasInstanceType`) — none of it has been used on the LIFT
  side before.

### Survey, 2026-07-14: the byte encoders are ALL there — read this before planning

The exact component this todo wants is **already emitted today, hardcoded**:
`WasmServeComponentBuilder.build` (:132-274) aliases `incoming-request` /
`response-outparam` out of the imported `wasi:http/types` instance
(`aliasInstanceType(INST_HTTP_TYPES, ...)` :151-154), wraps them in `definedOwn`, builds
`funcTypeParamsNoResult(["request","response-out"], [own,own])` (:180-184), `canonLift`s
the adapter's core `serve` func against it with NO canonical options (:265-266), and
`componentInstanceFromFunc("handle", ...)` + `exportInstance("wasi:http/incoming-handler@0.2.0", ...)`
(:269-272). So the gap is **generalization + front-end**, in six named places:

- **(a) no user-driven exported instance.** `appendFuncExports` only ever emits a bare
  `exportFunc` (`WasmComponentBuilder:296`). And `componentInstanceFromFunc` hardcodes a
  count of 1 (`ComponentWriter:636`) — an interface with >1 function needs a plural encoder.
- **(b) `wasm-export` cannot carry a handle param.** `FuncExport.paramValTypes` is a
  `List<Integer>` of `VT_*` primitive codes, and `funcTypeScalars`/`asyncFuncTypeScalars`
  write each as `writeSignedLeb128(code - 0x80)` — the negative primitive form only. An
  `own<T>` param is a NON-negative component type index and is unrepresentable. The general
  encoder that would work already exists (`ComponentWriter.funcTypeOf`, pre-encoded
  valtypes) but no export path calls it. **So the `wit-export` -> `wasm-export` lowering
  cannot express this**: the interface export needs its own internal form, the mirror of the
  import side's `(rontolisp::%component-import "iface-id" "<wit text>" ("member" "lisp-name") ...)`.
- **(c) the front-end refuses handles.** `WitExportDirective.designator` (:377-403) is an
  independent hard-coded 5-type table (`s32`/`s64`/`f64`/`bool`/`string`) that never
  consults `WitTypeMapper`; `Rep.HANDLE` exists and is unreachable from it.
- **(d) the core wrapper has no handle type.** `WasmExportCompiler.componentValType`
  (:484-495) throws on anything else, so the wrapper's flat core signature cannot be
  `(i32, i32)`.
- **(e) no LIFT-side marshalling at all.** `WasmComponentImportCompiler.emitLower*Param`
  turns a Lisp value into flats for an IMPORT; there is no counterpart that turns an
  incoming flat into a Lisp value inside an exported wrapper. For `handle` this is one
  `ref.i31` — but it is still a new class (`WasmComponentExportCompiler`).
- **(f) `canon resource.drop` is unreachable from Lisp — THE SHARED PREREQUISITE.**
  `ComponentWriter.canonResourceDrop` exists but is called only by the hardcoded serve /
  http-client builders (`WasmServeComponentBuilder:210-217` pre-wires four drops).
  `rontolisp:wit-import` binds an interface's funcs / constructors / methods / statics and
  **no drop**, so a Lisp handler receiving an `own<incoming-request>` could never release
  it. `.todo/136` needs exactly the same thing (fetch drops request/fields/body/stream/
  future/response), so **it is 136's to build.**

A type ascription on the exported instance is NOT needed (`exportInstance` writes `0x00` =
none), which is precisely how the hardcoded serve export gets away with referencing
imported resource types structurally. Do not go build an outer-alias (`0x02`)
instance-type declaration encoder for this; nothing needs it.

## The payoff beyond the blob

- **The request plist stops being hand-shaped — but be precise about what is actually
  broken (survey, 2026-07-14).** The premise as first written was too strong: the plist is
  NOT built in WAT. `HttpHandlerInliner` (`cli`) splices synthesized `%http-request` /
  `%http-encode` **Lisp** defuns, and the WAT adapter only hands the core three flat
  strings through a `:string*3 -> :string` `wasm-export` carrying `"<status>\n<body>"`. So
  the five keys (`:method` `:path` `:query` `:headers` `:body`) and the `?`-split agree
  across all three backends already. The REAL divergence is exactly one cell: **`:headers`
  is always `nil` on WASM, both ways** — request headers unreadable, response headers
  silently dropped — because `[method]incoming-request.headers` and `fields.append` are
  ABSENT from the serve import block, so it is a blob-set change, not a codegen tweak. That
  is what "derived from the WIT" buys, and it is the honest headline: **serve on WASM grows
  headers.** (Two more WASM-only quirks to preserve or fix deliberately: the request body
  is silently TRUNCATED at 64 KiB, `adapter-http-server.wat:166`, and an unknown method
  variant becomes `"GET"`.)
- **`wit-export` becomes able to implement any interface-exporting world**, not just
  `wasi:http/incoming-handler` — which is what `wasmCloud` and every actor-shaped host
  wants (`.todo/53`).
- If serve stops being a special blob shape, **`.todo/134`'s restriction dissolves**: a
  served component importing `wasi:keyvalue` is then just a component with two imports and
  one export, with no special case anywhere.

## Definition of done

- `rontolisp:http-handler` compiles to a component whose `wasi:http/incoming-handler`
  export is lifted from a Lisp-written handler over wit-imported `wasi:http/types`, with no
  `adapter-http-server.wasm`.
- Output parity with the current serve adapter across the existing serve E2E and
  `examples/net/http-handler*` (the oracle — do not delete the blob until it is green).
- `wasmtime serve` still runs it with the same flags; the fetch-inside-serve variant
  (`http-server-client`) collapses into "serve + the fetch library" (`.todo/136`).
- `.kb/wasi-component.md` + `.kb/fetch-http.md` + `.kb/wit.md`; docs en/ja.

## Implementation plan (session 2026-07-15, after the second survey)

The second survey (six parallel readers) confirmed the six gap sites and, crucially,
**simplified the codegen**: the core `handle` wrapper is byte-identical to a plain
`wasm-export` with two `:int` params and a `:void` return, because a handle box/unbox
(`ref.i31` / `i31.get_s`) is exactly `WasmExportCompiler`'s `:int` box/unbox. So there is
**no new `WasmComponentExportCompiler` marshalling** for the serve case; the only new thing
is at the COMPONENT level (the exported `handle`'s functype must reference
`own<incoming-request>` / `own<response-outparam>` aliased from the wit-imported
`wasi:http/types` instance, and it is exported as an INSTANCE under
`wasi:http/incoming-handler@0.2.0`, not a bare func). The byte encoders for all of that
already exist (`aliasInstanceType` / `definedOwn` / `funcTypeParamsNoResult` / `canonLift` /
`componentInstanceFromFunc` / `exportInstance`) and are exercised by the hardcoded
`WasmServeComponentBuilder` today.

Target serve component shape (no serve adapter):
```
core 0 = mem module
core 1 = preview1 bridge (adapter-http-server-p1.wasm)  -- STAYS
core 2 = rontolisp core, serve mode:
           - wit-imports wasi:io + wasi:http/types (incoming half) via serve.lisp
             (canon-lowered through appendUserImports, the fetch.lisp pattern)
           - exports %serve-handle (wasm-export :int :int -> :void), the Lisp glue
           - imports wasi_snapshot_preview1 from the bridge (print/clock/random)
interface export:
   alias incoming-request / response-outparam out of the wit-imported wasi:http/types
   instance; own<>; funcTypeParamsNoResult; canonLift the core %serve-handle; instance;
   exportInstance "wasi:http/incoming-handler@0.2.0"
```

Stages (each verified before the next; the WAT/import-block blobs are the byte-identity
oracle -- do NOT delete them until the new path is green on `wasmtime serve`):

1. **serve.lisp + eval/ServeLibrary** (the fetch.lisp mirror): wit-imports
   `wasi:http/types@0.2.0` + `wasi:io/streams@0.2.0` (+ error/poll as needed) into package
   `%serve-http` etc., defines `%serve-handle(request response-out)` reproducing
   `adapter-http-server.wat`'s algorithm (read method/path/headers/body, call the user
   handler, build+set the response, write the body in <=4096 chunks, drops in child-first
   order). **NEW: reads request headers and writes response headers** (the WAT drops them) --
   "serve on WASM grows headers". Spliced only on `--component` serve.
2. **wit-export interface exports** (front-end): `WitExportDirective` stops refusing an
   `ExportRef`/`use`d interface, resolves its functions, and lowers each to an internal
   interface-export form (mirror of `%component-import`); `designator` grows a `Rep.HANDLE`
   arm. `RontoLispCli` drops the `serve && witWorld` mutual-exclusion guard.
3. **export-side component wiring**: generalize the interface-export lift so the serve
   builder lifts the core's `%serve-handle` wasm-export (not the deleted adapter's `serve`),
   building the `own<>` funcType from the wit-imported instance.
4. **reshape the serve import block + rewrite WasmServeComponentBuilder**: wasi:http/io move
   to user imports (appendUserImports); a new minimal fixed import block provides ONLY the
   preview1 bridge's WASI (random/clocks/cli). Blob regen (`src/wasm-component/regen.sh`,
   re-derive indices from `wasm-tools dump`). Drop the serve adapter.
5. **verify plain serve** on `wasmtime serve` (GET/POST/headers).
6. **collapse serve+fetch** into serve + fetch.lisp (both now wit-import wasi:http, no
   collision); delete `adapter-http-server.wat` + the import blocks + the serve+fetch adapter.
7. byte-identity of unaffected artifacts, full test suite, native E2E, docs en/ja, then
   confirm commit.

## SESSION STATE (2026-07-15, session 2) -- PLAIN-SERVE MUST DONE

Steps 1-5 + 7 (minus the deferred collapse) are DONE this session:
- `WasmServeComponentBuilder.build` rewritten: no serve adapter. `lowerServeIoFromBlock`
  lowers serve.lisp's wasi:io/http calls FROM the block (alias-out + `canon lower`,
  `needsMemory` picking options, io/error skipped); the core's `handle` wasm-export is lifted
  directly against `own<incoming-request>`/`own<response-outparam>`. Additional user imports
  (keyvalue) partition off via `WasmServeComponentBuilder.additionalImports` and ride
  `appendUserImports`; a `ServeIo` record threads the next comp-func/core-func/type/core-inst
  cursors so nothing is hardcoded.
- `RontoLispCli` wired: `ServeLibrary.process` for `--component` plain serve, gated off for
  serve+fetch (`FetchLibrary.referencesFetch`) and for a `wit-export` world.
- `WasmLispCompiler` serve path passes only `additionalImports` to `WitEmitter` (else the
  fixed surface's packages get double-declared in the emitted WIT -- a bug found + fixed).
- Verified on `wasmtime serve -W gc=y -W exceptions=y`: GET/POST/query, >4 KiB chunking,
  **request headers read + response headers written**, and serve+keyvalue `/hits 42` against
  wasmtime's real `-S keyvalue=y` store.
- Tests routed to the ServeLibrary path (integration `compileServeComponent` CLI helper +
  the WasmComponentImportCompiler/WasmExportCompiler/WitOracle serve unit tests); the three
  serve BACKEND run commands gained `-W exceptions=y` (serve.lisp is EH-mode). WIT fixture +
  `WasiWitDefinitions` regen'd (http-server gains headers/fields.append/entries). Full suite
  GREEN (3677, was 3 backend-flag failures, fixed). Docs en/ja + `.kb` updated.

STEP 6 DONE in session 3 (below). Serve+fetch no longer rides the WAT adapter.

## SESSION STATE (2026-07-15, session 3) -- STEP 6 DONE

serve+fetch collapsed into `serve.lisp` + `fetch.lisp`. Both are spliced (CLI:
`FetchLibrary.process` -- its `serve` gate REMOVED, 3rd param gone -- then
`ServeLibrary.process`); `HttpHandlerInliner.inline`/`wrapperForms` DELETED (the class is now
just the `usesHttpHandler` detector). `WasmServeComponentBuilder.build` selects a `ServeBlock`
descriptor (NARROW plain / WIDE serve+fetch, `usesWideBlock` = a fetch-only iface present);
`lowerServeIoFromBlock` generalized (parameterized by the block, dedups bound funcs by field /
drops by resource, returns a `coreInstanceOf` map). `buildHttp` + the WAT adapters
`adapter-http-server.wat` + `adapter-http-server-client-p1.wat` (+ their `.wasm`) DELETED; the
preview1 bridge `adapter-http-server-p1.wasm` is shared by both shapes (once fetch is
fetch.lisp the core imports no `http` function). Wide block `import-block-http-server-client.bin`
regen'd to add `[method]incoming-request.headers`.

Two overlaps solved:
1. **iface merge** -- `WasmComponentImportCompiler.mergeByIface` (new) folds serve.lisp's and
   fetch.lisp's duplicate `%component-import`s of the same interface into one (each `%`-package
   keeps its own Lisp wrappers); runs before `inDependencyOrder`.
2. **core-import dedup** -- the component model FORBIDS a core module importing one
   (module,field) twice (verified: `wasm-tools component new` auto-dedups but hand-assembly does
   not). `WasmLispCompiler` gained an import-slot pass: the two libraries' wrappers stay distinct
   defuns but SHARE one core import (one placeholder ordinal), keyed by (module,field).
   `emitHttpImport` REMOVED (fetch-start/await = permanent trap stubs).

KEY BUG fixed: `WasmExprCompiler` dispatched `rontolisp:fetch` to the builtin
(`WasmFetchCompiler` -> `FUNC_FETCH_START` trap stub) whenever `ctx.serve`; guard
`component && !serve` -> `component` so serve+fetch falls through to fetch.lisp's defun.

Verified on wasmtime 46: plain serve + response headers, serve+fetch proxy round-trip
("proxied backend /up 200"), serve+fetch POST body, non-serve fetch. Full suite GREEN
(3674/0/0, 4 skipped) incl. the Docker serve+fetch round-trip; native image + CiSpecE2eTest
(820/0/0) green; web profile compile OK; javadoc clean (Version-class exception only). Run
serve+fetch with `wasmtime serve -W gc=y -W exceptions=y -S http=y`. Fixtures
(`http-server-client.wit` + `WasiWitDefinitions.httpServerClient`) regen'd; docs en/ja + .kb +
all serve example headers updated (every serve command needs `-W exceptions=y` -- a plain-serve
`wasmtime serve -W gc=y app.wasm` FAILS to parse; bc29daa's plain-serve commit missed the
example headers, fixed here).

STILL OPEN (deferred "later refinement", NOT this todo's blocker): delete the serve import
blocks (`import-block-http-server*.bin`) themselves -- structurally hard, because a minimal
preview1-bridge-only block is impossible (wasi:cli/stdout implicitly uses wasi:io/streams'
output-stream, which collides with serve.lisp's io/streams user import). The blocks are KEPT
and the http/io surface is lowered FROM them. TCP externalization (sockets over Lisp, like
fetch) was CONSIDERED and HELD 2026-07-15 (user aims for full WASI 0.3; the current sockets
adapter is intentionally 0.3-native, and a Lisp sockets.lisp would need either the async
stream/future canonical ABI in canon-lower, or a downgrade to wasi:sockets@0.2).

## SESSION STATE (2026-07-15) -- WHERE THIS STANDS, for the next session

DONE + in the tree (tree GREEN, always-on WIT tests 40/0):
- `src/main/resources/am/ik/rontolisp/eval/serve.lisp` -- the Lisp glue (fetch.lisp mirror
  + WAT algorithm + request/response HEADERS, which the WAT dropped). References these
  wasi:http/types members (all cross --component): incoming-request-{method,path-with-query,
  headers,consume,drop}, incoming-body-{stream,drop}, input-stream-{blocking-read,drop},
  output-stream-{blocking-write-and-flush,drop}, fields-{entries,drop,new,append},
  outgoing-response-{new,set-status-code,body}, outgoing-body-{write,finish},
  response-outparam-set. Imports wasi:io/error + wasi:io/streams + wasi:http/types.
- `src/main/java/am/ik/rontolisp/eval/ServeLibrary.java` -- the splice (FetchLibrary mirror):
  lowers serve.lisp's wit-imports itself, synthesizes `(defun %serve-dispatch (r) (HANDLER r))`
  + `(rontolisp:wasm-export '%serve-handle :as "handle" :params '(:int :int) :returns :void)`.
  NOT yet wired into RontoLispCli.
- `src/wasm-component/core-http-server.wat` EXPANDED (+incoming-request.headers, +fields.append,
  +fields.entries, +[resource-drop]fields) and `import-block-http-server.bin` REGEN'D
  (2966->3226 bytes). OUTER component-type space UNCHANGED (0-12; T_INPUT_STREAM=4,
  T_OUTPUT_STREAM=5; next free 13); import instances 0-7 unchanged.

KEY DESIGN FACTS decided this session (do NOT re-derive):
- The core `handle` wrapper == a plain `wasm-export :int :int -> :void` (handle box/unbox ==
  :int box/unbox). NO new WasmComponentExportCompiler for serve. NO general wit-export
  interface-export front-end needed for the MUST -- the serve builder lifts the core's
  `handle` wrapper directly, like it lifts the adapter's `serve` today.
- A minimal preview1-bridge block (random/clocks/cli only) is IMPOSSIBLE: wasi:cli/stdout
  implicitly imports wasi:io/streams' output-stream (nominal), which collides with serve.lisp's
  io/streams user import. => REUSE the (expanded) full block and lower http/io FROM IT for the
  core (NOT appendUserImports, which would double-import). This is why the block is kept, not
  deleted (a documented deviation from the table above -- the deletion is a later refinement).
- INIT: HEAP_PTR is seeded by an ACTIVE DATA SEGMENT at instantiation (WasmLispCompiler
  :1320,:2689), NOT by FUNC_START. So a STATELESS handler works with NO init trigger. The
  init-once (call FUNC_START from the handle wrapper, guarded like the old adapter's $inited)
  is a FOLLOW-UP for stateful handlers.

NEXT STEP = rewrite `WasmServeComponentBuilder.build` (plain serve, imports =
io/error+io/streams+http/types). FULL layout (component funcs / core funcs / core instances /
component types):

- `writeRaw(IMPORT_BLOCK_HTTP_SERVER)`  [types 0-12, import instances 0-7]
- core modules: 0=`MEM_MODULE`, 1=`ADAPTER_HTTP_SERVER_P1` (bridge), 2=coreModule. NO serve
  adapter.
- core inst 0 = mem instantiate; alias mem `memory`(0) + `cabi_realloc` (core func 0)
- bridge WASI: alias+lower 6 -> component funcs 0-5, core funcs 1-6:
  - `get-random-u64`(INST_RANDOM=4)=canonLower -> core 1
  - wall `now`(INST_WALL_CLOCK=5)=canonLowerMemory(_,0) -> core 2
  - mono `now`(INST_MONO_CLOCK=0)=canonLower -> core 3
  - `get-stdout`(INST_CLI_STDOUT=6)=canonLower -> core 4
  - `get-stderr`(INST_CLI_STDERR=7)=canonLower -> core 5
  - `[method]output-stream.blocking-write-and-flush`(INST_IO_STREAMS=2)=canonLowerMemory(_,0) -> core 6
- core inst 1 = bridge "w": `coreInstanceFromFuncs([rand-u64,wall-now,mono-now,get-stdout,
  get-stderr,io-write],[1,2,3,4,5,6])`
- core inst 2 = bridge instantiate(module 1,[mem,w],[0,1])
- serve.lisp http/io lowering FROM BLOCK (a helper): for the http/types(inst3) + io/streams(inst2)
  Imports, per `decl`: `aliasInstanceFunc(blockInst, decl.field())` + `canonLower`
  (`WasmComponentImportCompiler.needsMemory(decl)` ? `canonLowerMemoryReallocUtf8(f,0,0)` :
  `canonLower(f)`); per `drop`: project the resource (`aliasInstanceType`, ONCE into a shared
  map) + `canonResourceDrop`; build one `coreInstanceFromFuncs` per iface, exports keyed by
  `decl.field()` / `drop.field()`. io/error is skipped (0 decls). -> component funcs 6.., core
  funcs 7.. ; the core instances land at 3 (io/streams), 4 (http/types).
- core inst 5 = coreModule instantiate(module 2,[mem, wasi_snapshot_preview1,
  "wasi:io/streams@0.2.0", "wasi:http/types@0.2.0"],[0,2,3,4]). (module names = the canonical
  iface ids the core imports under.)
- Interface export: project `incoming-request` + `response-outparam` from block inst 3 (REUSE
  the drop projection of incoming-request); `definedOwn`x2; `funcTypeParamsNoResult(
  [request,response-out],[own_req,own_resp])`; `aliasCoreFunc(core inst 5, "handle")`;
  `canonLift(handle core func, handleFuncType)`; `componentInstanceFromFunc("handle", handle
  comp func)`; `exportInstance("wasi:http/incoming-handler@0.2.0", inst)`. Component types
  13.. = the resource projections + the two own<> + the handle funcType. The exported handle
  instance = component instance 8 (no user imports).

Then wire `ServeLibrary.process` into `RontoLispCli` (replace the `HttpHandlerInliner.inline`
call for --component serve; splice at the pre-`UserMacroExpander` point, ~line 279, so
serve.lisp's cond/handler-case/let* expand). The `serve && witWorld` guard is unaffected (serve
uses no user wit-export). Ensure WasmLispCompiler serve mode still collects the %component-import
imports and generates the "handle" wasm-export wrapper (both already happen; just verify).
Verify: `rontolisp h.lisp -o h.wasm --component && wasmtime serve -W gc=y h.wasm` + curl
GET/POST/headers. Then regen the WasiWitDefinitions http-server fixture (adds
headers/fields.append/entries to the emitted WIT) so WitOracleE2eTest stays consistent.
serve+fetch collapse (serve.lisp + fetch.lisp share the http/types import) + adapter-http-server.wat
deletion come last.

Handy API notes: `componentInstanceFromFunc` hardcodes a count of 1 (fine, incoming-handler has
one func). `exportInstance` writes no type ascription (0x00), which is why referencing the
imported resource types structurally is legal. Do NOT call `appendUserImports` for the three
serve built-ins (io/error/io/streams/http/types) -- they are in the block, and appendUserImports
would re-import them (collision); it IS still the path for any ADDITIONAL user wit-import
(serve+keyvalue, todo 134) -- those are not in the block. Toolchain present: wasmtime 46.0.1,
wasm-tools 1.252.0.
