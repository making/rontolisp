# rontolisp:tls-connect

`(rontolisp:tls-connect host port)`

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
- **WASM**: not supported — wasmtime hosts no TLS for WASI 0.3 components
  (`wasi:tls` is still a 0.2 draft), so `tls-connect` is a **compile error**
  in both Preview 1 and `--component` mode.
- **Browser playground**: not supported — the browser sandbox provides no raw
  TCP sockets, so `tls-connect` signals an error.

## Limitations

- Certificate verification cannot be disabled from Lisp; use the trust-store
  system properties to trust additional certificates. For the *server* side
  of TLS see [`rontolisp:tls-listen`](rontolisp-tls-listen.md).
- `read` (the s-expression reader) does not work on socket handles; read
  lines or bytes and parse them explicitly (e.g. with
  [`read-from-string`](read-from-string.md)).
