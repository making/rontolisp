# serve's HTTP glue through WIT (`wit-export` learns interface exports)

**Status:** open, unstarted, DEFERRED behind `.todo/136` (user decision 2026-07-14, after
the survey below). **UNBLOCKED 2026-07-14**: `.todo/133` landed, so capability 1 below is
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
