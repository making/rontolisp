# hello-tiny-routes — a **routed** Clack application on Cloudflare Workers

[`../hello-clack`](../hello-clack) is the smallest real Clack application on a
Worker: `ql:quickload`, one `defun`, `clack:clackup`. This is the same Worker
with the middle form replaced — the application is not a function you write but
one [tiny-routes](https://github.com/jeko2000/tiny-routes) composes out of
routes.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl http://localhost:8787/
Hello from tiny-routes on Cloudflare Workers!

$ curl http://localhost:8787/hello/rontolisp
Hello, rontolisp!

$ curl -i http://localhost:8787/anything
HTTP/1.1 404 Not Found
Content-Type: text/plain;charset=UTF-8

no route for /anything
```

## The whole program

```lisp
(ql:quickload '("clack" "clack-handler-reactor" "tiny-routes/lite"))

(defpackage :hello-tiny-routes (:use :cl :tiny-routes))
(in-package :hello-tiny-routes)

(define-routes *app*
  (define-get "/"
    ()
    (ok (format nil "Hello from tiny-routes on Cloudflare Workers!~%")))
  (define-get "/hello/:name"
    (req)
    (ok (format nil "Hello, ~a!~%" (path-parameter req :name))))
  (define-any "*"
    (req)
    (not-found (format nil "no route for ~a~%" (path-info req)))))

(clack:clackup *app*
               :server :reactor
               :use-thread nil)
```

Still **no Worker-specific code**. `*app*` is an ordinary Clack application — the
environment plist in, the `(status headers body)` list out — so it runs on
hunchentoot, on woo, under `wasmtime serve` and on the JVM unchanged. The
[`../hello-clack` README](../hello-clack/README.md) explains the designator
half: the handler backend stores the application on every backend, the compiler
synthesizes the `handle-request` export `src/index.js` calls — and why a Worker
could also just use `:server :rontolisp`, the way
[`../httpbin-clack-one-source`](../httpbin-clack-one-source) does.

What the routing library adds is three things worth naming:

- **A route is a handler.** `define-get` builds a function that answers, or
  returns `nil` to **decline** so the next route is tried. `define-routes` is
  just "try these in order, take the first non-`nil`" — which is why the last
  route, `"*"`, is the 404 and needs no special mechanism.
- **A path template binds parameters.** `"/hello/:name"` matches one segment and
  `path-parameter` reads it. `/hello/` and `/hello` do not match it (a segment
  has to be there), so they decline into the 404 like anything else.
- **Response constructors.** `ok` and `not-found` build the Clack
  `(status headers body)` list `../hello-clack` writes out by hand, naming the
  status instead of spelling the number. They set **no headers**, and this
  Worker leaves it that way: `src/index.js` passes the body to `new Response`,
  which gives it the Fetch default `Content-Type: text/plain;charset=UTF-8` —
  as `wrangler dev` reports above. An application that answers JSON, or that
  wants the header set in Lisp, uses `make-response` (or tiny-routes' `pipe`
  with `wrap-response-content-type` to set it for every route at once).

## `tiny-routes/lite`, and why it is on the `quickload` line

A tiny-routes path template compiles to a **cl-ppcre scanner at run time**, so
in a compiled module the whole regex engine is genuinely reachable and the
tree-shaker is right to keep it. `"tiny-routes/lite"` is
the opt-in system: the same source tree with the path-template matcher swapped
for a ppcre-free one and the `:cl-ppcre` dependency dropped with it. It accepts
templates of literal characters and `:name` tokens — `"/hello/:name"` is one —
matches them exactly as the full system does, and **refuses at route-build
time** on a regex metacharacter or a `:regex t` template.
[`../httpbin-tiny-routes`](../httpbin-tiny-routes/README.md) measures what that
choice is worth in bytes; the [ASDF systems
guide](../../../doc/en/guides/asdf-systems.md) has the exact accepted subset.

## What it costs

| | [`../hello`](../hello) | [`../hello-clack`](../hello-clack) | this |
| --- | --- | --- | --- |
| the Lisp | 3 `wasm-export`ed functions | a Clack application + `clackup` | three routes + `clackup` |
| imports | zero | zero | **zero** — instantiated with `{}`, no WASI shim |
| `_initialize` | none (no top-level forms) | ~5 ms | ~5 ms |
| warm request | | 0.013 ms | **0.013 ms** |

Measured on node 24 (V8, workerd's engine family) driving
[`src/index.js`](src/index.js)'s boundary code against these modules.

So routing costs a modest amount in the bundle — the
[size report](../../../size-report/results/cloudflare-workers.md) has the exact
bytes against `hello-clack` — and nothing per request: the route list is walked
in Lisp, and at three routes that disappears into the string boundary.

## Developing without Cloudflare

Exactly as in [`../hello-clack`](../hello-clack/README.md#developing-without-cloudflare):
the synthesized export calls `clack.handler.reactor:dispatch`, an ordinary
function, so the whole Worker — routes included — runs on the
interpreter, the JVM and the WASM backends, which
[`examples/examples.yaml`](../../examples.yaml) pins:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Check.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Check
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

The first build downloads clack, lack and tiny-routes into
`~/.rontolisp/quicklisp`; after that everything is offline (the `ql:quickload`
is resolved at **compile** time and inlined into the module).

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | The whole program — quickload, the routes, `clackup`. This is what `build.sh` compiles. |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight, on any backend — and what the examples manifest runs. |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../hello-clack/src/index.js`. |
| `src/worker.wasm` | A build product — run `./build.sh` first. |

[`../httpbin-tiny-routes`](../httpbin-tiny-routes) is this example grown up: five
echo endpoints declared one method at a time, a request body, a `/status/:code`
template, and a single catch-all that answers both the 405 and the 404.

## Limitations

The Worker sandbox and the `--no-wasi` build, exactly as in
[`../hello-clack`](../hello-clack/README.md#limitations): no standard input, no
filesystem, no `rontolisp:fetch` (use JavaScript's `fetch()` in
`src/index.js`). Printing does not trap — it is discarded — `random` works on a
generator `src/index.js` seeds from `crypto`, and the clock reads whatever
`src/index.js` hands to `__ronto_set_time`. One more is
the lite matcher's: a regex-shaped template signals when
the route is built, which under `--no-wasi` means at `_initialize`, so it is a
build-time decision rather than a request-time surprise.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello-tiny-routes/build.sh
```
