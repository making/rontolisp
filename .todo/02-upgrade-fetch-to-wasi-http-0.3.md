# Upgrade `rontolisp:fetch` from wasi:http@0.2 to async wasi:http@0.3

**Status:** blocked on upstream. Tracked here so the hybrid arrangement is not forgotten.

## Why it is on 0.2 today

The `--component` output is a native WASI 0.3 (Preview 3) component for every
interface area **except HTTP**. `rontolisp:fetch` in component mode is a
**hybrid**: the base I/O (cli / clocks / filesystem / random) is WASI 0.3, but a
fetch program additionally imports **`wasi:http@0.2` + `wasi:io@0.2`
(poll/streams)**, driven synchronously by `pollable.block` in `adapter-http-client.wat`.

This is because **async `wasi:http@0.3` does not exist upstream yet**:

- The `WebAssembly/wasi-http` repo's `v0.3.0-rc-*` tags and `main` are all still
  packaged as `wasi:http@0.2.7` / `0.2.8`, using `wasi:io/streams@0.2`
  (`input-stream`/`output-stream` resources), `wasi:io/poll`, and a
  resource-based `future-incoming-response` — **not** the component-model
  `stream<u8>` / `future<T>` async ABI.
- `wasmtime` 46 only hosts `wasi:http@0.2` (`-S help` says "wasi-http 0.2
  `fields` resource").

So there is no async http ABI to target and no host to run it. Verified
2026-06-25.

## Why this is a clean, isolated stopgap (not a dead end)

The rontolisp **core never sees a WASI http version**: it imports a bespoke
two-function seam (`http.fetch-start(8 x i32)` starts the request and returns the
promise handle; `http.fetch-await(6 x i32)` blocks and reads the response --
`WasmFetchCompiler` / `WasmAwaitCompiler` / `WasmFetchRuntimeBuilder`), exactly
like the version-agnostic preview1 file-I/O seam. The promise handle handed to
Lisp code is the wasi:http `future-incoming-response` handle itself.
Only `adapter-http-client.wat` + `import-block-http-client.bin` +
`WasmComponentBuilder.buildHttp` bind to a specific WASI http version — the same
layer we already rewrote when migrating the base I/O from 0.2 to 0.3.

When async `wasi:http@0.3` ships upstream, the `wasi:io@0.2` "island" disappears
entirely and the component becomes uniformly 0.3.

## What to do when upstream ships async wasi:http@0.3

1. Vendor the async `wasi:http@0.3.0` WIT (+ any deps it pulls) under
   `src/wasm-component/deps/http`, and update `uni-http-client.wit`.
2. Rewrite the http portion of `adapter-http-client.wat`: replace the
   `pollable.block` polling + `wasi:io` stream resources with the async canonical
   built-ins (`future.read` / `stream.read` / `stream.write`), mirroring how
   `adapter.wat` drives the base I/O. The `outgoing-handler.handle` becomes a
   `future<incoming-response>`; the request/response bodies become `stream<u8>`.
   Preserve the `fetch-start` / `fetch-await` split (the promise API): start
   sends the request and returns the future/readable handle, await blocks on it.
3. Drop the `wasi:io/poll` + `wasi:io/streams` imports from `uni-http-client.wit`;
   regenerate `import-block-http-client.bin` via `regen.sh`.
4. Re-derive the `WasmComponentBuilder.buildHttp` wiring constants
   (import-instance indices, lowered-func indices, canonical options) from a
   fresh `wasm-tools dump` of the regenerated reference.
5. Leave the core seam, `WasmFetchCompiler`, `WasmAwaitCompiler`, and
   `WasmFetchRuntimeBuilder` untouched — only
   `WasmFetchCompiler.methodDiscriminant` may need its variant discriminants
   re-checked against the 0.3 http `method` enum.
6. Drop the `-S http=y` requirement note once the host no longer needs it (still
   needed while on 0.2).
