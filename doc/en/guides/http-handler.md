# Serving HTTP (http-handler)

Hand-rolling HTTP over `read-line`/`write-line` (as the
[TCP Sockets guide](tcp-sockets.md) demonstrates with `http-hello.lisp`) is
instructive, but for a plain request/response server
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
does the parsing for you. You write a handler that takes a request property
list (`:method` / `:path` / `:query` / `:headers` / `:body`) and returns a
response property list (`:status` / `:headers` / `:body`) — the same value
model as
[`rontolisp:fetch`](http-fetch.md), just incoming instead of outgoing:

```console
(defun handle (request)
  (list :status 200
        :headers '(("content-type" . "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

(rontolisp:http-handler 'handle 8080)
```

Save it as `app.lisp` (also shipped as
[`examples/net/http-handler.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/http-handler.lisp)),
then run it on any of the three supported backends below.

## On the interpreter

`http-handler` starts a blocking embedded HTTP server on port 8080 (one
virtual thread per request) and serves until the process is stopped with
`Ctrl-C`:

```console
$ rontolisp app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Compiled to a JVM class

The same source compiles to a **JVM class** serving the same way. Unlike
other compiled rontolisp programs, the class is not self-contained: it
implements the embedded server's handler interface, so the rontolisp
executable JAR (`rontolisp-0.1.0-SNAPSHOT-exec.jar`, the same download as in
[Build & Install](../getting-started/build.md)) must be on the classpath when
running it:

```console
$ rontolisp app.lisp -o App.class
$ java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Compiled to a WASI HTTP component

It also compiles to a **WASI HTTP component** that runs under
`wasmtime serve` (wasmtime 46+):

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

There the module exports `wasi:http/handler@0.3.0` (the async WASI 0.3 HTTP
world) and the host owns the socket, so the `port` argument is ignored. The
flags enable what the component actually uses: the WebAssembly GC proposal
(`-W gc=y`) and the exception-handling proposal (`-W exceptions=y`, which the
Lisp-written HTTP glue uses to detect end-of-body). The handler is lifted as a
**callback async** export: a handler that suspends (awaiting a timer, a fetch
or a body read) hands control back to the host, which delivers each completion
event through the component's callback — all of it part of the base
component-model async ABI, which is default-on in wasmtime 46+, so no gated
feature flags are needed. The response is still delivered mid-task through
`canon task.return`, and the body streams after it.

## Other WASI HTTP runtimes

The component asks its host for `wasi:http` **0.3** (async) plus wasm-GC.
wasmtime 46+ serves it, and so does **wasmCloud**: the released `wash` (2.5.2)
runs it with `wash dev`, given
`dev.wasm_proposals: [gc, exception-handling, component-model-async]` in the
project manifest. **jco** and **Spin** cannot run it yet: jco does not
implement the 0.3 async ABI, and Spin's embedded wasmtime does not enable the
WebAssembly GC proposal that every rontolisp component needs.

## Query strings

`:path` carries the path only, so route comparisons are exact. When the
request has a query string it arrives separately under `:query` — the raw
text after the `?`, or `nil` when there is none. Parse it with the
query-string functions of the URL library,
[`rontolisp:query-param`](../reference/functions/rontolisp-query-param.md) and
[`rontolisp:query-params`](../reference/functions/rontolisp-query-params.md)
(both url-decode keys and values, and both accept `nil`):

```console
(defun handle (request)
  (list :status 200
        :body (format nil "Hello, ~a!~%"
                      (or (rontolisp:query-param (getf request :query) "name")
                          "world"))))

(rontolisp:http-handler 'handle 8080)
```

```console
$ curl 'http://127.0.0.1:8080/greet?name=ronto%20lisp'
Hello, ronto lisp!
$ curl http://127.0.0.1:8080/greet
Hello, world!
```

## Calling other services from a handler

[`rontolisp:fetch`](http-fetch.md) works inside a served handler on all three
backends, enabling the classic proxy / aggregator shape. A handler that awaits
is an asynchronous function, so define it with
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
instead of `defun`:

```console
(rontolisp:async-defun handle (request)
  (let ((res (rontolisp:await
              (rontolisp:fetch "http://127.0.0.1:9000/upstream"))))
    (list :status (getf res :status)
          :body (getf res :body))))

(rontolisp:http-handler 'handle 8080)
```

On the WASI component backend the outgoing-request machinery rides along in
the same component — serve and serve+fetch are one component shape, importing
`wasi:http/client@0.3.0`, which `wasmtime serve` provides by default (no
`-S http=y` needed):

```console
$ rontolisp proxy.lisp -o proxy.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y proxy.wasm
```

A complete example is
[`examples/net/dog-fetcher.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/dog-fetcher.lisp),
a reproduction of
[wasmCloud's dog-fetcher example](https://wasmcloud.com/docs/v1/examples/rust/component/dog-fetcher/):
every request fetches a random dog picture URL from the dog.ceo API and
answers it as JSON.

## Keeping State: a store, not a global

On the interpreter and the JVM the server is one long-lived process, so a global
hash table survives between requests. **A served component's does not**: a
`wasi:http` host instantiates the component afresh for every request, so anything
the handler wrote reads back empty on the next one.

The way to keep state is therefore to put it outside the component — in a WIT
interface the handler *calls*, bound with
[`rontolisp:wit-import`](../reference/functions/rontolisp-wit-import.md). A served
component imports it alongside its fixed `wasi:http` surface:

```console
(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0-draft"
                      :package kv)

(defun handle (request)
  (let* ((page (getf request :path))
         (bucket (kv:open ""))
         (seen (kv:bucket-get bucket page))
         (hits (+ 1 (if seen (parse-integer seen) 0))))
    (kv:bucket-set bucket page (princ-to-string hits))
    (list :status 200 :body (format nil "~a: ~a hits~%" page hits))))

(rontolisp:http-handler 'handle 8080)
```

```console
$ rontolisp page-hits-server.lisp -o server.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
```

The same source runs on the interpreter and the JVM, where a
[provider](../reference/functions/rontolisp-wit-provide.md) written in Lisp
answers the interface instead. Whether the counts *survive* on a component is the
host's business: wasmtime's built-in key-value provider is an in-memory store it
rebuilds per instance (so, under `wasmtime serve`, per request), while a host that
links an out-of-process provider — wasmCloud (`wash dev`), say — keeps them. The
worked example is
[`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/main/examples/wit/keyvalue).

## Limitations

Request and response headers are marshalled on every backend, including the WASI
component: the handler reads `:headers` (an alist of `(name . value)` string pairs)
from the request and any `:headers` in the response is written back.

Inside a served component handler, `random`, the time built-ins and `print`
(to the host's stdout) all work — the component bridges them to the
`wasi:random`, `wasi:clocks` and `wasi:cli` interfaces every `wasi:http` host
provides. `uiop:getenv` reads the host environment too — a served component
imports `wasi:cli/environment@0.3.0`, so `wasmtime serve --env NAME=value`
(or `-S inherit-env=y`) reaches the handler — and file streams are
unavailable. See the
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
reference page for the details.

For the *client* side of HTTP, use `rontolisp:fetch` — see the
[HTTP Requests guide](http-fetch.md). To work at the raw socket level instead
(any TCP protocol, or TLS), see the [TCP Sockets guide](tcp-sockets.md).
