# rontolisp:tcp-connect

`(rontolisp:tcp-connect host port)`

Opens a blocking TCP connection to `host`/`port` and returns a
**bidirectional stream handle**. The handle lives in the same handle space as
file streams, so the standard stream functions work on it directly:
[`read-line`](read-line.md), [`write-line`](write-line.md),
[`write-string`](write-string.md), [`read-byte`](read-byte.md),
[`write-byte`](write-byte.md) and
[`close`](close.md). Unlike buffered file output, socket writes are sent
immediately (`write-line` flushes per line). `read-line` returns `nil` once
the peer has closed the connection.

The example below is self-contained: it listens on an ephemeral port with
[`rontolisp:tcp-listen`](rontolisp-tcp-listen.md), connects to itself over the
loopback interface, and echoes one line through
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md):

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (sock (rontolisp:tcp-connect "127.0.0.1" port)))
  (write-line "ping" sock)
  (let* ((peer (rontolisp:tcp-accept listener))
         (line (read-line peer)))
    (write-line line peer)
    (let ((reply (read-line sock)))
      (close peer)
      (close sock)
      (close listener)
      reply)))   ; => "ping"
```

A typical client connects to a fixed host and port and exchanges lines until
the server closes:

```console
(let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
  (write-line "hello" sock)
  (print (read-line sock))   ; the server's reply
  (close sock))
```

## Backend support

- **Interpreter** and **JVM**: use the JDK `java.net.Socket`; `host` may be a
  hostname or an IP literal. A failed connection (for example a refused port)
  signals an error.
- **WASM**: component-only, over `wasi:sockets@0.3.0` (natively WASI 0.3 —
  unlike `rontolisp:fetch`, no 0.2 hybrid is needed). `host` must be an
  **IPv4 literal** such as `"127.0.0.1"` (hostname resolution via
  `wasi:sockets/ip-name-lookup` is not wired yet). Compile with `--component`
  and run with `wasmtime run -W gc=y -W exceptions=y -S tcp=y
  -S inherit-network=y` (wasmtime 46+). A
  failed connection returns `nil` instead of a handle (the same nil-on-failure
  convention as `rontolisp:fetch`); without the `-S` flags the component still
  starts, but every socket operation fails and yields `nil`. The tcp built-ins
  are a compile error in Preview 1 (core-module) mode.
- **Browser playground**: not supported — the browser sandbox provides no raw
  TCP sockets, so every tcp function signals an error.

## Limitations

- TCP only (no UDP yet): the connection is plain text. For an encrypted
  client connection use [`rontolisp:tls-connect`](rontolisp-tls-connect.md)
  (interpreter/JVM only).
- The WASM component backend accepts IPv4 literals only.
- `read` (the s-expression reader) does not work on socket handles; read lines
  or bytes and parse them explicitly (e.g. with
  [`read-from-string`](read-from-string.md)).
