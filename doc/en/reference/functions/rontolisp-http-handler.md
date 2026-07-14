# rontolisp:http-handler

`(rontolisp:http-handler handler &optional port)`

Serves HTTP requests with a Lisp handler function. `handler` is a quoted symbol
naming a one-argument function (like [`rontolisp:wasm-export`](rontolisp-wasm-export.md)).
The handler receives a request property list and returns a response property
list, mirroring the shape of [`rontolisp:fetch`](rontolisp-fetch.md) so one HTTP
value model spans incoming and outgoing requests:

- **request** — `(:method <string> :path <string> :query <string-or-nil>
  :headers <alist> :body <string>)`. `:path` is the path only, with the query
  string stripped; `:query` is the raw query string without the leading `?`
  (`"a=1&b=2"` for `/get?a=1&b=2`), or `nil` when the request has none — parse
  it with [`rontolisp:query-param`](rontolisp-query-param.md) /
  [`rontolisp:query-params`](rontolisp-query-params.md).
- **response** — `(:status <integer> :headers <alist> :body <string>)`. Missing
  keys default to `:status 200` and an empty body.

On the **interpreter** and **JVM** backends `http-handler` starts a blocking
embedded HTTP server on `port` (default `8080`, one virtual thread per request)
and serves until the process is stopped (Ctrl-C). Compiled to a **WASI
component** (`--component`) it instead exports `wasi:http/incoming-handler`, so
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
$ wasmtime serve -W gc=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Backend support

`http-handler` runs on the **interpreter** backend (a blocking server), the
**JVM** backend (the same blocking server; the compiled class needs the
rontolisp executable JAR, `rontolisp-0.1.0-SNAPSHOT-exec.jar`, on the
classpath) and the **WASI component** backend
(`--component`, a `wasi:http/incoming-handler` component for `wasmtime serve`).
On the WASI component backend, request and response headers are not marshalled
yet: the handler sees `:headers nil` and `:headers` in the response is ignored.
The interpreter and the JVM backend pass headers through. Inside a served
handler `random`, the time built-ins and `print` (to the host's stdout) work —
they are bridged to `wasi:random` / `wasi:clocks` / `wasi:cli`, which every
`wasi:http` host provides; `getenv` returns `nil` and file streams are
unavailable. [`rontolisp:fetch`](rontolisp-fetch.md) also works inside a
served handler (the component then additionally imports
`wasi:http/outgoing-handler`), so proxy-style handlers run on every backend —
grant the host outbound HTTP, e.g. `wasmtime serve -W gc=y -S http=y`.

A handler may also call a WIT interface of its own with
[`rontolisp:wit-import`](rontolisp-wit-import.md), which the served component
imports alongside its fixed `wasi:http` surface. That is how a served handler
keeps **state**: a `wasi:http` host instantiates the component afresh for every
request, so a global hash table reads back empty every time, while a
`wasi:keyvalue` store lives outside it.

The serve component is plain WASI 0.2, so it is not tied to wasmtime: any host
that serves `wasi:http` 0.2 and enables the WebAssembly GC proposal can run it —
jco (`jco serve` on Node.js, where wasm-GC is on by default) and wasmCloud
(`wash` with the `gc` wasm proposal enabled) both work. Spin (`spin up`) cannot
run the component yet: Spin's embedded wasmtime does not enable the GC proposal
and offers no flag to turn it on.

See the [Serving HTTP guide](../../guides/http-handler.md) for the full
example and the per-runtime commands.
