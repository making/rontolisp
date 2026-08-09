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
is valid too. A bare string signals a clear error, as Clack itself refuses
strings; a pathname body (lack's file-serving form) is a distinct value here and
is refused too, until the transport can serve it. A function body is supported in Clack's delayed-response form (the
responder is called with the final response list); the streaming-writer form
signals.

## Getting from one handler to a set of routes

The application above is ONE function for the whole site. A routing library is
what turns it into a set of routes, and
[tiny-routes](https://github.com/jeko2000/tiny-routes) loads unmodified (see the
[ASDF systems guide](asdf-systems.md)):

```console
$ cat routes.lisp
(ql:quickload "clack")
(ql:quickload "tiny-routes")

(defpackage :demo (:use :cl :tiny-routes))
(in-package :demo)

(define-routes *app*
  (define-get "/hello" () (ok "hello world"))
  (define-get "/users/:id" (req) (ok (format nil "user ~A" (path-parameter req :id))))
  (define-post "/echo" (req) (ok (format nil "echo:~A" (request-body req))))
  (define-any "*" () (not-found "nope")))

(clack:clackup (pipe *app* (wrap-request-body) (wrap-query-parameters))
               :server :rontolisp :port 5000 :use-thread nil)
$ rontolisp routes.lisp
$ curl http://127.0.0.1:5000/hello
hello world
$ curl http://127.0.0.1:5000/users/42
user 42
$ curl -XPOST -d abc http://127.0.0.1:5000/echo
echo:abc
$ curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5000/zzz
404
```

Its request IS the env plist above and its response IS the response list, so
nothing is converted at the boundary: `wrap-request-body` reads the `:raw-body`
stream, `wrap-query-parameters` parses `:query-string`, the path template
matches `:path-info`, and `ok`/`not-found` build `(status headers body)`. The
routes are read inside the application's own package, which is where the library
is meant to be used from.

The same routes run WITHOUT a server on every backend — call the composed
handler with a request plist you build yourself, which is what
[`examples/asdf/tiny-routes-demo.lisp`](https://github.com/making/rontolisp/blob/main/examples/asdf/tiny-routes-demo.lisp)
does. Serving them has the backend constraints below.

## Backends

The `:server :rontolisp` line does not change between these — it means "serve
on this target's native inbound transport", chosen at compile time:

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
- **WASM reactor** (`--no-wasi`, or `--no-gc`) — the host
  **calls** the module instead of handing it a socket: the same program
  compiles to a module exporting `handle-request` (a JSON request string in, a
  JSON response string out), which a Cloudflare Worker, a browser page, node
  or a JVM host calls per request. `:port` is ignored and `clackup` returns at
  once — the next section has the details.
- **WASM Preview 1** has no incoming TCP by design: the program compiles, and
  `clackup` signals `HTTP-HANDLER requires --component ...` at run time
  (catchable with `handler-case`).

## A host that calls you: the reactor build

Some hosts never hand you a socket. A Cloudflare Worker, a browser page, node
and a JVM embedding all parse the request themselves and then **call an
exported function**. There is nothing for `clackup` to start there — but you
still write `clackup`, and since `:server :rontolisp` picks the transport per
target, *nothing* in the source has to change: compile the very same program
with `--no-wasi` and the handler backend takes its reactor shape.

```console
$ rontolisp app.lisp -o worker.wasm --no-wasi --optimize=size
```

`run` starts nothing here: it stores the application, and the compiler
synthesizes the export the host calls (`handle-request`, a JSON request string
in and a JSON response string out) from a marker the handler backend leaves
behind. Nothing in your source names it, and the module imports nothing — no
WASI shim on the JavaScript side.

One keyword is a property of the *other* backends, not boilerplate:
`:use-thread nil` — on the interpreter and the JVM `clackup` defaults to
running the backend on its own thread, and a script wants to serve in the
foreground. `clackup`'s default middlewares stay on everywhere: lack's
`backtrace` middleware writes its report to `*error-output*`, which under
`--no-wasi` is a discarding sink and on every other backend is real standard
error.

### Driving the reactor by hand: `clack-handler-reactor`

A second built-in handler backend makes the reactor shape **explicit and
host-driven on every backend**:

```console
$ cat worker.lisp
(ql:quickload "clack-handler-reactor")
(load "app.lisp")                       ; defines app, an ordinary Clack application

(clack:clackup #'app :server :reactor :use-thread nil)
```

Where `:rontolisp` binds a socket on the interpreter and the JVM, this
designator stores the application there too, and the host calls
`(clack.handler.reactor:dispatch request-json)` — the same function the
synthesized export calls — directly. That is how a Worker can be developed
and tested without the Worker: the whole edit/run loop happens on the
interpreter. A Worker itself no longer needs this designator; both ride the
same machinery and the same application store, so the two cannot drift.

Underneath both is `handle`, and it needs no `clackup` and no Worker to try —
it is an ordinary function of two arguments:

```lisp
(ql:quickload "clack-handler-reactor")

(defun app (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "~a ~a ~a" (getf env :request-method)
                      (getf env :path-info) (getf env :query-string)))))

(princ (clack.handler.reactor:handle
        #'app "{\"method\":\"GET\",\"target\":\"/hi?a=1\"}"))
```

```text
{"status":200,"headers":[["content-type","text/plain"]],"body":"GET /hi a=1"}
```

`handle` takes the application and one JSON request string and answers one JSON
response string. It builds the Clack environment and normalizes the Clack
response through the same code path a served request takes, so the application
sees exactly what Clack promises — and it **catches**: on a host like this an
uncaught error would take the whole instance down, so it answers 500 with the
condition's report instead.

The envelope, in both directions:

```json
{ "method": "GET", "target": "/path?a=1", "headers": {"host": "..."},
  "body": "", "scheme": "https", "remote-addr": "203.0.113.7" }
```

```json
{ "status": 200, "headers": [["content-type", "text/plain"]], "body": "..." }
```

Two details the host side must get right:

- `target` is the **raw** request target — path and query still joined and still
  percent-encoded. The split and the decoding happen on the Lisp side, and
  `:path-info` / `:query-string` have to come from there for the application to
  see what Clack promises.
- Send `content-length` for a request with a body. `lack/request` parses nothing
  without it, and a request that arrived chunked carries none — set it from the
  bytes you actually read.

Response headers cross as an **array of pairs**, not an object, so an
application that sets two cookies still answers two `Set-Cookie` headers.

A complete Worker built this way — deploying
[`examples/net/httpbin-clack.lisp`](https://github.com/making/rontolisp/blob/main/examples/net/httpbin-clack.lisp)
itself, with the JavaScript side and the measurements — is
[`examples/cloudflare-workers/httpbin-clack/`](https://github.com/making/rontolisp/tree/main/examples/cloudflare-workers/httpbin-clack):
one source, four hosts, and the directory contains no Lisp file at all.

If the module size matters more than the `clackup` line, this adapter is small
enough to write out by hand and skip loading clack entirely.
[`examples/cloudflare-workers/httpbin/`](https://github.com/making/rontolisp/tree/main/examples/cloudflare-workers/httpbin)
is that: the same application, the same envelope, the same JavaScript side, and
about half the module. The two directories are a measured pair — the
per-request cost turns out to be identical, and what clack costs on a host like
this is module size and isolate startup.

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
