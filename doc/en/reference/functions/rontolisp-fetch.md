# rontolisp:fetch

`(rontolisp:fetch url &optional options)`

Starts an outgoing HTTP request, modeled on the JavaScript `fetch` API, and
immediately returns a **future** while the request runs asynchronously. A
future is an opaque value (it prints as `#<FUTURE>` and satisfies
[`rontolisp:futurep`](rontolisp-futurep.md)); pass it to
[`rontolisp:await`](../special-forms/rontolisp-await.md) to block until the
response arrives and obtain the result property list
`(:status <integer> :headers <alist> :body <stream>)`, or chain a callback
with [`rontolisp:then`](rontolisp-then.md).

```lisp
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

Because the request is already in flight when `fetch` returns, several requests
can overlap:

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))  ; both requests running
  (list (rontolisp:await p1) (rontolisp:await p2)))
```

## Options

The optional second argument is an options property list. Recognized keys:

- `:method` — the HTTP method as a string (default `"GET"`). Supported methods
  are `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS` and `PATCH`, matched
  case-insensitively; any other method is an error.
- `:headers` — request headers, an alist of `(name . value)` string pairs.
- `:body` — the request body as a string (omit for no body).

The options are validated when `fetch` is called (like the JavaScript `fetch`,
which throws synchronously on invalid arguments).

```console
;; GET with request headers (an alist of (name . value) string pairs)
(rontolisp:fetch "http://example.com/api"
                 (list :headers (list (cons "Accept" "application/json"))))

;; POST with a request body
(rontolisp:fetch "http://example.com/api"
                 (list :method "POST"
                       :headers (list (cons "Content-Type" "application/json"))
                       :body "{\"name\":\"rontolisp\"}"))
```

## Result

`fetch` itself returns the future. Awaiting it yields the property list
`(:status <integer> :headers <alist> :body <stream>)`, where `:headers` is an
alist of `(name . value)` response-header pairs and `:body` is an
**asynchronous stream** of the body's chunks — drain it to one string with
[`rontolisp:read-all`](rontolisp-read-all.md) (or take the chunks one at a
time with [`rontolisp:stream-read`](rontolisp-stream-read.md)):

```console
(let ((res (rontolisp:await (rontolisp:fetch "http://example.com/"))))
  (print (getf res :status))    ; => 200
  (print (rontolisp:await (rontolisp:read-all (getf res :body))))
                                ; => "<html>...</html>"
  (print (getf res :headers)))  ; => (("content-type" . "text/html") ...)
```

> **Backend note.** The stream-valued `:body` is the interpreter/JVM contract.
> Under `--component` the response currently carries the whole body as one
> string in `:body` (the component streaming body lands later), so read it
> there with a plain `(getf res :body)`.

A JSON response body parses into Lisp values with
[`rontolisp:json-parse`](rontolisp-json-parse.md), and
[`rontolisp:json-stringify`](rontolisp-json-stringify.md) builds a JSON
request `:body` from an s-expression.

## Backend support

- **Interpreter** and **JVM**: use the JDK `java.net.http.HttpClient`; the
  request runs on a background thread from the moment `fetch` returns.
- **WASM**: component-only, over the async `wasi:http@0.3.0` — fetch is
  ordinary Lisp glue calling the wit-imported `wasi:http/client@0.3.0`, so the
  component is uniformly WASI 0.3. The future wraps the in-flight async
  `client.send` subtask, so multiple requests genuinely overlap. Compile with
  `--component` and run with
  `wasmtime run -W gc=y -W exceptions=y -S http=y`
  (wasmtime 46+; `-S http=y` makes the host provide `wasi:http`). fetch remains
  a compile error in Preview 1 (core-module) mode, which has no host
  `wasi:http`; the generic future operations (`await`, `then`, `futurep`)
  compile in every mode. fetch also works inside a
  [`rontolisp:http-handler`](rontolisp-http-handler.md) serve component (a
  proxy-style handler): run it with `wasmtime serve -W gc=y -W exceptions=y` —
  the serve host provides `wasi:http/client` by default, no `-S http=y` needed.
- **Browser playground**: truly asynchronous. The interpreter runs in a Web
  Worker; `fetch` hands the request to the page's main thread, which runs the
  real browser `fetch()` (subject to CORS) while the program continues, so
  requests overlap, and `await` blocks the worker until the response arrives.
  When cross-origin isolation is unavailable (`SharedArrayBuffer` disabled)
  the playground falls back to a synchronous request per fetch — programs
  behave the same, requests simply do not overlap.

## Limitations

- The method must be one of `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS`,
  `PATCH`. An unsupported `:method` is an error: the interpreter and JVM reject
  it at `fetch` time; the WASM backend resolves the method statically and
  rejects a statically-known unsupported `:method` at compile time (a method
  computed at runtime cannot be checked there and is treated as GET, while a
  runtime-computed `:body` is sent normally).
- A failed request (for example a refused connection) surfaces when the future
  is awaited — the same timing as a JavaScript `await` rejection: every backend
  signals an error there (on WASM it is a `rontolisp:wit-error` condition,
  catchable with `handler-case`). A request that cannot even be *started* (for
  example a malformed URL, or an unsupported runtime-computed method on the
  interpreter/JVM) makes `fetch` itself error or, on WASM, return `nil` instead
  of a future — and awaiting `nil` yields `nil`.
