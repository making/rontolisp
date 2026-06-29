# rontolisp:fetch

`(rontolisp:fetch url &optional options)`

Performs an outgoing HTTP request, modeled on the JavaScript `fetch` API, and
returns a property list `(:status <integer> :body <string> :headers <alist>)`,
where `:headers` is an alist of `(name . value)` response-header pairs.

```lisp
(let ((res (rontolisp:fetch "https://httpbin.org/get")))
  (getf res :status))   ; => 200
```

## Options

The optional second argument is an options property list. Recognized keys:

- `:method` — the HTTP method as a string (default `"GET"`). Supported methods
  are `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS` and `PATCH`, matched
  case-insensitively; any other method is an error.
- `:headers` — request headers, an alist of `(name . value)` string pairs.
- `:body` — the request body as a string (omit for no body).

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

The result is a property list `(:status <integer> :body <string> :headers
<alist>)`, where `:headers` is an alist of `(name . value)` response-header
pairs:

```console
(let ((res (rontolisp:fetch "http://example.com/")))
  (print (getf res :status))    ; => 200
  (print (getf res :body))      ; => "<html>...</html>"
  (print (getf res :headers)))  ; => (("content-type" . "text/html") ...)
```

## Backend support

- **Interpreter** and **JVM**: use the JDK `java.net.http.HttpClient`.
- **WASM**: component-only, and a **hybrid** — the base I/O is WASI 0.3 but
  fetch imports `wasi:http@0.2` + `wasi:io@0.2` (async `wasi:http@0.3` does not
  exist upstream yet; see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md`). Compile
  with `--component` and run with `-S http=y` plus the async flags. It remains a
  compile error in Preview 1 (core-module) mode, which has no host `wasi:http`.

## Limitations

- The method must be one of `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS`,
  `PATCH`. An unsupported `:method` is an error: the interpreter and JVM reject
  it at runtime; the WASM backend resolves the method statically and rejects a
  statically-known unsupported `:method` at compile time (a method computed at
  runtime cannot be checked there and is treated as GET, while a runtime-computed
  `:body` is sent normally).
- A failed request (for example a refused connection) raises an error in the
  interpreter and JVM, and returns `nil` in WASM.
- In WASM, the response body is capped (about 576 KiB) and very large programs
  may exhaust the shared linear memory the response buffers reuse.
