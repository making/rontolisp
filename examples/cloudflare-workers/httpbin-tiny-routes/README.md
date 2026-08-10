# httpbin-tiny-routes — the same Worker, routed by a real routing library

The five echo endpoints of [`../httpbin-clack`](../httpbin-clack) — same
helpers, same JSON documents — with the hand-written `cond` over `:path-info`
replaced by [tiny-routes](https://github.com/jeko2000/tiny-routes):
`define-routes`, one route macro per HTTP method, a `/status/:code` **path
template** and the route-decline protocol. And one line that decides the module
size:

```lisp
(ql:quickload "tiny-routes/lite")
```

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"method":"GET","headers":{...},"path":"/get","args":{"b":"two","a":"1"}}

$ curl http://localhost:8787/status/418
418

$ curl http://localhost:8787/status/teapot     # :code must parse -> the route
{"error":"not found","path":"/status/teapot"}  # declines into the 404
```

## What `tiny-routes/lite` is

The same tiny-routes source tree (the verbatim library from Quicklisp) with
**one component substituted**: the path-template matcher. It is an *opt-in* —
plain `(ql:quickload "tiny-routes")` still loads the untouched library,
cl-ppcre included, and the two systems refuse to load into one program.

That one dependency is the whole size story. Upstream compiles a route template
to a regex scanner at *run* time, so the whole cl-ppcre engine is genuinely
reachable and the tree-shaker is right to keep it — measured, the full library
takes this same module to roughly 2.8× its size (the
[size report](../../../size-report/results/cloudflare-workers.md) carries both
rows, built from this `worker.lisp` with only the `ql:quickload` line changed;
they answer the same probes byte for byte).

The lite matcher accepts templates made of **literal characters and `:name`
tokens** (`/users/:id`, `/status/:code`, `/pair/:a/:b`) — almost every routed
application — and matches them exactly as the full system does, greedy
backtracking included; the two are pinned template-for-template against the real
cl-ppcre engine by the test suite. Outside that subset the trade is loud, never
silent: a template containing a regex metacharacter, or `:regex t`, signals a
clear error when the route is *built*. A program that needs those loads the full
`"tiny-routes"` and pays for the engine. Exact subset: the
[ASDF systems guide](../../../doc/en/guides/asdf-systems.md).

## The endpoints

| | |
| --- | --- |
| `GET /get` | echo the request: `args`, `headers`, `method`, `path` |
| `POST /post` | the same, plus `data` (the raw body) and `json` (its parsed value) |
| `PUT /put`, `PATCH /patch`, `DELETE /delete` | ditto |
| `GET /status/:code` | answer with status `:code` — the **path template**; a non-numeric `:code` declines into the 404 |

The five echo endpoints answer the same documents as `../httpbin-clack`, but
nothing in them checks a method any more: each is declared with the macro for
the one method it answers, so the check is the *router's*.

A wrong method then **declines** — no route claims the request — and lands in
the single catch-all at the bottom, which is where both of httpbin's error
answers are decided: a path that is one of the five endpoints declined on its
method gives the 405 (naming the method that would have worked); any other path
gives the 404. One route, not one per endpoint.

`/status/:code` is deliberately not in that list, because its decline means
something else: the route answers `nil` when `:code` is not a number, and
falling through to a catch-all with no entry there is exactly the 404 httpbin
answers for `/status/teapot`.

`PATCH` has no macro in tiny-routes. Matching the method is the whole of what
those add over `define-any`, and the matcher is exported, so that one route is
spelled the way the macros expand:

```lisp
(wrap-request-matches-method (define-any "/patch" (req) (echo-with-body req))
                             :patch)
```

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | **The whole program**: quickload, the [`net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp) helpers verbatim, the routes, `clackup` |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight — the local edit/run loop, and what the examples manifest runs |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../httpbin/src/index.js` and `../httpbin-clack/src/index.js` |
| `src/worker.wasm` | A build product — run `./build.sh` first |

## Developing without Cloudflare

As in [`../httpbin-clack`](../httpbin-clack/README.md):
the synthesized export calls `clack.handler.reactor:dispatch`, an ordinary
function, so the whole Worker — routes included — runs on every backend:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Prog.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Prog
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

The first build downloads clack, lack and tiny-routes into
`~/.rontolisp/quicklisp`; after that everything is offline, because the
`ql:quickload` is resolved at **compile** time and inlined into the module.

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../httpbin-clack`](../httpbin-clack/README.md#limitations) apply unchanged.
One more is this directory's own: the lite matcher refuses regex-shaped
templates at route-build time.
