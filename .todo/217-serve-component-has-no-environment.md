# 217 -- a served component cannot read the environment (`uiop:getenv` -> nil)

## What is wrong

`uiop:getenv` works on the interpreter, the JVM, WASM Preview 1 and the base WASI
0.3 component -- but a `rontolisp:http-handler` program compiled with
`--component` returns **nil for every variable**, silently. `wasmtime serve
--env DATABASE_URL=...` and `-S inherit-env=y` make no difference: the value has
nowhere to enter.

Found while moving `examples/db/*.lisp` off hardcoded connection details and onto
`(database-url-parts (uiop:getenv "DATABASE_URL"))`. Three of the four backends
took it; `postgres-web.lisp` under `wasmtime serve` stops on `no database URL
given` -- and, an uncaught `error` on WASM being a bare `unreachable`, it stops
without printing why.

## Why

Two deliberate pieces, both correct on their own terms:

- `src/wasm-component/adapter-http-server-p1.wat` implements
  `environ_sizes_get`/`environ_get` as **a zero-entry environment** -- its own
  comment says "the service world has no wasi:cli/environment".
- `WasmServeComponentBuilder`'s import block accordingly omits
  `wasi:cli/environment@0.3.0`. `wasm-tools component wit app.wasm` on a serve
  build lists `wasi:cli/stdout`/`stderr` and no `environment`, where a base
  component lists it.

The WASI 0.3 *service* world genuinely does not carry `wasi:cli/environment`.
But rontolisp's serve components already reach past that world -- the socket
surface is why `examples/db/postgres-web.lisp` documents `-S cli=y` -- so
"the world does not have it" is a reason for the default, not a reason for the
gap to be permanent.

## The essential fix, and the decision it needs

Mechanically it is the base adapter's work, ported:

1. `adapter-http-server-p1.wat`: `(import "w" "get-environment" ...)`, plus the
   69-line `environ_sizes_get`/`environ_get` pair that decodes
   `list<tuple<string,string>>` into a preview1 `KEY=VALUE\0` buffer -- copy it
   from `adapter.wat` (lines ~276-344), including the 0x50020 scratch layout.
   Rebuild the `.wasm` through `src/wasm-component/regen.sh`.
2. `WasmServeComponentBuilder`: add the interface to the import block, alias
   `get-environment`, `canonLowerMemoryReallocUtf8` it, and name it in the `w`
   core-instance list. The serve variant's core func indices shift, so audit
   them the way `WasmComponentBuilder`'s "shifting everything after
   wasi:cli/environment by one" comment describes.
3. Pin it: a serve leg in `WasmLispCompilerIntegrationTest` next to
   `componentGetenvFromWasiEnvironment`, and re-point `.kb/wasi-component.md`
   and `.kb/time-environment-builtins.md` (the latter currently claims the
   clock/environ imports "exist in both modes", which is true of run mode only).

**The decision this needs first**: an unconditional import narrows what can host
a served component. wasmCloud is the case to check -- if `wash` does not supply
`wasi:cli/environment@0.3.0`, adding it to every serve build turns a working
deployment into an instantiation failure, and `examples/db/postgres-web.lisp`
documents wasmCloud as a supported host. The likely answer is therefore a
**conditional** import: emit the interface (and the real adapter path) only when
the program actually calls `uiop:getenv`, which the existing tree-shaking already
knows how to decide. Confirm the wasmCloud behavior before picking.

## Non-goals

Nothing here changes the run-mode component, Preview 1, the JVM or the
interpreter -- all four already read the environment correctly.
