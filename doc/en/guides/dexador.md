# HTTP Client (dexador)

[Dexador](https://github.com/fukamachi/dexador) — the HTTP client most Common
Lisp code is written against — loads **unpatched** via
`(ql:quickload "dexador")`. Its socket layer is the built-in
[usocket shim](tcp-sockets.md#the-usocket-compatible-shim) and its TLS layer the
built-in [`cl+ssl` shim](asdf-systems.md#built-in-shim-systems); every other
dependency (fast-http, quri, babel, chipz, cl-cookie, trivial-mimes, ...) is
the real library. Redirects, chunked and gzip bodies, cookies, keep-alive and
the request/response encoding all run dexador's own Lisp code, so a request
behaves as it does on SBCL.

For a request with no library at all, [`rontolisp:fetch`](http-fetch.md) is
the built-in client; dexador is for code that already speaks it.

## Requests

`dex:get`, `dex:post`, `dex:head`, `dex:put`, `dex:patch` and `dex:delete`
answer the body, the status and the response headers (a hash table keyed by
lower-case names) as three values:

```console
$ cat client.lisp
(ql:quickload "dexador" :silent t)
(multiple-value-bind (body status headers) (dex:get "http://127.0.0.1:8080/get?lang=lisp")
  (format t "~a ~a~%~a~%" status (gethash "content-type" headers) body))
(format t "~a~%" (dex:post "http://127.0.0.1:8080/post"
                           :content '(("name" . "rontolisp") ("city" . "Tōkyō"))))
$ rontolisp client.lisp
200 application/json
{"args":{"lang":"lisp"}, ...}
{"data":"name=rontolisp&city=T%C5%8Dky%C5%8D", ...}
```

What `:content` takes is dexador's: a string or an octet vector goes out as
is, an alist as a URL-encoded form (or as `multipart/form-data` when a value is
a pathname). `:headers` is an alist; a `Content-Type` there overrides the
default. A status of 400 or above signals `dex:http-request-failed`, or the
subtype for the status (`dex:http-request-not-found` for 404 and so on), whose
`dex:response-status`, `dex:response-body` and `dex:response-headers` read the
reply:

```console
(handler-case (dex:get "http://127.0.0.1:8080/missing")
  (dex:http-request-not-found (e)
    (format t "~a ~a~%" (dex:response-status e) (dex:response-body e))))
```

Redirects are followed (`:max-redirects`, default 5), a gzip body is
decoded, a `:cookie-jar` from `(cl-cookie:make-cookie-jar)` keeps the cookies a
server sets, and connections come from a pool, so consecutive requests to one
host reuse one socket. `:want-stream t` answers the body as a stream; closing it
drains what is left, so the connection goes back to the pool. `:read-timeout`
(seconds, default 10) is a real read deadline: a stalled server fails the
request with `read-byte: Read timed out`.

[`examples/net/dexador-client.lisp`](https://github.com/making/rontolisp/tree/develop/examples/net/dexador-client.lisp)
runs these requests against
[`examples/net/httpbin.lisp`](https://github.com/making/rontolisp/tree/develop/examples/net/httpbin.lisp).

## HTTPS

An `https://` URL goes through the `cl+ssl` shim over
[`rontolisp:tls-upgrade`](../reference/functions/rontolisp-tls-upgrade.md):
the server certificate is verified, and so is the host name. On the
interpreter and the JVM the trust store is the JDK's; point the
`javax.net.ssl.trustStore` system properties at your own to trust a private
CA. `:insecure t` (or binding `dex:*not-verify-ssl*` to `t`) skips
verification, for development only.

```console
(dex:get "https://example.com/")                   ; verified
(dex:get "https://127.0.0.1:8443/" :insecure t)    ; a self-signed development server
```

A handshake that fails — an untrusted certificate, a name the certificate does
not carry — signals an `error` on every backend.

## Backends

- **Interpreter** and **JVM class** — everything above. A compiled class
  writes its runtime support classes into `am/` beside it; keep the two
  together (`-o client.jar` carries both).
- **WASM component** (`--component`) — HTTP and HTTPS:

  ```bash
  rontolisp client.lisp -o client.wasm --component
  wasmtime run -S tcp=y -S inherit-network=y -S tls=y -S allow-ip-name-lookup=y client.wasm
  ```

  `-S tls=y` is needed even for a program that only fetches `http://`
  (dexador names the TLS layer either way), and `-S allow-ip-name-lookup=y`
  for a URL whose host is a name rather than an IPv4 literal; the component
  connects over IPv4 only. The certificate is verified against wasmtime's own
  roots, so the `javax.net.ssl` properties do not apply, and `:insecure t`
  signals: the host offers no way to skip verification.
- **Native executable** (`--native`) — HTTP only. An `https://` URL signals
  when the request runs; the program still builds.
- **WASM Preview 1** — compiles, and the first request signals naming
  `--native`: a plain Preview 1 module has no sockets.

dexador itself needs no `--dir` on any backend (a file a multipart upload
reads does). trivial-mimes, which it loads, builds its MIME table when the
program starts from `/etc/mime.types` where the running machine has one, and
otherwise from the copy its release bundles, which the compiled program
carries.

## Current limits

- **No proxies.** `:proxy` and SOCKS5 are not supported.
- **No client certificates.** `:ssl-key-file`, `:ssl-cert-file` and
  `:ssl-key-password` signal, as does `:ca-path`; use the trust-store
  properties instead.
- **No `https://` from a native executable or Preview 1** (see Backends).

See also: [TCP Sockets](tcp-sockets.md) for the socket and TLS rules per
backend, [HTTP Requests (fetch)](http-fetch.md) for the built-in client, and
[Systems (asdf)](asdf-systems.md) for the library catalogue.
