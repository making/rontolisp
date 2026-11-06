# rontolisp:tcp-connect

`(rontolisp:tcp-connect host port)`

Opens a blocking TCP connection to `host`/`port` and returns a
**bidirectional stream handle**. The handle lives in the same handle space as
file streams, so the standard stream functions work on it directly:
[`read-line`](read-line.md), [`write-line`](write-line.md),
[`write-string`](write-string.md), [`write-char`](write-char.md),
[`read-char`](read-char.md), [`read-byte`](read-byte.md),
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
  signals an error. A `host` that is no string or a `port` outside `(INTEGER 0 65535)` signals the
  operator's `type-error` before anything connects.
- **WASM**: component-only, over `wasi:sockets@0.3.0` (natively WASI 0.3, no
  0.2 hybrid). `host` may be a hostname or an IPv4 literal; a hostname
  resolves through `wasi:sockets/ip-name-lookup@0.3.0` and the first IPv4
  address it answers is the one connected to (the connection is IPv4 only).
  Compile with `--component` and run with `wasmtime run -S tcp=y
  -S inherit-network=y` (wasmtime 46+), plus `-S allow-ip-name-lookup=y` for a
  hostname (an IPv4 literal and `localhost` need no lookup). A
  failed connection, or a hostname that does not resolve to an IPv4 address,
  returns `nil` instead of a handle; without the `-S` flags
  the component still starts, but every socket operation fails and yields
  `nil`. The tcp built-ins
  compile in Preview 1 (core-module) mode but every call raises a call-time
  error naming the backends that work.
- **Native executable** (`--native`): as on the interpreter and the JVM -- the
  runner opens the socket, `host` may be a hostname or an IP literal, and a
  failed connection signals the same error
  ([TCP sockets in a native executable](../../compiling/native.md#tcp-sockets)).
- **Browser playground**: not supported — the browser sandbox provides no raw
  TCP sockets, so every tcp function signals an error.

## Limitations

- TCP only (no UDP yet): the connection is plain text. For an encrypted
  client connection use [`rontolisp:tls-connect`](rontolisp-tls-connect.md)
  (interpreter/JVM only).
- The WASM component backend connects over IPv4 only (no IPv6 literal, and a
  hostname with only IPv6 addresses fails).
- `read` (the s-expression reader) does not work on socket handles; read lines
  or bytes and parse them explicitly (e.g. with
  [`read-from-string`](read-from-string.md)).
