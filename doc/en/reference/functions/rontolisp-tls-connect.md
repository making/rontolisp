# rontolisp:tls-connect

`(rontolisp:tls-connect host port)`
`(rontolisp:tls-connect host port :insecure value)`

Opens a blocking TCP connection to `host`/`port`, performs a **TLS
handshake**, and returns a **bidirectional stream handle** — the encrypted
counterpart of [`rontolisp:tcp-connect`](rontolisp-tcp-connect.md). The handle
lives in the same handle space as file streams, so the standard stream
functions work on it directly: [`read-line`](read-line.md),
[`write-line`](write-line.md), [`read-byte`](read-byte.md),
[`write-byte`](write-byte.md) and [`close`](close.md). As with plain sockets,
writes are sent immediately and `read-line` returns `nil` once the peer has
closed the connection.

The server certificate is validated against the JDK default trust store and
the hostname is verified (HTTPS-style endpoint identification), so connecting
to a server with an untrusted or mismatching certificate signals an error. To
trust a self-signed certificate, point the standard
`javax.net.ssl.trustStore` / `javax.net.ssl.trustStorePassword` system
properties at your own trust store; they are re-read on every call.

Passing `:insecure` with a non-`nil` `value` **disables** both checks — the
certificate chain is accepted unconditionally and the hostname is not verified.
This is intended for development against a self-signed server; never use it for
real endpoints, since it removes all protection against man-in-the-middle
attacks. `:insecure nil` is the same as omitting the option (verification on).

The example below speaks HTTP/1.1 over TLS by hand (the request lines end
with CRLF, so the carriage return is appended explicitly; `read-line` strips
it from the response). For real HTTPS requests prefer
[`rontolisp:fetch`](rontolisp-fetch.md) — `tls-connect` is for arbitrary
TLS-wrapped protocols:

```console
(let ((sock (rontolisp:tls-connect "example.com" 443))
      (cr (princ-to-string (code-char 13))))
  (write-line (concatenate 'string "GET / HTTP/1.1" cr) sock)
  (write-line (concatenate 'string "Host: example.com" cr) sock)
  (write-line (concatenate 'string "Connection: close" cr) sock)
  (write-line cr sock)
  (print (read-line sock))   ; "HTTP/1.1 200 OK"
  (close sock))
```

## Backend support

- **Interpreter** and **JVM**: use the JDK TLS stack (`SSLSocket`); `host` may
  be a hostname or an IP literal. A failed connection or handshake (refused
  port, untrusted certificate, hostname mismatch) signals an error.
- **WASM**: not supported yet — `tls-connect` is a **compile error** in both
  Preview 1 and `--component` mode. A component-mode client could be built on
  wasmtime's experimental `wasi:tls@0.3.0-draft` interface, but that interface
  is unstable (no semver guarantee); until it settles, use the interpreter or
  the JVM backend.
- **Browser playground**: not supported — the browser sandbox provides no raw
  TCP sockets, so `tls-connect` signals an error.

## Limitations

- `:insecure` is an all-or-nothing opt-out (no per-certificate pinning); to
  trust specific additional certificates while keeping verification on, use the
  trust-store system properties instead. For the *server* side of TLS see
  [`rontolisp:tls-listen`](rontolisp-tls-listen.md).
- `read` (the s-expression reader) does not work on socket handles; read
  lines or bytes and parse them explicitly (e.g. with
  [`read-from-string`](read-from-string.md)).
