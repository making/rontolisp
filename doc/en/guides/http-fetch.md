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
> moment `fetch` returns. On WASM `fetch` needs a host that can make the call
> for it, which is either a **component** (`--component`, importing the async
> `wasi:http@0.3.0`, run with `-S http=y` on top of the usual flags) or a
> **`--no-wasi` reactor built with `--host-fetch`**, which lowers the same
> source onto the host's own HTTP client through an `env.fetch` import (plus
> `env.readResponseBody` for the reply body) — that
> is how a Cloudflare Worker or a node embedding fetches
> ([the section below](#fetching-from-a-reactor---no-wasi---host-fetch)). With
> neither, `fetch` is a compile error in Preview 1 (core-module) mode. In the
> **browser playground** `fetch`
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
  (rontolisp:json-stringify h))   ; => "{\"name\":\"rontolisp\"}"
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

When building the hash table by hand — `make-hash-table` then a `setf gethash`
per key — is awkward, four utilities convert to and from the usual list shapes.
[`rontolisp:plist-hash-table`](../reference/functions/rontolisp-plist-hash-table.md)
and [`rontolisp:alist-hash-table`](../reference/functions/rontolisp-alist-hash-table.md)
build a hash table from a property list or an association list (a keyword key
like `:name` down-cases to `"name"`), so a JSON object is one expression from a
quoted literal:

```lisp
(rontolisp:json-stringify (rontolisp:plist-hash-table '(:name "rontolisp" :stars 1)))   ; => "{\"name\":\"rontolisp\",\"stars\":1}"
```

```lisp
(rontolisp:json-stringify (rontolisp:alist-hash-table '(("name" . "rontolisp") ("stars" . 1))))   ; => "{\"name\":\"rontolisp\",\"stars\":1}"
```

The inverses
[`rontolisp:hash-table-plist`](../reference/functions/rontolisp-hash-table-plist.md)
and [`rontolisp:hash-table-alist`](../reference/functions/rontolisp-hash-table-alist.md)
flatten a parsed object back into a list you can walk with `getf` or `assoc`
(a parsed object has string keys, so `assoc` with `:test 'equal`):

```lisp
(rontolisp:hash-table-plist (rontolisp:json-parse "{\"n\": 1}"))   ; => ("n" 1)
```

```lisp
(rontolisp:hash-table-alist (rontolisp:json-parse "{\"n\": 1}"))   ; => (("n" . 1))
```

They are lightweight subsets of the same-named `alexandria` functions and, like
the JSON functions, compile in on every backend.

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

## Fetching from a reactor (`--no-wasi --host-fetch`)

A [`--no-wasi` reactor](wasm-gc-module.md#no-wasi-reactor-mode) imports no
WASI, so it has no `wasi:http` to fetch through — but the hosts that drive one
(a Cloudflare Worker, node, a browser page) have an HTTP client of their own.
`--host-fetch` routes `rontolisp:fetch` at it, as two injected imports —
`env.fetch(request-json) -> response-head-json` for the request and the reply's
head, and `env.readResponseBody(ptr, cap) -> i32` for the reply's body:

```bash
rontolisp worker.lisp -o worker.wasm --no-wasi --host-fetch --optimize=size
```

Nothing in the Lisp changes — same options, same
`(:status :headers :body)` answer, `:body` an asynchronous stream like
everywhere else — but three things are particular to this backend:

- **The fetch belongs inside an export, not at the top level.** A reactor has
  no `_start`: the host instantiates it and calls an exported function. A
  JavaScript host implements `env.fetch` with `WebAssembly.Suspending` (JSPI),
  which parks the whole wasm stack until the promise settles, and
  `_initialize` is the one stack it may not park — so a fetch the *load path*
  reaches is refused there. The build prints a warning naming it.
- **The body arrives after the head, one chunk at a time.** `env.fetch`
  answers status and headers; the octets are pulled through
  `env.readResponseBody` as the drain asks for them, into a buffer the module
  passes (the host answers how many it wrote, `0` for end of stream). So a
  large reply never becomes a JSON string, a *binary* reply crosses as the
  octets it is, and a Worker can forward a streamed upstream response straight
  to its own client.
- **Started == settled, and settled means the HEAD.** The future is settled the
  moment `fetch` returns (the stack was parked for the round trip to the
  headers), so `await` never suspends and two fetches never overlap — the
  [degenerate async
  shape](async.md#under-the-hood-wasi-preview-3-futures--streams) Preview 1 has
  everywhere. A transport failure *before* the head therefore signals at the
  `fetch` call; one *during* the body signals at the drain, like every other
  backend. One reply body is live at a time: starting the next `fetch` before
  draining the previous one makes that drain signal rather than answer the new
  reply's octets.

The host side owes one obligation in return, which the build also prints:
enter every export through `WebAssembly.promising` and serialise the calls
(or compile
[`--reentrant`](wasm-host-boundary.md#overlapping-calls---reentrant) to
overlap them on one instance). A
suspended handler returns control to the event loop, and a second request
entering the same instance would share its globals and its allocator — the
module refuses that re-entry with a trap rather than corrupting both calls. A
synchronous `env.fetch` (node without JSPI, a test stub) needs none of this
and is equally valid. Adding
[`--emit-js-glue`](wasm-host-boundary.md#generating-the-host-glue---emit-js-glue)
writes that obligation as JavaScript beside the module — both imports, the
`promising` entry and the queue — leaving the host only what its `fetch` does.

The usual shape is a served reactor: an
[`http-handler`](http-handler.md) or a
[Clack application](clack.md#a-host-that-calls-you-the-reactor-build) compiled
with these flags exports `handle-request`, and its handler is what fetches.
[`examples/cloudflare-workers/dog-fetcher`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/dog-fetcher)
is exactly that, JavaScript side included — one source that also runs on the
interpreter, the JVM and a `wasi:http` component.

For raw TCP instead of HTTP — or to implement the *server* side — see the
[TCP Sockets guide](tcp-sockets.md).
