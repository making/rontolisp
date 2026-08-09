# hello-ningle — a **ningle** application on Cloudflare Workers

[`../hello-clack`](../hello-clack) is the smallest real Clack application on a
Worker: `ql:quickload`, one `defun`, `clack:clackup`.
[`../hello-tiny-routes`](../hello-tiny-routes) is the same Worker with the
application composed out of routes. This is the third shape:
[ningle](https://github.com/fukamachi/ningle), where the application is not a
function at all but a CLOS **object** you hang routes on — and where the 404 is a
method you override rather than a route you add.

```bash
./build.sh              # worker.lisp -> src/worker.wasm
npx wrangler dev        # or deploy it
rontolisp check.lisp    # drive the whole Worker locally, on any backend
```

## The whole program

```lisp
(ql:quickload '("clack" "clack-handler-reactor" "ningle"))

(defpackage :hello-ningle (:use :cl))
(in-package :hello-ningle)

(defvar *app* (make-instance 'ningle:app))

(setf (ningle:route *app* "/")
      (format nil "Hello from ningle on Cloudflare Workers!~%"))

(setf (ningle:route *app* "/hello/:name")
      (lambda (params) (format nil "Hello, ~a!~%" (cdr (assoc :name params)))))

(defmethod ningle:not-found ((app ningle:app))
  (setf (lack.response:response-status ningle:*response*) 404)
  (format nil "no route for ~a~%"
          (lack.request:request-path-info ningle:*request*)))

(clack:clackup *app* :server :reactor :use-thread nil)
```

Still **no Worker-specific code**. `*app*` is an ordinary Clack application — the
environment plist in, the `(status headers body)` list out — so it runs on
hunchentoot, on woo, under `wasmtime serve` and on the JVM unchanged; the
[`../hello-clack` README](../hello-clack/README.md) explains the designator half.

What ningle adds over the two neighbours is worth naming, because it is a
genuinely different model and not a spelling difference:

- **The application is an object, and a route is a `setf`.** There is no
  route-list form: `(setf (ningle:route *app* "/x") controller)` mutates the
  application's mapper, so routes can be added from anywhere, including at run
  time.
- **A controller does not have to be a function.** The `/` route above is a
  *string*. ningle answers a non-function controller as the response body, which
  is why the first line of its README is exactly that.
- **A controller receives the parameters, not the environment.** The `:name`
  token binds into an alist keyed by the keyword. The request itself is in the
  `ningle:*request*` special, along with `*response*` (mutable — that is how the
  404 below sets its status) and `*session*`.
- **The 404 is an extension point, not a route.** `ningle:not-found` is a
  generic function on the application class; overriding it is how "no rule
  matched" is answered. That is the deliberate difference from the tiny-routes
  Worker, where the 404 is the last route in the list — and it means this file
  exercises `defmethod` on a library generic from the application's own package.

## What it costs

| | [`../hello`](../hello) | [`../hello-clack`](../hello-clack) | [`../hello-tiny-routes`](../hello-tiny-routes) | this |
| --- | --- | --- | --- | --- |
| the Lisp | 3 `wasm-export`ed functions | a Clack application + `clackup` | three routes + `clackup` | two routes, a `not-found` method + `clackup` |
| module | 563 B | 249,795 B raw / **76,049 B gzip** | 273,417 B raw / **81,427 B gzip** | 2,662,798 B raw / **608,220 B gzip** |
| imports | zero | zero | zero | **zero** |
| `_initialize` | none (no top-level forms) | 4.9 ms | 4.7 ms | **7.2 ms** |
| warm request | | 0.013 ms | 0.011 ms | **0.059 ms** |

All four modules built and measured together (`--no-wasi --optimize=size`,
`gzip -9 -n`, node 24 driving [`src/index.js`](src/index.js)'s boundary code,
2026-08-09); the neighbours' own READMEs record their own earlier runs.

**Ten times the module, and almost none of it is ningle.** The same build with
ningle replaced by one `lack.request:make-request` call is 2,226,054 B raw /
495,317 B gzip, so ningle, its router
[myway](https://github.com/fukamachi/myway) and myway's `map-set` account for
**436 KB** of the total; the other ~2 MB is the `lack-request` chain — `http-body`,
`fast-http`'s generated header and multipart state machines, `smart-buffer`,
`circular-streams`, `yason`, `trivial-mimes`, `quri`. tiny-routes never touches
it, because its request IS the Clack environment plist; ningle's `call` reads
`request-headers` / `-method` / `-path-info` / `-parameters` on every request, so
there is no route around it. 608 KB gzip is still 20% of the free plan's 3 MB
compressed limit, so the size is a cost rather than a wall — but it is the
reason to reach for `tiny-routes` when the routing is all you need.

There is also no size opt-in to offer the way tiny-routes has one: myway
compiles every rule to a **cl-ppcre scanner**, so the regex engine is genuinely
reachable and no amount of tree-shaking can remove it.

## Developing without Cloudflare

The same loop the other directories have: the synthesized export calls `clack.handler.reactor:dispatch`, an ordinary
function, so the whole Worker — routes, the `not-found` method and all — runs on
the interpreter, the JVM and the WASM backends, which
[`examples/examples.yaml`](../../examples.yaml) pins:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Check.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Check
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

```console
--> /
<-- {"body":"Hello from ningle on Cloudflare Workers!\n","headers":[],"status":200}
--> /hello/rontolisp
<-- {"body":"Hello, rontolisp!\n","headers":[],"status":200}
--> /anything
<-- {"body":"no route for /anything\n","headers":[],"status":404}
```

(Key order differs per backend — it follows hash-table iteration order — which is
why the manifest checks with `contains`.)

The first build downloads clack, lack and ningle into `~/.rontolisp/quicklisp`;
after that everything is offline (the `ql:quickload` is resolved at **compile**
time and inlined into the module).

To serve the same application over a real socket instead, drop the
`clack-handler-reactor` line and use `:server :rontolisp` — that is what
[`examples/net/httpbin-ningle.lisp`](../../net/httpbin-ningle.lisp) does, with
six routes instead of two.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | The whole program — quickload, the routes, the `not-found` method, `clackup`. This is what `build.sh` compiles. |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight, on any backend — and what the examples manifest runs. |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../hello-clack/src/index.js`. |
| `src/worker.wasm` | The compiled module (~2.7 MB). A build product — run `./build.sh` first. |

## Limitations

The same ones as [`../hello-clack`](../hello-clack/README.md#limitations): no
standard input and no clock in the Lisp, no filesystem, no `rontolisp:fetch`
(use JavaScript's `fetch()` in `src/index.js`). Printing does not trap — it is
discarded — and `random` works, on a generator `src/index.js` seeds from
`crypto`.

This directory used to open with a blockquote saying it did not run on
Cloudflare at all: `lack-request -> http-body -> fast-http -> smart-buffer`
names a temporary directory with a top-level `(random ...)` over
`uiop:default-temporary-directory`, and on a `--no-wasi` module both halves used
to trap, inside `_initialize`, before any export existed. Nothing about ningle
changed; the reactor learned to answer them.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello-ningle/build.sh
```
