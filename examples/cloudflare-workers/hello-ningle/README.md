# hello-ningle — a Worker whose application is an object

The same greeting as [`../hello-clack`](../hello-clack), written the way
[ningle](https://github.com/fukamachi/ningle) wants it: the application is not a
function at all but a CLOS **object** you hang routes on, and the 404 is a
method you override rather than a route you add.

```bash
./build.sh              # worker.lisp -> src/worker.wasm
npx wrangler dev        # or deploy it
rontolisp check.lisp    # drive the whole Worker locally, on any backend
```

## The whole program

```lisp
(ql:quickload '("clack" "clack-handler-reactor" "ningle"))

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

Four things make it ningle:

- **The application is an object, and a route is a `setf`.** There is no
  route-list form, so routes can be added from anywhere — another file, a
  function, run time.
- **A controller does not have to be a function.** The `/` route is a *string*;
  ningle answers a non-function controller as the response body.
- **A controller receives the parameters, not the environment.** The `:name`
  token binds into an alist keyed by the keyword; the request itself is in
  `ningle:*request*`, with `*response*` (mutable — that is how the 404 sets its
  status) and `*session*` beside it.
- **The 404 is an extension point.** `ningle:not-found` is a generic function on
  the application class, so answering "no rule matched" is a `defmethod` on a
  *library* generic.

There is no `defpackage` here, and that is ningle's own idiom: a thin framework
used through qualified names, exactly as its README shows. The tiny-routes
Worker needs one because `(:use :tiny-routes)` is what makes `define-get` and
`ok` unqualified; nothing here is used unqualified, so a package would earn
nothing.

`*app*` is still an ordinary Clack application, so it runs on hunchentoot, on
woo, under `wasmtime serve` and on the JVM; the
[`../hello-clack` README](../hello-clack/README.md) explains the `:server`
designator half. To serve it over a real socket, drop `clack-handler-reactor`
and use `:server :rontolisp` — that is what
[`examples/net/httpbin-ningle.lisp`](../../net/httpbin-ningle.lisp) does.

## What it costs

This is by an order of magnitude the largest of the four hello Workers
([size report](../../../size-report/results/cloudflare-workers.md)) — **and
almost none of it is ningle.** The same build with ningle replaced by one
`lack.request:make-request` call is barely smaller, so ningle, its router
[myway](https://github.com/fukamachi/myway) and myway's `map-set` are a fifth of
the difference; the rest is the `lack-request` chain — `http-body`,
`fast-http`'s generated header and multipart state machines, `smart-buffer`,
`circular-streams`, `yason`, `trivial-mimes`, `quri`. tiny-routes never touches
it, because its request IS the Clack environment plist; ningle's `call` reads
`request-headers` / `-method` / `-path-info` / `-parameters` on every request.
There is no size opt-in to offer either, the way tiny-routes has one: myway
compiles every rule to a **cl-ppcre scanner**, so the regex engine is genuinely
reachable. It still fits the free plan's bundle limit with room to spare — a
cost, not a wall — but it is the reason to reach for `tiny-routes` when routing
is all you need.

## Developing without Cloudflare

The synthesized export calls `clack.handler.reactor:dispatch`, an ordinary
function, so the whole Worker — routes, the `not-found` method and all — runs on
every backend, which [`examples/examples.yaml`](../../examples.yaml) pins:

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

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | The whole program. This is what `build.sh` compiles. |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight, on any backend. |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../hello-clack/src/index.js`. |
| `src/worker.wasm` | A build product — run `./build.sh` first. |

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../hello-clack`](../hello-clack/README.md#limitations) apply unchanged.

This directory used to open with a blockquote saying it did not run on
Cloudflare at all: `lack-request -> http-body -> fast-http -> smart-buffer` names
a temporary directory with a top-level `(random ...)` over
`uiop:default-temporary-directory`, and on a `--no-wasi` module both halves used
to trap inside `_initialize`, before any export existed. Nothing about ningle
changed; the reactor learned to answer them.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello-ningle/build.sh
```
