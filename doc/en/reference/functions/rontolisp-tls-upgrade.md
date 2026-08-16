# rontolisp:tls-upgrade

`(rontolisp:tls-upgrade stream host)`
`(rontolisp:tls-upgrade stream host :insecure value)`

Wraps an **already-connected** TCP stream handle in **TLS** as a client:
performs the handshake over the existing connection and returns a **new**
stream handle carrying the encrypted stream. Where
[`rontolisp:tls-connect`](rontolisp-tls-connect.md) opens a fresh connection,
`tls-upgrade` takes the handle an earlier
[`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) (or `usocket:socket-connect`)
answered — the shape an HTTP client library needs, since it connects first
(possibly issuing a proxy `CONNECT`) and only then starts TLS. The returned
handle works with the standard stream functions ([`read-line`](read-line.md),
[`write-line`](write-line.md), [`read-byte`](read-byte.md),
[`write-byte`](write-byte.md), [`close`](close.md)); closing it also closes the
underlying connection.

The server certificate is validated against the JDK default trust store and
`host` is verified against it (HTTPS-style endpoint identification; `host` is
also sent as the SNI server name). To trust a self-signed certificate, point
the standard `javax.net.ssl.trustStore` / `javax.net.ssl.trustStorePassword`
system properties at your own trust store; they are re-read on every call.
Passing `:insecure` with a non-`nil` `value` disables both checks — development
only, exactly like `tls-connect`'s option.

This is the primitive behind the bundled
[`cl+ssl` shim system](../../guides/asdf-systems.md#built-in-shim-systems):
`cl+ssl:make-ssl-client-stream` — the call every CL HTTP client (dexador,
drakma, ...) makes for an `https://` URL — upgrades the stream it is handed
through `tls-upgrade`.

The example speaks HTTPS by hand over an upgraded plain connection (for real
HTTPS requests prefer [`rontolisp:fetch`](rontolisp-fetch.md)):

```console
(let* ((sock (rontolisp:tcp-connect "example.com" 443))
       (tls (rontolisp:tls-upgrade sock "example.com"))
       (cr (princ-to-string (code-char 13))))
  (write-line (concatenate 'string "HEAD / HTTP/1.1" cr) tls)
  (write-line (concatenate 'string "Host: example.com" cr) tls)
  (write-line (concatenate 'string "Connection: close" cr) tls)
  (write-line cr tls)
  (print (read-line tls))   ; "HTTP/1.1 200 OK"
  (close tls))
```

## Backend support

- **Interpreter** and **JVM**: use the JDK TLS stack
  (`SSLSocketFactory.createSocket(socket, host, port, true)`); a failed
  handshake (untrusted certificate, hostname mismatch, a peer that does not
  speak TLS) signals an error.
- **WASM**: not supported yet — `tls-upgrade` is a **compile error** in both
  Preview 1 and `--component` mode, like the rest of the TLS family. wasmtime's
  experimental `wasi:tls@0.3.0-draft` interface could host it, but it is
  unstable (no semver guarantee); until it settles, use the interpreter or the
  JVM backend.
- **Browser playground**: not supported — the browser sandbox provides no raw
  TCP sockets, so `tls-upgrade` signals an error.

## Limitations

- `stream` must be a **connected socket handle** (from `tcp-connect` or
  `tcp-accept`); a listener or file-stream handle signals an error. The
  original handle still names the raw connection underneath — after the
  upgrade, read and write through the new handle only.
- Client certificates are not supported (there is no way to present a client
  identity); the `cl+ssl` shim signals on its `:key`/`:certificate`/`:password`
  options for this reason rather than silently connecting unauthenticated.
- `:insecure` is an all-or-nothing opt-out, like `tls-connect`'s.
