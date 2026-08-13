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
[`examples/asdf/tiny-routes-demo.lisp`](https://github.com/making/rontolisp/blob/develop/examples/asdf/tiny-routes-demo.lisp)
does. Serving them has the backend constraints below.

### The other answer: ningle

[ningle](https://github.com/fukamachi/ningle) loads unmodified too, and it is a
different model rather than a different spelling. The application is a CLOS
**object** you hang routes on, each route is a `setf`, a controller receives the
matched **parameters** (the request itself is in a special variable), and a
controller that is not a function at all is answered as the body:

```console
$ cat ningle-app.lisp
(ql:quickload "clack")
(ql:quickload "ningle")

(defpackage :demo (:use :cl))
(in-package :demo)

(defvar *app* (make-instance 'ningle:app))

(setf (ningle:route *app* "/") "Welcome to ningle!")
(setf (ningle:route *app* "/hello/:name")
      (lambda (params) (format nil "Hello, ~A" (cdr (assoc :name params)))))
(setf (ningle:route *app* "/submit" :method :POST)
      (lambda (params) (format nil "posted ~A" (cdr (assoc "q" params :test #'string=)))))

(clack:clackup *app* :server :rontolisp :port 5000 :use-thread nil)
$ rontolisp ningle-app.lisp
$ curl http://127.0.0.1:5000/
Welcome to ningle!
$ curl http://127.0.0.1:5000/hello/Eitaro
Hello, Eitaro
$ curl -XPOST -d q=abc http://127.0.0.1:5000/submit
posted abc
$ curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5000/zzz
404
```

Four differences are worth knowing before picking one:

- **Routes are added, not listed.** `(setf (ningle:route ...))` mutates the
  application, so routes can come from anywhere — including from run-time data.
- **Query and body parameters arrive in the same alist** as the template's
  `:name` bindings (keyed by the string name), because ningle reads every
  request through `lack-request`. tiny-routes never touches that chain, and that
  is most of the size difference in a compiled module — an order of magnitude
  for the same two routes, with no ppcre-free opt-in to fall back on, since
  ningle's router compiles every rule to a scanner.
- **The 404 is a method**, `ningle:not-found`, rather than a catch-all route,
  and `ningle:*response*` is mutable — which is how a controller answers a
  status other than 200.
- **A route can be chosen by something that is not the path.** `:accept`
  negotiation is built in, and `(setf (ningle:requirement app :key) fn)`
  registers your own; the closure runs on every dispatch.

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

### A handler that fetches: `--host-fetch`

A reactor imports nothing, which also means it has no HTTP client — so an
application that calls [`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md)
(a proxy, an API gateway) needs one more flag. `--host-fetch` lowers `fetch`
onto the host's own client as a single `env.fetch` import; that one import is
the whole difference to the module above:

```console
$ rontolisp worker.lisp -o worker.wasm --no-wasi --host-fetch --optimize=size
```

The route bodies stay synchronous. Only an `async-defun` / `async-lambda` body
may `await`, so a route that needs a fetched value calls one and returns its
**future** — the reactor transport resolves a future-valued response at the
boundary, exactly as `wasmtime serve` does under `--component`. The
[fetch guide](http-fetch.md#fetching-from-a-reactor---no-wasi---host-fetch) has
what else is particular to this transport (an eager `:body`, a settled future,
and the JSPI obligation on the JavaScript side), and
[`examples/cloudflare-workers/dog-fetcher`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/dog-fetcher)
is a routed Worker built this way — one source that also serves a socket on the
interpreter and the JVM.

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

### Passing the body separately

The JSON above is the request **head**. `handle` and `dispatch` take one more
optional argument, the **body source**, so the body does not have to ride
inside it:

- `nil` — no body;
- a string — the body, already read;
- a function of no arguments — a **pull source**: each call answers the next
  chunk — a string, or an `(unsigned-byte 8)` vector for a host that hands over
  raw octets — with `nil` or an empty chunk for the end. It may answer a future,
  so a host that suspends while it reads can hand one over.

A chunk boundary may fall inside a UTF-8 sequence: a host reading a socket knows
nothing about code points, so the open sequence is carried into the next chunk
rather than decoded as two malformed characters.

A source that is empty at its **first** call is no body at all — `:raw-body`
stays `nil`, exactly as for a request whose `"body"` is absent, because that is
what upstream's `(when raw-body ...)` guards expect and a bodiless `GET` must
not pay for a stream it would only find empty.

The envelope's `"body"` key is exactly the string case, and it is what is used
when no source is passed — or when the source turns out to be empty, so a host
may start handing a reader over without also having to stop filling the
envelope. A host written against the shape above keeps working unchanged.

### Taking the response body separately

Symmetrically, `handle` and `dispatch` take a fourth optional argument, the
**body sink**: a function of one argument, called with each chunk of the
response body. It may answer a future, so a host that suspends while it writes
can hand one over.

Given a sink, the JSON answer is the response **head** and its `"body"` key is
**absent** — so a host can tell "the body crossed out of band" from "the body is
the empty string". A **stream** response body (a proxied `fetch`) is then
forwarded chunk at a time instead of being collected into one string first.

```lisp
(ql:quickload "clack-handler-reactor")

(defun app (env)
  (declare (ignore env))
  (list 200 '(:content-type "text/plain") (list "hello")))

(defvar *chunks* nil)
(defun sink (chunk) (setq *chunks* (cons chunk *chunks*)) nil)

(princ (clack.handler.reactor:handle
        #'app "{\"method\":\"GET\",\"target\":\"/hi\"}" nil #'sink))
(terpri)
(princ (car *chunks*))
```

```
{"status":200,"headers":[["content-type","text/plain"]]}
hello
```

The chunks cross **before** the head, because the head is the return value. So a
head that does carry a `"body"` key wins over anything already written: that is
how a handler that fails halfway through its body still answers one clean
document — the 500 the transport catches into carries its report in band, and
the host discards the chunks it already took.

An `(unsigned-byte 8)` response body reaches the sink as **octets**, not as
text: a sink can write bytes, and a JSON head cannot carry them.

Passing no sink keeps the old shape exactly: the body rides the head, a stream
body is drained into it, and an octet body is rendered one character per octet
(the only spelling a JSON string has for it).

### The WASM boundary: a head export and two body imports

On a `--no-wasi` WASM module neither a source nor a sink is a Lisp value the
host can pass, so the boundary is three entries and the compiler writes the two
imports for you:

```text
module -> host   handle-request(headPtr, headLen) -> (ptr, len)   ; the JSON head
host -> module   env.readRequestBody(ptr, cap) -> n               ; up to cap octets
                                                                  ;   at ptr; 0 = end
host -> module   env.writeResponseBody(ptr, len)                  ; take these octets
```

The head is the JSON above **without** the `"body"` key, in either direction.
The bodies cross as raw octets — in, into a buffer the module owns and reuses;
out, straight out of the module's own memory — which is what a JSON string could
not do: a **binary** body crosses exactly either way (the string boundary
decodes UTF-8, and does not validate), and *crossing* costs the module no linear
memory at all — the envelope used to hold the body several times over. Reading
the request body is not yet free: whichever way a handler drains `:raw-body`,
decoding the octets to text currently costs about fifteen times the body in
linear memory, reclaimed for reuse at the end of the request.

Note the direction flip in the two imports. A chunk crossing *in* is a result
written into a buffer the module passes; one crossing *out* is a parameter the
host reads and must copy before the call returns. Both are the same rule — the
caller owns the memory — and both mean the host may not hold on to a pointer.

Both imports are declared `:async t`, so the host chooses how it answers.
Answering synchronously (read the body first, then call in; collect the response
chunks as they arrive) is the simple host, and it is what the Worker examples
do. Wrapping an import in `WebAssembly.Suspending` — pulling from the request's
own reader, or writing to a stream that applies backpressure — is the streaming
host: it must then enter `handle-request` through `WebAssembly.promising` and
serialise its calls, because a suspended module can be re-entered — the module
refuses that with a trap rather than corrupting both calls, and the build prints
the obligation.

Under `--component` both bodies stay inside the envelope: a component's host
functions cross the canonical ABI rather than a core import. So does a plain
WASI command module that drives its own `dispatch` in-process (what the
examples' `check.lisp` files do), whose host is `wasmtime run` and satisfies no
`env.*` import. Everything above this section is unchanged either way, which is
the point of the source and the sink being abstract values.

What the application then sees is the `:raw-body` mode. `clackup` and `handle`
ask for the buffered one, the synchronous stream Clack promises (the source is
drained into it whatever shape it had). A reactor built from a bare
`rontolisp:http-handler` keeps **that directive's** default instead — a
rontolisp stream, drained the same way as on every other backend:

```lisp
(rontolisp:async-defun handle (env)
  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
    (list 200 '(:content-type "text/plain") (list body))))
```

A complete Worker built this way — the JavaScript side and the measurements
included — is
[`examples/cloudflare-workers/httpbin-clack/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin-clack).
Beside it,
[`examples/cloudflare-workers/httpbin-clack-one-source/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin-clack-one-source)
deploys
[`examples/net/httpbin-clack.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/httpbin-clack.lisp)
*itself* — the file that binds a socket when you interpret it — and so contains
no Lisp file at all: one source, four hosts.

If the module size matters more than the `clackup` line, this adapter is small
enough to write out by hand and skip loading clack entirely.
[`examples/cloudflare-workers/httpbin/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin)
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
server, and [`examples/asdf/clack-hello.lisp`](https://github.com/making/rontolisp/blob/develop/examples/asdf/clack-hello.lisp)
for the runnable demo with all per-backend commands.
