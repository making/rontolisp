# rontolisp:http-handler

`(rontolisp:http-handler handler &optional port)`

Serves HTTP requests with a Lisp handler function. `handler` is a quoted symbol
naming a one-argument function (like [`rontolisp:wasm-export`](rontolisp-wasm-export.md)).
The handler receives a request property list and returns a response property
list, mirroring the shape of [`rontolisp:fetch`](rontolisp-fetch.md) so one HTTP
value model spans incoming and outgoing requests:

- **request** — `(:method <string> :path <string> :query <string-or-nil>
  :headers <alist> :body <stream>)`. `:path` is the path only, with the query
  string stripped; `:query` is the raw query string without the leading `?`
  (`"a=1&b=2"` for `/get?a=1&b=2`), or `nil` when the request has none — parse
  it with [`rontolisp:query-param`](rontolisp-query-param.md) /
  [`rontolisp:query-params`](rontolisp-query-params.md). `:body` is an
  asynchronous stream; a handler that reads it drains it with
  `(rontolisp:await (rontolisp:read-all (getf request :body)))` and must be an
  [`rontolisp:async-defun`](../special-forms/rontolisp-async-defun.md).
- **response** — `(:status <integer> :headers <alist> :body
  <string-or-stream>)`. Missing keys default to `:status 200` and an empty
  body; a stream body (e.g. a proxied fetch response's `:body`) is drained by
  the server.

On the **interpreter** and **JVM** backends `http-handler` starts a blocking
embedded HTTP server on `port` (default `8080`, one virtual thread per request)
and serves until the process is stopped (Ctrl-C). Compiled to a **WASI
component** (`--component`) it instead exports `wasi:http/handler@0.3.0`, so
the module runs as a serverless HTTP component under `wasmtime serve` (the
`port` argument is ignored — the host owns the socket).

```console
(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

(rontolisp:http-handler 'handle 8080)
```

Run it on the interpreter, then talk to it with `curl`:

```console
$ rontolisp app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

Compile it to a JVM class (the class implements the embedded server's handler
interface, so the rontolisp executable JAR must be on the classpath when
running it — this is the one step that needs the JAR instead of the native
binary):

```console
$ rontolisp app.lisp -o App.class
$ java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

Or compile it to a WASI HTTP component and serve it with `wasmtime serve`:

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Backend support

`http-handler` runs on the **interpreter** backend (a blocking server), the
**JVM** backend (the same blocking server; the compiled class needs the
rontolisp executable JAR, `rontolisp-0.1.0-SNAPSHOT-exec.jar`, on the
classpath) and the **WASI component** backend
(`--component`, a `wasi:http/handler@0.3.0` component for `wasmtime serve`).
Request and response headers are marshalled on every backend, the WASI component
included: the handler reads `:headers` (an alist of `(name . value)` string pairs)
and any `:headers` in the response is written back. Inside a served
handler `random`, the time built-ins and `print` (to the host's stdout) work —
they are bridged to `wasi:random` / `wasi:clocks` / `wasi:cli`, which every
`wasi:http` host provides; `getenv` returns `nil` and file streams are
unavailable. [`rontolisp:fetch`](rontolisp-fetch.md) also works inside a
served handler — serve and serve+fetch are one component shape, whose
`wasi:http/client@0.3.0` import `wasmtime serve` provides by default — so
proxy-style handlers run on every backend with the same serve command. A
handler that awaits (fetch inside serve, say) is an asynchronous function and
must be defined with
[`rontolisp:async-defun`](../special-forms/rontolisp-async-defun.md) rather
than `defun`: `rontolisp:await` is legal only inside asynchronous bodies.

A handler may also call a WIT interface of its own with
[`rontolisp:wit-import`](rontolisp-wit-import.md), which the served component
imports alongside its fixed `wasi:http` surface. That is how a served handler
keeps **state**: a `wasi:http` host instantiates the component afresh for every
request, so a global hash table reads back empty every time, while a
`wasi:keyvalue` store lives outside it.

The serve component targets the async `wasi:http@0.3.0` (`service` world); its
handler is a callback async lift over the base component-model async ABI,
default-on in wasmtime 46+, so `wasmtime serve` needs no gated feature flags.
wasmCloud hosts it too: the released `wash` (2.5.2) runs it with `wash dev`,
given `dev.wasm_proposals: [gc, exception-handling, component-model-async]`.
jco does not implement the 0.3 async ABI, and Spin's embedded wasmtime does
not enable the WebAssembly GC proposal that every rontolisp component needs.

See the [Serving HTTP guide](../../guides/http-handler.md) for the full
example and the per-runtime commands.
