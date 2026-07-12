# usocket shim follow-ups

The `usocket` package (usocket.lisp + UsocketLibrary + the with-* macro
expansions + the BuiltinSystems "usocket" ASDF hook) shipped covering the
Postmodern/cl-postgres surface (socket-connect + :element-type +
socket-stream) plus listeners, accessors and the with-* macros. See
`.kb/tcp-sockets.md` for the mechanics. Remaining gaps, none needed by
Postmodern:

## Tier 1: WASM component address accessors

`rontolisp:tcp-local-address` / `tcp-peer-address` / `tcp-peer-port` return
nil in component mode (WasmTcpCompiler drops the handle and pushes nil).
Wiring them for real means extending `adapter-sock.wat` with
`wasi:sockets` `local-address`/`remote-address` calls and new fixed import
indices (renumbering the core seam -- see `.kb/tcp-sockets.md` "The WASM core
seam"). Deliberately deferred: an unconditional compile error was NOT an
option (usocket.lisp splices whole, every defun body compiles eagerly).

## Tier 2: wait-for-input (single-socket degenerate form)

Many usocket consumers loop `wait-for-input` -> read. A lite version that
ignores `:timeout` and immediately returns its socket argument (reads block
anyway) would unblock those loops on a single socket; a real multi-socket
select needs a poll primitive on every backend (interpreter:
`InputStream.available()`; WASM: `wasi:io/poll`). Decide whether the
semantically-lying no-op is worth shipping (document loudly) or wait for the
poll primitive.

## Tier 3: socket-server

A single-threaded accept loop (`socket-server host port handler` with
`:in-new-thread`/`:multi-threading` ignored) is expressible in usocket.lisp
today over socket-listen/socket-accept; needs `*remote-host*`/`*remote-port*`
dynamic binding around the handler call (specials work, so this is doable).
Skipped in v1 by scope decision.

## Non-goals here (tracked elsewhere)

UDP (`socket-send`/`socket-receive`, blocked on `.todo/47-udp-sockets.md`)
and `socket-shutdown` (needs a half-close primitive). The error-path gaps
were CLOSED by `.todo/116-error-handling-foundation.md` Phases 1-3
(2026-07-12): the with-* macros close on every exit on interpreter/JVM
(`unwind-protect`), and `usocket:socket-error` is a real condition type
catchable under `handler-case` (`usocket::%usock-guard` re-signal; see
`.kb/error-handling.md`).
