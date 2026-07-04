# rontolisp:http-handler

`(rontolisp:http-handler handler &optional port)`

Serves HTTP requests with a Lisp handler function. `handler` is a quoted symbol
naming a one-argument function (like [`rontolisp:wasm-export`](rontolisp-wasm-export.md)).
The handler receives a request property list and returns a response property
list, mirroring the shape of [`rontolisp:fetch`](rontolisp-fetch.md) so one HTTP
value model spans incoming and outgoing requests:

- **request** — `(:method <string> :path <string> :headers <alist> :body <string>)`
- **response** — `(:status <integer> :headers <alist> :body <string>)`. Missing
  keys default to `:status 200` and an empty body.

On the **interpreter** backend `http-handler` starts a blocking embedded HTTP
server on `port` (default `8080`, one virtual thread per request) and serves
until the process is stopped (Ctrl-C). Compiled to a **WASI component**
(`--component`) it instead exports `wasi:http/incoming-handler`, so the module
runs as a serverless HTTP component under `wasmtime serve` (the `port` argument
is ignored — the host owns the socket).

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
$ java -jar rontolisp.jar app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

Or compile it to a WASI HTTP component and serve it with `wasmtime serve`:

```console
$ java -jar rontolisp.jar app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y -W component-model-async=y \
    -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
    app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Backend support

`http-handler` runs on the **interpreter** backend (a blocking server) and the
**WASI component** backend (`--component`, a `wasi:http/incoming-handler`
component for `wasmtime serve`). The **JVM** backend is in progress and signals a
clear error at compile time for now.

Spin (`spin up`) cannot run the component yet: Spin's embedded wasmtime does not
enable the WebAssembly GC proposal, which every rontolisp component requires, so
use `wasmtime serve -W gc=y ...`.

See [Serving HTTP](../../guides/tcp-sockets.md) for the full example.
