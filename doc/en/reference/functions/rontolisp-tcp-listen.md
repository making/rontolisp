# rontolisp:tcp-listen

`(rontolisp:tcp-listen port &optional host)`

Binds a listening TCP socket on `port` and returns a **listener handle** for
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md) and [`close`](close.md).
Port `0` picks a free ephemeral port — read the actual port back with
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md). Without `host` the
listener binds all interfaces; pass an address string (for example
`"127.0.0.1"`) to bind only one.

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener)))
  (close listener)
  (> port 0))   ; => T
```

A server accepts connections in a loop; each accepted handle is a
bidirectional stream (see
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md)):

```console
(let ((listener (rontolisp:tcp-listen 7777)))
  (do ((n 1 (+ n 1))) (nil)
    (let ((sock (rontolisp:tcp-accept listener)))
      (do ((line (read-line sock) (read-line sock)))
          ((null line) (close sock))
        (write-line line sock)))))   ; echo every client forever
```

## Backend support

- **Interpreter** and **JVM**: use the JDK `java.net.ServerSocket` (with
  `SO_REUSEADDR` on the interpreter); a failed bind (for example a port
  already in use) signals an error.
- **WASM**: component-only, over `wasi:sockets@0.3.0`. `host` must be an IPv4
  literal. Compile with `--component` and run with `wasmtime run -W gc=y
  -W exceptions=y -S tcp=y -S inherit-network=y` (wasmtime 46+); a failed
  bind returns `nil`. Compile error in Preview 1 (core-module) mode.
- **Browser playground**: not supported (no raw TCP in the browser sandbox).

## Limitations

- The listener handle only supports `rontolisp:tcp-accept`,
  `rontolisp:tcp-local-port` and `close` — it is not a byte stream itself.
- See [`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) for the shared
  socket limitations (TCP only, IPv4 literals on WASM). The listener serves
  plain TCP; for a TLS listener use
  [`rontolisp:tls-listen`](rontolisp-tls-listen.md) (interpreter/JVM only).
