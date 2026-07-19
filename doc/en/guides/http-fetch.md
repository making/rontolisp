# HTTP Requests (fetch)

The `rontolisp` package provides outgoing HTTP modeled on the JavaScript
`fetch` API, plus the JSON functions that pair naturally with it. None of
these are part of Common Lisp; reference them with the `rontolisp:` qualifier
(see [Packages](../reference/packages.md)). `rontolisp:fetch` starts a request
and immediately returns a **future**; `rontolisp:await` resolves it (inside an
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
or at top level), and `rontolisp:json-parse` / `rontolisp:json-stringify`
convert between JSON documents and Lisp values.

| Function | Purpose |
|----------|---------|
| [`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md) | Start an HTTP request: `(rontolisp:fetch url &optional options)` |
| [`rontolisp:await`](../reference/special-forms/rontolisp-await.md) | Suspend until a future settles and return its value |
| [`rontolisp:futurep`](../reference/functions/rontolisp-futurep.md) | `t` if a value is a future |
| [`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md) | Drain a body stream's chunks into one string (async) |
| [`rontolisp:json-parse`](../reference/functions/rontolisp-json-parse.md) | Parse a JSON string into Lisp values |
| [`rontolisp:json-stringify`](../reference/functions/rontolisp-json-stringify.md) | Serialize a Lisp value to a JSON string |

> **Backend support.** The interpreter and JVM-compiled classes use the JDK
> `java.net.http.HttpClient`; the request runs on a background thread from the
> moment `fetch` returns. The WASM backend is **component-only**
> (`--component`, importing the async `wasi:http@0.3.0`): `fetch` is a compile
> error in Preview 1 (core-module) mode, and a fetch component must run with
> `-S http=y` on top of the usual flags. In the **browser playground** `fetch`
> runs the real browser `fetch()` (subject to CORS) while the program
> continues. `await`, `futurep` and the JSON functions work on **every**
> backend and in every WASM mode — only `fetch` itself is restricted.

## A first request

`fetch` returns as soon as the request is in flight. Passing the future to
`rontolisp:await` suspends until the response arrives and yields the result
property list `(:status <integer> :headers <alist> :body <stream>)` — on
every backend `:body` is an asynchronous stream, drained with
[`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md):

```lisp
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

Reading the individual fields:

```console
(let ((res (rontolisp:await (rontolisp:fetch "http://example.com/"))))
  (print (getf res :status))    ; => 200
  (print (rontolisp:await (rontolisp:read-all (getf res :body))))
                                ; => "<html>...</html>"
  (print (getf res :headers)))  ; => (("content-type" . "text/html") ...)
```

## Request options

The optional second argument is an options property list with `:method`
(a string, default `"GET"`), `:headers` (an alist of `(name . value)` string
pairs) and `:body` (a string):

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

The supported methods are `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS`
and `PATCH`; see the [fetch](../reference/functions/rontolisp-fetch.md)
reference page for validation timing and error behavior per backend (a failed
request surfaces at `await`, not at `fetch` — every backend signals an error
there; `nil` comes back only for a request that cannot be *started*).

## Futures

Because the request is already running when `fetch` returns, several requests
overlap — start them all, then await each (in any order):

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))  ; both requests running
  (list (rontolisp:await p1) (rontolisp:await p2)))
```

To transform a response — or to build any asynchronous helper — define an
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md):
its body runs eagerly to the first pending `await`, and the caller gets a
future for the rest:

```console
(rontolisp:async-defun fetch-status (url)
  (getf (rontolisp:await (rontolisp:fetch url)) :status))

(rontolisp:await (fetch-status "https://httpbin.org/get"))   ; => 200
```

`await` is generic: a non-future value passes through unchanged, and a settled
future can be awaited any number of times.
[`rontolisp:futurep`](../reference/functions/rontolisp-futurep.md) tells
futures apart from plain values:

```lisp
(rontolisp:await 42)   ; => 42
```

```lisp
(rontolisp:futurep (rontolisp:fetch "https://httpbin.org/get"))   ; => t
```

## Working with JSON

`rontolisp:json-parse` turns a JSON document into Lisp values following
[`com.inuoe.jzon`](asdf-systems.md)'s defaults: a JSON object becomes a hash
table with string keys, an array a vector, and `true`/`false`/`null` become
`t`/`nil`/the symbol `null`:

```lisp
(gethash "name" (rontolisp:json-parse "{\"name\": \"rontolisp\", \"n\": 2}"))   ; => "rontolisp"
```

```lisp
(gethash "b" (gethash "a" (rontolisp:json-parse "{\"a\": {\"b\": [1, true, null]}}")))   ; => #(1 t null)
```

`rontolisp:json-stringify` is the inverse: a hash table becomes an object, a
vector or list an array, and `nil`/`t`/the symbol `null` become
`false`/`true`/`null`:

```lisp
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "name" h) "rontolisp")
  (rontolisp:json-stringify h))   ; => "{"name":"rontolisp"}"
```

```lisp
(rontolisp:json-stringify (list 1 (list 2 3) nil))   ; => "[1,[2,3],false]"
```

Both functions are written in rontolisp itself and compile into the program
on every backend, and are a lightweight subset of jzon — a program can switch
to it unchanged. The full value mappings and the edge cases (integer width,
key order) are on the
[json-parse](../reference/functions/rontolisp-json-parse.md) and
[json-stringify](../reference/functions/rontolisp-json-stringify.md)
reference pages.

## A complete program

The pieces combine into the typical JSON-API round trip: build the request
body with `json-stringify`, POST it, await the response and parse the body
with `json-parse`. Save the following as `fetch-post.lisp`:

```console
(let ((req (make-hash-table :test 'equal)))
  (setf (gethash "name" req) "rontolisp")
  (setf (gethash "stars" req) 1)
  (let* ((payload (rontolisp:json-stringify req))
         (res (rontolisp:await
               (rontolisp:fetch "https://httpbin.org/post"
                                (list :method "POST"
                                      :headers (list (cons "Content-Type" "application/json"))
                                      :body payload))))
         (body (rontolisp:await (rontolisp:read-all (getf res :body))))
         (json (rontolisp:json-parse body)))
    (print (getf res :status))
    (write-line (gethash "data" json))))
```

```
200
{"name":"rontolisp","stars":1}
```

### Running it

On the interpreter:

```bash
rontolisp fetch-post.lisp
```

Compiled to a JVM class (the class is named after the output file):

```bash
rontolisp fetch-post.lisp -o FetchPost.class
java FetchPost
```

Compiled to a WASM component (wasmtime 46+; note `-S http=y`, which grants
outgoing HTTP — without it instantiation fails because the `wasi:http`
imports are unavailable):

```bash
rontolisp fetch-post.lisp -o fetch-post.wasm --component
wasmtime run -W gc=y -W exceptions=y -S http=y fetch-post.wasm
```

For raw TCP instead of HTTP — or to implement the *server* side — see the
[TCP Sockets guide](tcp-sockets.md).
