# Clack Web Applications

[Clack](https://github.com/fukamachi/clack) — a web
application environment for Common Lisp — loads verbatim via
`(ql:quickload "clack")`, and `clack:clackup` runs a Clack application on the
built-in `clack-handler-rontolisp` backend:

```console
$ cat app.lisp
(ql:quickload "clack")
(clack:clackup
 (lambda (env)
   (list 200 '(:content-type "text/plain")
         (list (format nil "Hello, Clack! ~A ~A~%"
                       (getf env :request-method) (getf env :path-info)))))
 :server :rontolisp
 :port 5000
 :use-thread nil)
$ rontolisp app.lisp        # interpret; or -o App.class / -o app.wasm --component
$ curl http://127.0.0.1:5000/hello
Hello, Clack! GET /hello
```

There is no adaptation layer behind this: rontolisp's own server protocol
*is* Clack's (see [Serving HTTP](http-handler.md)), so the backend hands the
application to the server as the handler and converts nothing per request —
a Clack application is a valid `rontolisp:http-handler` handler, and vice
versa.

The first run downloads clack, [lack](https://github.com/fukamachi/lack) and
their dependencies into `~/.rontolisp/quicklisp`; the dependencies resolve to
real libraries (alexandria, the ironclad slice) and to the
[built-in shim systems](asdf-systems.md#built-in-shim-systems)
(bordeaux-threads, usocket, swank, uiop).

## clackup semantics

The defaults work the way Clack users expect:

- **`:use-thread t` (the default)** returns a handler object while the server
  answers on a background thread ([`rontolisp:make-thread`](../reference/functions/rontolisp-make-thread.md)),
  and `(clack:stop handler)` shuts that server down.
- **`:use-thread nil`** serves in the foreground: `clackup` blocks until the
  process is stopped (Ctrl-C) — the script shape used above.
- **`:use-default-middlewares t` (the default)** wraps the application in
  lack's backtrace middleware through `lack:builder`.
- `:address` binds the listener (default `127.0.0.1`); `:silent t` suppresses
  the banner and `:debug nil` the debug notice.

## The application protocol

The application is a function from the standard Clack env plist to the
standard `(status headers body)` list:

| env key | value |
|---------|-------|
| `:request-method` | the method as an upcased interned keyword (`:GET`, `:POST`, ...) |
| `:script-name` | `""` |
| `:path-info` | the percent-decoded request path |
| `:query-string` | the raw query string, or `nil` |
| `:request-uri` | the raw request target verbatim (still encoded, query included) |
| `:server-name` / `:server-port` | from the `Host` header when present, otherwise the listener's |
| `:server-protocol` | a keyword, e.g. `:HTTP/1.1` |
| `:url-scheme` | `"http"` or `"https"` |
| `:headers` | a hash table (`:test 'equal`) keyed by lowercased header names; duplicate request headers join with `", "` in wire order |
| `:content-type` / `:content-length` | from that table (`nil` when absent; `:content-length` an integer) |
| `:raw-body` | the request body as a synchronous in-memory bivalent stream — `read-line`/`read-char` and `read-byte`/`read-sequence` both work, with a real `file-position` (what lack-request and http-body need); `nil` for a bodiless request |
| `:remote-addr` / `:remote-port` | the real peer on the interpreter and the JVM; `nil` on the WASI component (`wasi:http@0.3.0` exposes no peer accessor) |

The response `body` may be a list of strings, a
`(vector (unsigned-byte 8))` (each octet becomes the character of its code
point), a rontolisp stream, or `nil`; the two-element `(status headers)` form
is valid too. A bare string — and therefore a pathname body (a rontolisp
pathname is its namestring) — signals a clear error, as Clack itself refuses
strings. A function body is supported in Clack's delayed-response form (the
responder is called with the final response list); the streaming-writer form
signals.

## Backends

- **Interpreter** — everything above.
- **JVM class** — the same program compiled with `-o App.class`; like every
  served program it needs the rontolisp jar on the runtime classpath
  (`java -cp rontolisp-exec.jar:. App`).
- **WASM component** (`--component`) — the host owns the socket: run with
  `wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y
  -S inherit-network=y app.wasm`. The `:port` argument is ignored,
  `:use-thread` is effectively `nil` (the WASM backends are single-threaded,
  so it defaults to `nil` there) and `clack:stop` is meaningless — the host
  controls the server's lifecycle.
- **WASM Preview 1** has no incoming TCP by design: the program compiles, and
  `clackup` signals `HTTP-HANDLER requires --component ...` at run time
  (catchable with `handler-case`).

## Current limits

- One Clack server per process: a second concurrent `clackup` replaces the
  first one's application.
- `clack.socket` (WebSocket) and `:swank-port` are unsupported (`:swank-port`
  reaches the `swank` stub, which signals).
- Streaming-writer responses and bare-string/pathname bodies signal, as noted
  above (delayed function responses work).

See also: [Serving HTTP (http-handler)](http-handler.md) for the underlying
server, and [`examples/asdf/clack-hello.lisp`](https://github.com/making/rontolisp/blob/main/examples/asdf/clack-hello.lisp)
for the runnable demo with all per-backend commands.
