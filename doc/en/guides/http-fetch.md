# HTTP Requests (fetch)

The `rontolisp` package provides outgoing HTTP modeled on the JavaScript
`fetch` API, plus the JSON functions that pair naturally with it. None of these
are part of Common Lisp; reference them with the `rontolisp:` qualifier (see
[Packages](../reference/packages.md)). `rontolisp:fetch` starts a request and
immediately returns a **future**; you resolve it with `rontolisp:await`. The
future / `await` mechanics themselves are not specific to HTTP — they are the
subject of the [Asynchronous Programming guide](async.md), which this page
assumes; here we cover only what is particular to making requests.

| Function | Purpose |
|----------|---------|
| [`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md) | Start an HTTP request: `(rontolisp:fetch url &optional options)` |
| [`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md) | Drain a response body stream into one string (async) |
| [`rontolisp:json-parse`](../reference/functions/rontolisp-json-parse.md) | Parse a JSON string into Lisp values |
| [`rontolisp:json-stringify`](../reference/functions/rontolisp-json-stringify.md) | Serialize a Lisp value to a JSON string |

> **Backend support.** The interpreter and JVM-compiled classes use the JDK
> `java.net.http.HttpClient`; the request runs on a background thread from the
> moment `fetch` returns. The WASM backend is **component-only**
> (`--component`, importing the async `wasi:http@0.3.0`): `fetch` is a compile
> error in Preview 1 (core-module) mode, and a fetch component must run with
> `-S http=y` on top of the usual flags. In the **browser playground** `fetch`
> runs the real browser `fetch()` (subject to CORS) while the program
> continues. The JSON functions work on **every** backend and in every WASM
> mode; only `fetch` itself is restricted. `await`, `futurep` and the future
> combinators are covered in the [async guide](async.md).

## A first request

`fetch` returns as soon as the request is in flight. Passing the future to
`rontolisp:await` suspends until the response arrives and yields the result
property list `(:status <integer> :headers <alist> :body <stream>)` — on every
backend `:body` is an [asynchronous stream](async.md#asynchronous-streams),
drained with
[`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md):

```lisp
(let ((p (rontolisp:fetch "https://httpbin.ik.am/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

Reading the individual fields:

```lisp
(let ((res (rontolisp:await (rontolisp:fetch "https://httpbin.ik.am/get"))))
  (print (getf res :status))    ; => 200
  (print (rontolisp:await (rontolisp:read-all (getf res :body))))
                                ; => "{...}"
  (print (getf res :headers)))  ; => (("content-type" . "application/json") ...)
```

Because the request is already running when `fetch` returns, several requests
overlap — start them all, then await each (in any order). This is just the
general [overlapping-work](async.md#overlapping-work) behavior of futures:

```lisp
(let ((p1 (rontolisp:fetch "https://httpbin.ik.am/status/200"))
      (p2 (rontolisp:fetch "https://httpbin.ik.am/status/201")))  ; both requests running
  (list (getf (rontolisp:await p1) :status)
        (getf (rontolisp:await p2) :status)))                     ; => (200 201)
```

## Request options

The optional second argument is an options property list with `:method`
(a string, default `"GET"`), `:headers` (an alist of `(name . value)` string
pairs) and `:body` (a string):

```lisp
;; GET with request headers (an alist of (name . value) string pairs)
(rontolisp:await
  (rontolisp:fetch "https://httpbin.ik.am/get"
                   '(:headers (("Accept" . "application/json")))))

;; POST with a request body
(rontolisp:await
  (rontolisp:fetch "https://httpbin.ik.am/post"
                   '(:method "POST"
                     :headers (("Content-Type" . "application/json"))
                     :body "{\"name\":\"rontolisp\"}")))
```

The supported methods are `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS`
and `PATCH`; see the [fetch](../reference/functions/rontolisp-fetch.md)
reference page for validation timing and error behavior per backend (a failed
request surfaces at `await`, not at `fetch` — every backend signals an error
there; `nil` comes back only for a request that cannot be *started*).

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

```lisp
(let ((req (make-hash-table :test 'equal)))
  (setf (gethash "name" req) "rontolisp")
  (setf (gethash "stars" req) 1)
  (let* ((payload (rontolisp:json-stringify req))
         (res (rontolisp:await
               (rontolisp:fetch "https://httpbin.ik.am/post"
                                `(:method "POST"
                                  :headers (("Content-Type" . "application/json"))
                                  :body ,payload))))
         (body (rontolisp:await (rontolisp:read-all (getf res :body))))
         (json (rontolisp:json-parse body)))
    (print (getf res :status))
    (write-line (or (gethash "data" json) body))))
```

```console
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
