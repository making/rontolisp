# rontolisp:fetch

`(rontolisp:fetch url &optional options)`

Starts an outgoing HTTP request, modeled on the JavaScript `fetch` API, and
immediately returns a **promise** — an opaque handle — while the request runs
asynchronously. Pass the promise to
[`rontolisp:await`](rontolisp-await.md) to block until the response arrives and
obtain the result property list
`(:status <integer> :body <string> :headers <alist>)`.

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

`fetch` itself returns the promise. Awaiting it yields the property list
`(:status <integer> :body <string> :headers <alist>)`, where `:headers` is an
alist of `(name . value)` response-header pairs:

```console
(let ((res (rontolisp:await (rontolisp:fetch "http://example.com/"))))
  (print (getf res :status))    ; => 200
  (print (getf res :body))      ; => "<html>...</html>"
  (print (getf res :headers)))  ; => (("content-type" . "text/html") ...)
```

## Backend support

- **Interpreter** and **JVM**: use the JDK `java.net.http.HttpClient`; the
  request runs on a background thread from the moment `fetch` returns.
- **WASM**: component-only, and a **hybrid** — the base I/O is WASI 0.3 but
  fetch imports `wasi:http@0.2` + `wasi:io@0.2` (async `wasi:http@0.3` does not
  exist upstream yet; see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md`). The
  promise is the in-flight `wasi:http` response handle, so multiple requests
  genuinely overlap. Compile with `--component` and run with `-S http=y` plus
  the async flags. fetch and await remain compile errors in Preview 1
  (core-module) mode, which has no host `wasi:http`.
- **Browser playground**: the request is performed synchronously (via
  `XMLHttpRequest`) when `fetch` is called and an already-settled promise is
  returned, so programs behave the same but requests do not overlap.

## Limitations

- The method must be one of `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS`,
  `PATCH`. An unsupported `:method` is an error: the interpreter and JVM reject
  it at `fetch` time; the WASM backend resolves the method statically and
  rejects a statically-known unsupported `:method` at compile time (a method
  computed at runtime cannot be checked there and is treated as GET, while a
  runtime-computed `:body` is sent normally).
- A failed request (for example a refused connection) surfaces when the promise
  is awaited — the same timing as a JavaScript `await` rejection: it raises an
  error in the interpreter and JVM, and `await` returns `nil` in WASM. In WASM
  a request that cannot even be started (for example a malformed URL) makes
  `fetch` return `nil` instead of a promise, and awaiting `nil` yields `nil`.
- In WASM, the response body is capped (about 576 KiB) and very large programs
  may exhaust the shared linear memory the response buffers reuse.
