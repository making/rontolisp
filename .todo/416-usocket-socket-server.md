# 416. usocket:socket-server

Difficulty: Medium

Split out of `.todo/114` Tier 2 (2026-08-16). The v1 sketch there -- a
single-threaded accept loop with `:in-new-thread`/`:multi-threading` IGNORED
-- was rejected for the same reason Tier 1b rejected a silent no-op timeout:
ignoring `:in-new-thread` blocks a caller that expects `socket-server` to
return immediately (upstream returns `(values thread socket)`), which is a
semantic lie, not a degraded mode.

Since todo-227 the honest version is possible on the interpreter/JVM:
`rontolisp:make-thread` exists (the bt2 shim rides it), so

- `:in-new-thread t` -> run the event loop on a spawned thread and return
  `(values thread socket)`;
- `:multi-threading t` -> one thread per accepted connection;
- the handler runs with `usocket:*remote-host*` / `usocket:*remote-port*`
  dynamically bound from `get-peer-name` (specials work);
- every connection closes on every handler exit (`unwind-protect` where it
  compiles, the with-* macro split);
- `:protocol :datagram` signals (UDP is `.todo/047`).

The open design question is the WASM story: usocket.lisp is parsed ONCE for
all backends and is spliced UNPRUNED into every usocket program, so a
`rontolisp:make-thread` reference in it must not break the WASM compiles
(today a bare reference there is the thread shim's business, not the
usocket shim's). Options: route through a `%usock-`internal that the WASM
path never reaches plus a run-time `(member :rontolisp-wasm *features*)`
branch (the wait-for-input pattern, `.kb/tcp-sockets.md`) -- but the COMPILE
of the make-thread call site is the thing to verify first; or make the
single-threaded loop the WASM behavior and signal on `:in-new-thread` /
`:multi-threading` there. Decide with the same loud-over-lying rule and
record it in `.kb/tcp-sockets.md`.

No consumer is currently blocked on this (dexador/cl-postgres/clack do not
call it); it is API-surface completeness for accept-loop consumers.
