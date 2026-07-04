# Support rontolisp:fetch inside a serve component (outbound HTTP from a handler)

## Problem

A handler served via `rontolisp:http-handler` + `--component` cannot make
outgoing requests with `rontolisp:fetch`. Verified 2026-07-04: a program
combining `http-handler` and `fetch` **compiles without error** under
`--component`, but the produced component fails to instantiate:

```
$ wasmtime serve -W gc=y -S http=y fetch-serve.wasm
Error: failed to parse WebAssembly module
Caused by:
    missing module instantiation argument named `sock` (at offset 0xdca4)
```

The cause: when the program uses fetch, the core module expects the http
adapter machinery (the `sock`/`http` instantiation arguments that
`WasmComponentBuilder.buildHttp` wires for `wasmtime run` components), but
`WasmServeComponentBuilder.buildServe` only wires mem + core +
`adapter-serve.wasm`.

On the interpreter and JVM backends fetch inside a handler works fine (both
sides use `java.net.http.HttpClient`), so proxy-style programs are currently
interpreter/JVM-only.

## Plan

Two steps:

1. **Short term (fail fast)**: make `fetch` + serve mode a clear compile-time
   error ("fetch inside a serve component is not supported yet") instead of
   emitting a component that cannot instantiate.
2. **Real support**: teach `WasmServeComponentBuilder.buildServe` to include
   the http adapter blob set (`adapter-http.wasm` / `mem-http.wasm` /
   `import-block-http.bin`) when the program uses fetch, mirroring
   `WasmComponentBuilder.build(core, usesHttp)`. The serve world already runs
   on `wasi:http@0.2`, and the proxy world includes the outgoing-handler side,
   so no new host requirements are expected beyond what `adapter-http.wat`
   already uses. Mind the interaction with `.todo/49` (fetch + sockets in one
   component) and `.todo/02` (fetch on wasi:http 0.3).

## Goal

Enable the classic proxy/aggregator sample shapes from the Spin/wasmCloud
ecosystems (e.g. wasmCloud's dog-fetcher, weather-proxy tutorials) as
rontolisp examples that run on all three serving backends, not just
interpreter/JVM.
