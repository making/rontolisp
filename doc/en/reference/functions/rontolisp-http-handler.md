# rontolisp:http-handler

`(rontolisp:http-handler handler &optional port &key raw-body)`

Serves HTTP requests with a Lisp handler function. `handler` is a quoted symbol
naming a one-argument function (like [`rontolisp:wasm-export`](rontolisp-wasm-export.md)).
The handler receives the Clack environment property list and returns the Clack
response list `(status headers body)` — a Clack application is a valid handler
as is (see [Clack Web Applications](../../guides/clack.md)):

- **environment** — a property list with exactly these keys, always all
  present: `:request-method` (an upcased interned keyword, `:GET` / `:POST` /
  ..., so `(eq m :POST)` works), `:script-name` (the application's mount point,
  percent-decoded — `""` everywhere but a Servlet war deployed under a context
  path), `:path-info` (the percent-decoded path, with the mount point stripped
  first), `:query-string` (the raw text after the first
  `?`, or `nil` — parse it with
  [`rontolisp:query-param`](rontolisp-query-param.md) /
  [`rontolisp:query-params`](rontolisp-query-params.md)), `:server-name`,
  `:server-port` (an integer), `:server-protocol` (a keyword, e.g.
  `:HTTP/1.1`), `:request-uri` (the raw request target verbatim, still
  encoded, query included), `:url-scheme` (`"http"`/`"https"`),
  `:remote-addr` / `:remote-port` (the real peer on the interpreter/JVM;
  `nil` on the WASI component), `:headers` (an `equal` hash table keyed by
  lowercased names, repeated headers joined with `", "`, never `nil` —
  `(gethash "content-type" (getf env :headers))`), `:content-type` and
  `:content-length` (string / integer, or `nil`), and `:raw-body`.
- **`:raw-body`** — by default (`:raw-body :stream`) an asynchronous stream;
  a handler that reads it drains it with
  `(rontolisp:await (rontolisp:read-all (getf env :raw-body)))` and must be an
  [`rontolisp:async-defun`](../special-forms/rontolisp-async-defun.md). With
  the directive argument `(rontolisp:http-handler 'handle 8080 :raw-body
  :buffered)` the body is instead read in full and handed over as a
  synchronous in-memory bivalent stream readable with `read-line`/`read-char`
  and `read-byte`/`read-sequence`, with a real `file-position` — what a Clack
  application (lack-request, http-body) needs; a bodiless request then gets
  `:raw-body nil`.
- **response** — the positional list `(status headers body)`. `status` is a
  required integer (a non-integer car signals an error). `headers` is a
  keyword plist (`'(:content-type "text/plain")`) or a dotted alist (so a
  [`rontolisp:fetch`](rontolisp-fetch.md) result's `:headers` passes straight
  through); repeated names each become their own header line, `content-length`
  / `transfer-encoding` are dropped (the server computes them), and `nil` is
  fine. `body` is a list of strings (joined), `nil`/omitted (an empty body —
  the two-element `(status headers)` form is valid), an `(unsigned-byte 8)`
  vector, or a rontolisp stream (e.g. a proxied fetch body) drained by the
  server; a **bare string signals an error** (a rontolisp pathname is its
  namestring, and in Clack a pathname body means "serve this file"). A
  function response is supported in Clack's delayed form only —
  `(lambda (responder) ... (funcall responder (list 200 nil (list "later"))))`
  — and the streaming-writer form is refused.

On the **interpreter** and **JVM** backends `http-handler` starts a blocking
embedded HTTP server on `port` (default `8080`, one virtual thread per request)
and serves until the process is stopped (Ctrl-C). The listener binds the
wildcard address (`0.0.0.0`, dual-stack); there is no address argument, so a
program that must choose its bind address serves through
[`clack:clackup`](../../guides/clack.md) and its `:address` instead. Compiled to a **WASI
component** (`--component`) it instead exports `wasi:http/handler@0.3.0`, so
the module runs as a serverless HTTP component under `wasmtime serve` (the
`port` argument is ignored — the host owns the socket). Compiled to a
**Servlet war** (`-o app.war`) it registers the handler with the servlet
container instead of binding a socket, and the `port` argument is likewise
ignored — see the [HTTP guide](../../guides/http-handler.md#compiled-to-a-servlet-war).

```console
(defun handle (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf env :request-method) (getf env :path-info)))))

(rontolisp:http-handler 'handle 8080)
```

Run it on the interpreter, then talk to it with `curl`:

```console
$ rontolisp app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

Compile it to a JVM class (self-contained: the embedded server travels beside
the class, under `am/ik/rontolisp/runtime/`, so nothing else goes on the
classpath — `-o app.jar` packages the two together):

```console
$ rontolisp app.lisp -o App.class
$ java -cp . App
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

Or compile it to a WASI HTTP component and serve it with `wasmtime serve`:

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Backend support

`http-handler` runs on the **interpreter** backend (a blocking server), the
**JVM** backend (the same blocking server, travelling beside the compiled
class so it needs nothing else on the classpath) and the **WASI component**
backend
(`--component`, a `wasi:http/handler@0.3.0` component for `wasmtime serve`).
Request and response headers are marshalled on every backend, the WASI component
included: the handler reads `:headers` (an `equal` hash table keyed by
lowercased names) and the response's `headers` element is written back. Inside a served
handler `random`, the time built-ins and `print` (to the host's stdout) work —
they are bridged to `wasi:random` / `wasi:clocks` / `wasi:cli`, which every
`wasi:http` host provides; `uiop:getenv` reads the host environment through the
component's own `wasi:cli/environment@0.3.0` import (`wasmtime serve
--env NAME=value` or `-S inherit-env=y`), and file streams are
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
So does **Spin**, from the
[canary build](https://github.com/spinframework/spin/releases/tag/canary)
(4.1.0-pre0) on, with a plain
`spin.toml` and no flags; released Spin 4.0.2 cannot, because its wasmtime 44
speaks the `wasi:http@0.3.0-rc-2026-03-15` snapshot instead of the released
0.3.0. jco does not implement the 0.3 async ABI.

See the [Serving HTTP guide](../../guides/http-handler.md) for the full
example and the per-runtime commands.
