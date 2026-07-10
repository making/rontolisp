# TCP Sockets

The `rontolisp` package provides four functions for plain TCP networking,
plus encrypted variants for both sides (`tls-connect` and `tls-listen`). They
are **not part of Common Lisp**; reference them with the `rontolisp:`
qualifier (see [Packages](../reference/packages.md)). A connected socket is a
**bidirectional stream handle** in the same handle space as file streams, so
the standard stream functions work on it directly: `read-line`, `write-line`,
`read-byte`, `write-byte` and `close`. Unlike buffered file output, socket
writes are sent immediately (`write-line` flushes per line), and `read-line`
returns `nil` once the peer has closed the connection.

| Function | Purpose |
|----------|---------|
| [`rontolisp:tcp-connect`](../reference/functions/rontolisp-tcp-connect.md) | Open a client connection: `(rontolisp:tcp-connect host port)` |
| [`rontolisp:tcp-listen`](../reference/functions/rontolisp-tcp-listen.md) | Bind a listening socket: `(rontolisp:tcp-listen port &optional host)` |
| [`rontolisp:tcp-accept`](../reference/functions/rontolisp-tcp-accept.md) | Wait for a client connection: `(rontolisp:tcp-accept listener)` |
| [`rontolisp:tcp-local-port`](../reference/functions/rontolisp-tcp-local-port.md) | Read the bound port back (useful after listening on port `0`) |
| [`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md) | Open an **encrypted** client connection: `(rontolisp:tls-connect host port)` |
| [`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md) | Bind an **encrypted** listening socket from a PKCS12 keystore: `(rontolisp:tls-listen keystore password port &optional host)` |
| [`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md) | Bind an **encrypted** listening socket from PEM files: `(rontolisp:tls-listen-pem cert-file key-file port &optional host)` |

> **Backend support.** The interpreter and JVM-compiled classes use the JDK
> socket classes and accept hostnames or IP literals. The WASM backend is
> **component-only** (`--component`, over `wasi:sockets@0.3.0`): the tcp
> functions are a compile error in Preview 1 (core-module) mode, hosts must be
> IPv4 literals, and the component must run with `-S tcp=y
> -S inherit-network=y` on top of the async flags. In the **browser
> playground** every tcp function signals an error (the browser sandbox has no
> raw TCP), so the runnable example below only works outside the browser. See
> the [tcp-connect](../reference/functions/rontolisp-tcp-connect.md) reference
> page for the shared limitations (TCP only, no UDP). The TLS functions
> ([`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md),
> [`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md) and
> [`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md))
> are interpreter/JVM only (a compile error on the WASM backend).

## A first round trip

The snippet below is self-contained: it listens on an ephemeral port, connects
to itself over the loopback interface, and echoes one line back through the
accepted handle:

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

## An echo server

A real server binds a fixed port and serves connections in an accept loop.
Save the following as `echo-server.lisp` (also shipped as
[`examples/net/echo-server.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/echo-server.lisp)).
Each accepted handle is read line by line until `read-line` returns `nil`
(the client closed), and every line is written straight back:

```console
(let ((listener (rontolisp:tcp-listen 7777)))
  (if listener
      (progn
        (write-line "echo server listening on 127.0.0.1:7777")
        (do ((n 1 (+ n 1))) (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (write-line (format nil "client ~a connected" n))
            (do ((line (read-line sock) (read-line sock)))
                ((null line) (close sock) (write-line "client disconnected"))
              (write-line line sock)))))
      (write-line "tcp-listen failed (is port 7777 already in use?)")))
```

The `(if listener ...)` check matters on the WASM component backend, where a
failed bind returns `nil` instead of signaling an error (the interpreter and
JVM signal). The server loops forever — stop it with `Ctrl-C`.

### Running it

On the interpreter:

```bash
rontolisp echo-server.lisp
```

Compiled to a JVM class (the class is named after the output file):

```bash
rontolisp echo-server.lisp -o EchoServer.class
java EchoServer
```

Compiled to a WASM component (wasmtime 46+; note the two `-S` flags that grant
network access — without them the component still starts, but `tcp-listen`
returns `nil`):

```bash
rontolisp echo-server.lisp -o echo-server.wasm --component
wasmtime run -W gc=y -W component-model-more-async-builtins=y -S tcp=y -S inherit-network=y echo-server.wasm
```

Whichever backend serves, talk to it with any TCP client, for example
`nc` (netcat):

```console
$ nc 127.0.0.1 7777
hello
hello
world
world
```

## TLS connections

[`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md) is
the encrypted counterpart of `tcp-connect`: it performs a TLS handshake after
connecting and returns the same kind of stream handle, so `read-line`,
`write-line`, `read-byte`, `write-byte` and `close` work unchanged. The server
certificate is validated against the JDK default trust store and the hostname
is verified; point the `javax.net.ssl.trustStore` system properties at your
own trust store to accept self-signed certificates, or pass `:insecure t` to
skip verification entirely (development only). See the reference page for
details and an HTTPS-by-hand example:

```console
(let ((sock (rontolisp:tls-connect "example.com" 443)))
  ...  ; speak any TLS-wrapped protocol over the handle
  (close sock))
```

The *server* side is
[`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md): it
takes a PKCS12 keystore file (its reference page shows the one-line `keytool`
command that generates a self-signed one) and returns a listener that the
plain `rontolisp:tcp-accept` / `rontolisp:tcp-local-port` / `close` work on;
each accepted connection completes its handshake on the first read. To serve
straight from PEM files (certbot / OpenSSL output) instead of a PKCS12
keystore, use
[`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md).
The TLS variants of the servers below are in the `examples/` directory —
[`https-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/https-hello.lisp)
and
[`kv-server-tls.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/kv-server-tls.lisp):

```console
(let* ((listener (rontolisp:tls-listen "tls-server.p12" "changeit" 8443))
       (sock (rontolisp:tcp-accept listener)))
  ...  ; serve the connection with the standard stream functions
  (close sock)
  (close listener))
```

## More examples

The [`examples/` directory](https://github.com/making/rontolisp/tree/develop/examples)
contains further socket programs, each with per-backend run instructions in
its header comment:

- [`echo-client.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/echo-client.lisp)
  — the matching client for the echo server: pipes standard-input lines to
  the server and prints each reply. Server and client can each run on a
  *different* backend.
- [`http-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/http-hello.lisp)
  — a minimal HTTP/1.1 server that `curl` and browsers understand, built from
  `read-line`/`write-line` over the socket handle (CRLF request lines read as
  plain lines on every backend). Its TLS twin
  [`https-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/https-hello.lisp)
  serves the same page over HTTPS (`curl -k https://127.0.0.1:8443/`).
- [`kv-server.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/kv-server.lisp)
  — a miniature **Redis-compatible** in-memory key-value server: speaks
  enough RESP2 that the real `redis-cli` works (`PING`/`SET`/`GET`/`DEL`/
  `INCR`/...), with hash-table state surviving across connections. Its TLS
  twin
  [`kv-server-tls.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/kv-server-tls.lisp)
  serves the same protocol over TLS (`redis-cli --tls --insecure -p 6380`).

For HTTP there is no need to hand-roll the protocol over a socket in either
direction: the *client* side is `rontolisp:fetch` (see the
[HTTP Requests guide](http-fetch.md)), and for the *server* side
`rontolisp:http-handler` parses requests and adapts responses for you (see the
[Serving HTTP guide](http-handler.md)).
