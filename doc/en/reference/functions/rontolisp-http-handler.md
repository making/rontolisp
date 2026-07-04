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
until the process is stopped (Ctrl-C).

```console
(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

(rontolisp:http-handler 'handle 8080)
```

Run it, then talk to it with `curl`:

```console
$ java -jar rontolisp.jar app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Backend support

`http-handler` currently runs on the **interpreter** backend only. The **JVM**
backend and the **WASI component** backend (compiling the handler into a
`wasi:http/incoming-handler` component that runs under `wasmtime serve` and Spin)
are in progress; they signal a clear error at compile time for now.

See [Serving HTTP](../../guides/tcp-sockets.md) for the full example.
