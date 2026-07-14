# serve's HTTP glue through WIT (`wit-export` learns interface exports)

**Status:** open, unstarted. Medium-large. Blocked by `.todo/133` (variant/result
parameters). Do it AFTER `.todo/136` — same machinery, bigger blob, lower risk there.

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

**1. Rich parameters (`.todo/133`).** The single call that sends a response takes a
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

## The payoff beyond the blob

- **The request plist stops being hand-shaped.** Today `rontolisp:http-handler`'s request
  plist is built one way in the serve adapter (WAT), another way on the JVM, another on the
  interpreter, and nothing checks they agree. Derived from the WIT `record`, there is one
  shape by construction.
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
