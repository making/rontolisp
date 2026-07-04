# rontolisp:tls-listen

`(rontolisp:tls-listen keystore password port &optional host)`

Binds a listening **TLS** socket — the encrypted counterpart of
[`rontolisp:tcp-listen`](rontolisp-tcp-listen.md). `keystore` is the path to a
**PKCS12 keystore file** holding the server private key and certificate, and
`password` unlocks it. The returned listener handle works with the plain TCP
functions unchanged: accept connections with
[`rontolisp:tcp-accept`](rontolisp-tcp-accept.md), read the bound port back
with [`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md) (useful after
listening on port `0`) and shut down with [`close`](close.md). An accepted
connection is a normal bidirectional stream handle; its TLS handshake
completes on the first read or write.

Generate a self-signed keystore for local development with the JDK `keytool`
(or export one from openssl with `openssl pkcs12 -export`):

```bash
keytool -genkeypair -alias my-server -keyalg EC -dname CN=localhost \
  -validity 365 -ext SAN=ip:127.0.0.1,dns:localhost \
  -storetype PKCS12 -keystore tls-server.p12 \
  -storepass changeit -keypass changeit
```

The example below is a one-connection TLS echo server; test it with
`openssl s_client -connect 127.0.0.1:8443` (type a line, it comes back):

```console
(let* ((listener (rontolisp:tls-listen "tls-server.p12" "changeit" 8443))
       (sock (rontolisp:tcp-accept listener))    ; blocks for a client
       (line (read-line sock)))                  ; the handshake happens here
  (write-line line sock)
  (close sock)
  (close listener))
```

Complete servers live in the `examples/` directory:
[`https-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/https-hello.lisp)
(an HTTPS server `curl -k` understands) and
[`kv-server-tls.lisp`](https://github.com/making/rontolisp/blob/develop/examples/kv-server-tls.lisp)
(a mini-Redis served over TLS that the real `redis-cli --tls` talks to).

## Backend support

- **Interpreter** and **JVM**: use the JDK TLS stack. Unlike `tcp-listen` on
  the WASM backend, failures never yield `nil`: a missing keystore, a wrong
  password or a busy port signals an error on both backends.
- **WASM**: not supported — `tls-listen` is a **compile error** in both
  Preview 1 and `--component` mode. The `wasi:tls` proposal is **client-only**
  (there is no server-side TLS interface for WASM components), so a TLS
  *server* has no WASM path.
- **Browser playground**: not supported — the browser sandbox provides no raw
  TCP sockets, so `tls-listen` signals an error.

## Limitations

- The keystore must be PKCS12 (`keytool`'s default). To serve a certificate and
  key straight from **PEM files** (certbot / OpenSSL output), use
  [`rontolisp:tls-listen-pem`](rontolisp-tls-listen-pem.md) instead.
- No client-certificate authentication (mutual TLS) options.
- The handshake of an accepted connection is lazy: a client that fails
  certificate validation surfaces as an error on the server's first read or
  write of that connection, not at `rontolisp:tcp-accept` time.
