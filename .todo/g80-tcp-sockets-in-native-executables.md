# g80. `rontolisp:tcp-*` in a `--native` executable

Difficulty: High

A `--native` executable is the Preview 1 module plus the runner (`.kb/native-output.md`), so
`tcp-connect` / `tcp-listen` / `tcp-accept` and the usocket shim are the Preview 1 call-time
errors there, while the interpreter, the JVM and `--component` have them
(`.kb/tcp-sockets.md`). A native program cannot reach PostgreSQL (cl-postgres), Redis, or
any other TCP peer. Serving HTTP does not need this: the runner owns the HTTP server
(`.kb/http-server.md`, "--native: the runner's server").

## What was tried (2026-10-24) and why it does not work as is

A runner host module `rltcp` (std::net: `listen`, `connect`, `accept`, `localPort`,
`peerAddress`, `setTimeout`, `lastError`; a socket numbered as a WASI descriptor from
`1 << 24` up) that SHADOWS `wasi_snapshot_preview1.fd_read` / `fd_write` / `fd_close`, so
the module's unchanged stream code reaches a socket through `fd_read` like a file. The
socket half worked in unit tests; the delegation for every OTHER descriptor did not:
calling the original Preview 1 `Func` from the shadowing host function
(`linker.get(...)` taken before shadowing, `TypedFunc::call(&mut caller, ...)`) traps with
`missing required memory export` -- a host-to-host call has no calling instance, and
wasmtime-wasi's p1 functions find the guest memory through the caller's exports. The p1
descriptor table and its functions are private to wasmtime-wasi 49, so the runner cannot
insert a socket into the table or call the implementation with the memory itself.

## Directions

1. **The component's shape, a second provider.** `WasmSocketsRewrite` already redirects every
   socket-capable stream call site onto the `%io-*` dispatch defuns `sockets.lisp` defines
   (`--component` only). A `runner-sockets.lisp` defining the same names and shapes over
   `rltcp` read/write/close imports (a handle table in Lisp, the chunked read buffer the
   component uses), spliced under `CompileFrontend.Options.runnerHosted()`, with the
   rewrite enabled there too. `StdinLibraryTest#theTwoDispatchSplicesDefineTheSameNamesAndShapes`
   is the pattern for pinning a third provider. Blocking reads are fine: Preview 1 async
   bodies run to their end. Likely the long-term shape: one dispatch layer, two hosts.
2. **A trampoline in the module.** Keep the shadowing, and have the module export
   functions that call the real p1 `fd_read` / `fd_write` / `fd_close` re-defined under
   other import names, so the delegation is a wasm call with the module as caller. Smaller,
   but every file read of a socket program then crosses host -> wasm -> host.

Either way: the stub choice (`RontoLispCli`: `rlrun-net` for a module importing `rltcp`
as for `rlhttp`), `WasmExprCompiler`'s Preview 1 tcp stub must step aside where the splice
defines the operators, `resource-config.json`, the docs (`doc/*/compiling/native.md`), and
a native leg for the loopback programs `WasmLispCompilerIntegrationTest#componentTcp*` run.
