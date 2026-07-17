# rontolisp:tcp-accept

`(rontolisp:tcp-accept listener)`

Blocks until a client connects to the given listener handle (from
[`rontolisp:tcp-listen`](rontolisp-tcp-listen.md)) and returns a
**bidirectional stream handle** for the accepted connection — the same kind of
handle [`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) returns, usable
with `read-line`, `write-line`, `write-string`, `read-byte`, `write-byte` and
`close`.

The example is self-contained: because the client connects *before* the
accept, the connection waits in the listen backlog and the single-threaded
program never blocks for long:

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (client (rontolisp:tcp-connect "127.0.0.1" port))
       (server (rontolisp:tcp-accept listener)))
  (write-byte 65 client)
  (let ((b (read-byte server)))
    (close server)
    (close client)
    (close listener)
    b))   ; => 65
```

## Backend support

- **Interpreter** and **JVM**: `java.net.ServerSocket.accept()`; accepting on
  a closed listener signals an error.
- **WASM**: component-only. The accept is a cooperatively blocking read of one
  `tcp-socket` handle from the `wasi:sockets@0.3.0` accept stream; in an async
  body a pending accept suspends only its own task, so other tasks (a
  `rontolisp:wait-for` timer, another request) keep running. Returns
  `nil` if accepting fails. Compile error in Preview 1 (core-module) mode.
- **Browser playground**: not supported.

## Limitations

- Blocks indefinitely until a client connects; there is no timeout parameter.
- One connection is served per call — accept again for the next client (see
  the server loop under [`rontolisp:tcp-listen`](rontolisp-tcp-listen.md)).
