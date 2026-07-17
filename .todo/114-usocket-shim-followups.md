# usocket shim follow-ups

The `usocket` package (usocket.lisp + UsocketLibrary + the with-* macro
expansions + the BuiltinSystems "usocket" ASDF hook) shipped covering the
Postmodern/cl-postgres surface (socket-connect + :element-type +
socket-stream) plus listeners, accessors and the with-* macros. See
`.kb/tcp-sockets.md` for the mechanics. Remaining gaps, none needed by
Postmodern:

## Tier 1: wait-for-input (single-socket degenerate form)

Many usocket consumers loop `wait-for-input` -> read. A lite version that
ignores `:timeout` and immediately returns its socket argument (reads block
anyway) would unblock those loops on a single socket; a real multi-socket
select needs a poll primitive on every backend (interpreter:
`InputStream.available()`; WASM: `wasi:io/poll`). Decide whether the
semantically-lying no-op is worth shipping (document loudly) or wait for the
poll primitive.

## Tier 2: socket-server

A single-threaded accept loop (`socket-server host port handler` with
`:in-new-thread`/`:multi-threading` ignored) is expressible in usocket.lisp
today over socket-listen/socket-accept; needs `*remote-host*`/`*remote-port*`
dynamic binding around the handler call (specials work, so this is doable).
Skipped in v1 by scope decision.

## Non-goals here (tracked elsewhere)

UDP (`socket-send`/`socket-receive`, blocked on `.todo/047-udp-sockets.md`)
and `socket-shutdown` (needs a half-close primitive). The error-path gaps
were CLOSED by todo-116 Phases 1-3
(2026-07-12): the with-* macros close on every exit on interpreter/JVM
(`unwind-protect`), and `usocket:socket-error` is a real condition type
catchable under `handler-case` (`usocket::%usock-guard` re-signal; see
`.kb/error-handling.md`).
