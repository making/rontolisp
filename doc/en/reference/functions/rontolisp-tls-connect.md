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
- **WASM `--component`** (WASI 0.3): supported, over wasmtime's
  `wasi:tls@0.3.0-draft` interface — add `-S tls=y` to the usual socket run
  flags (`-W exceptions=y -S tcp=y -S inherit-network=y`). Like
  `tcp-connect` there, `host` must be an **IPv4 literal** (or `localhost`) —
  and it doubles as the name the certificate is verified against, so for a
  real-world host prefer `tcp-connect` to its address plus
  [`rontolisp:tls-upgrade`](rontolisp-tls-upgrade.md) with the DNS name.
  Failures follow the WASM error convention and return `nil` instead of
  signaling. Certificates are verified against the trust anchors compiled
  into the host (wasmtime bundles the Mozilla root store; the trust-store
  system properties and `:insecure` have no effect there — a non-`nil`
  `:insecure` value **signals** rather than silently verifying). The
  interface is an explicitly experimental draft, so a wasmtime update may
  need a matching rontolisp update.
- **WASM Preview 1**: not supported — a **compile error** (no `wasi:tls` host
  API exists for Preview 1).
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
