# HTTP Requests (fetch)

The `rontolisp` package provides outgoing HTTP modeled on the JavaScript
`fetch` API, plus the JSON functions that pair naturally with it. None of
these are part of Common Lisp; reference them with the `rontolisp:` qualifier
(see [Packages](../reference/packages.md)). `rontolisp:fetch` starts a request
and immediately returns a **promise**; the generic promise operations resolve
or transform it, and `rontolisp:json-parse` / `rontolisp:json-stringify`
convert between JSON documents and Lisp values.

| Function | Purpose |
|----------|---------|
| [`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md) | Start an HTTP request: `(rontolisp:fetch url &optional options)` |
| [`rontolisp:await`](../reference/functions/rontolisp-await.md) | Block until a promise settles and return its value |
| [`rontolisp:then`](../reference/functions/rontolisp-then.md) | Derive a new promise that applies a callback to the settled value |
| [`rontolisp:promisep`](../reference/functions/rontolisp-promisep.md) | `t` if a value is a promise |
| [`rontolisp:json-parse`](../reference/functions/rontolisp-json-parse.md) | Parse a JSON string into Lisp values |
| [`rontolisp:json-stringify`](../reference/functions/rontolisp-json-stringify.md) | Serialize a Lisp value to a JSON string |

> **Backend support.** The interpreter and JVM-compiled classes use the JDK
> `java.net.http.HttpClient`; the request runs on a background thread from the
> moment `fetch` returns. The WASM backend is **component-only**
> (`--component`, importing the async `wasi:http@0.3.0`): `fetch` is a compile
> error in Preview 1 (core-module) mode, and a fetch component must run with
> `-S http=y` on top of the usual flags. In the **browser playground** `fetch`
> runs the real browser `fetch()` (subject to CORS) while the program
> continues. The promise operations (`await` / `then` / `promisep`) and the
> JSON functions work on **every** backend and in every WASM mode — only
> `fetch` itself is restricted.

## A first request

`fetch` returns as soon as the request is in flight. Passing the promise to
`rontolisp:await` blocks until the response arrives and yields the result
property list `(:status <integer> :body <string> :headers <alist>)`:

```lisp
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

Reading the individual fields:

```console
(let ((res (rontolisp:await (rontolisp:fetch "http://example.com/"))))
  (print (getf res :status))    ; => 200
  (print (getf res :body))      ; => "<html>...</html>"
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

## Promises

Because the request is already running when `fetch` returns, several requests
overlap — start them all, then await each:

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))  ; both requests running
  (list (rontolisp:await p1) (rontolisp:await p2)))
```

`rontolisp:then` derives a new promise that transforms the settled value, like
JavaScript's `Promise.prototype.then` — calls chain, and a callback returning
a promise is flattened:

```lisp
(rontolisp:await
 (rontolisp:then (rontolisp:fetch "https://httpbin.org/get")
                 (lambda (r) (getf r :status))))   ; => 200
```

Both operations are generic: `await` passes a non-promise value through
unchanged, and `then` accepts one, so a value that may or may not be a promise
is handled uniformly. `rontolisp:promisep` tells the two apart:

```lisp
(rontolisp:await 42)   ; => 42
```

```lisp
(rontolisp:promisep (rontolisp:then 1 (lambda (x) x)))   ; => t
```

A `then` callback runs lazily at first `await` and its result is memoized (a
settled promise can be awaited any number of times); see the
[then](../reference/functions/rontolisp-then.md) reference page for the exact
timing.

## Working with JSON

`rontolisp:json-parse` turns a JSON document into Lisp values. By default a
JSON object becomes a property list with keyword keys, so the result reads
with `getf`; arrays become lists, `true`/`false`/`null` become `t`/`nil`:

```lisp
(rontolisp:json-parse "{\"name\": \"rontolisp\", \"n\": 2}")   ; => (:name "rontolisp" :n 2)
```

```lisp
(getf (rontolisp:json-parse "{\"a\": {\"b\": [1, true, null]}}") :a)   ; => (:b (1 t nil))
```

Passing `:hash-table` returns hash tables with string keys instead — use it
when keys are arbitrary strings or when an empty object must stay
distinguishable from `nil`:

```lisp
(let ((h (rontolisp:json-parse "{\"content-type\": \"text/html\"}" :hash-table)))
  (gethash "content-type" h))   ; => "text/html"
```

`rontolisp:json-stringify` is the inverse: keyword property lists and hash
tables serialize to objects, other lists to arrays:

```lisp
(rontolisp:json-stringify (list :name "rontolisp" :ok t :ver 1.5))   ; => "{\"name\":\"rontolisp\",\"ok\":true,\"ver\":1.5}"
```

```lisp
(rontolisp:json-stringify (list 1 (list 2 3) nil))   ; => "[1,[2,3],null]"
```

Both functions are written in rontolisp itself and compile into the program
on every backend; the full value mappings and the edge cases (integer width,
`nil` ambiguity, key order) are on the
[json-parse](../reference/functions/rontolisp-json-parse.md) and
[json-stringify](../reference/functions/rontolisp-json-stringify.md)
reference pages.

## A complete program

The pieces combine into the typical JSON-API round trip: build the request
body with `json-stringify`, POST it, await the response and parse the body
with `json-parse`. Save the following as `fetch-post.lisp`:

```console
(let* ((payload (rontolisp:json-stringify (list :name "rontolisp" :stars 1)))
       (res (rontolisp:await
             (rontolisp:fetch "https://httpbin.org/post"
                              (list :method "POST"
                                    :headers (list (cons "Content-Type" "application/json"))
                                    :body payload))))
       (json (rontolisp:json-parse (getf res :body))))
  (print (getf res :status))
  (write-line (getf json :data)))
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
wasmtime run -W gc=y -W exceptions=y -W component-model-more-async-builtins=y -S http=y fetch-post.wasm
```

For raw TCP instead of HTTP — or to implement the *server* side — see the
[TCP Sockets guide](tcp-sockets.md).
