# httpbin-tiny-routes — the same Worker, routed by a **real routing library**

The five echo endpoints of [`../httpbin-clack`](../httpbin-clack) — same
helpers, same JSON documents — with the hand-written `cond` over `:path-info`
replaced by [tiny-routes](https://github.com/jeko2000/tiny-routes):
`define-routes`, one route macro per HTTP method (`define-get`, `define-post`,
…), a `/status/:code` **path template** and the route-decline protocol. And one
line that decides the module size:

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

## What routing costs: the size comparison

Same day, same compiler, the same `--no-wasi --optimize=size` build line and
the same `gzip -9 -n`, node 24 for `_initialize` (2026-08-08):

| Worker | routing | raw | gzip | `_initialize` |
| --- | --- | --- | --- | --- |
| [`../httpbin`](../httpbin) | hand-written `cond`, no clack in the module | 182,767 B | 55,895 B | 4.5 ms |
| [`../httpbin-clack`](../httpbin-clack) | hand-written `cond`, `clackup` | 384,366 B | 105,447 B | 4.8 ms |
| **this** | **tiny-routes/lite**, `clackup` | **408,448 B** | **111,868 B** | 4.8 ms |
| this with the full `"tiny-routes"` | tiny-routes over **cl-ppcre** | 974,530 B | 235,955 B | 6.1 ms |

Read bottom-up. The full library is 2.5× the clack build — not because
of tiny-routes itself (~72 KB of routing code) but because its one dependency
is **cl-ppcre**: a route template compiles to a regex scanner at *run* time, so
the whole regex engine is genuinely reachable and the tree-shaker is right to
keep it. `tiny-routes/lite` swaps one file of the library — the
cl-ppcre-backed `path-template.lisp` — for a ppcre-free matcher and drops the
`:cl-ppcre` dependency with it, and the library API costs **+24,082 B raw**
over the hand-written `cond`. The last row was
built from this very `worker.lisp` with only the `ql:quickload` line changed,
and answers the same probes byte-for-byte — for 2.4× the module.

111,868 B gzip is **3.5%** of the free plan's 3 MB compressed bundle limit.

## What `tiny-routes/lite` is

The same tiny-routes source tree (the verbatim library from Quicklisp), loaded
with **one component substituted**: the path-template matcher. It is an
*opt-in* — plain `(ql:quickload "tiny-routes")` still loads the untouched
library, cl-ppcre included, and the two systems refuse to load into one
program.

The lite matcher accepts templates made of **literal characters and `:name`
tokens** (`/users/:id`, `/status/:code`, `/files/v:version`, `/pair/:a/:b`) —
which is to say, almost every routed application — and matches them **exactly
as the full system does**, greedy backtracking included: the two are pinned
template-for-template against the real cl-ppcre engine by the test suite.
Outside that subset the trade is loud, never silent:

- a template containing a regex metacharacter (`.` `\` `[` `]` `(` `)` `{`
  `}` `|` `^` `$` `*` `+` `?`) — which upstream hands to cl-ppcre as live
  regex syntax — signals a clear error when the route is *built*, and
- `:regex t` templates signal likewise.

A program that needs those loads the full `"tiny-routes"` and pays for the
engine. Details and the exact subset: the
[ASDF systems guide](../../../doc/en/guides/asdf-systems.md).

## The endpoints

| | |
| --- | --- |
| `GET /get` | echo the request: `args`, `headers`, `method`, `path` |
| `POST /post` | the same, plus `data` (the raw body) and `json` (its parsed value) |
| `PUT /put`, `PATCH /patch`, `DELETE /delete` | ditto |
| `GET /status/:code` | answer with status `:code` — the **path template**; a non-numeric `:code` declines into the 404 |

The five echo endpoints answer the same documents as `../httpbin-clack` — a
wrong method 405, an unknown path 404, an unparseable body `"json": null` — but
nothing in them checks a method any more: each is declared with the macro for
the one method it answers (`define-get "/get"`, `define-post "/post"`, …), so
`../httpbin-clack`'s `echo-when` is gone and the check is the *router's*.

A wrong method then **declines** — no route claims the request — and it lands
in the single catch-all at the bottom, which is where both of httpbin's error
answers are decided: a path that is one of the five endpoints declined on its
method, and that is the 405 (naming the method that would have worked); any
other path is the 404. One route, not one per endpoint.

`/status/:code` is deliberately *not* in that table, because its decline means
something else: the route answers `nil` when `:code` is not a number, and
falling through to the catch-all with no entry there is exactly the 404 httpbin
answers for `/status/teapot`.

`PATCH` has no macro in tiny-routes (it ships `define-get`, `define-post`,
`define-put`, `define-delete`, `define-head`, `define-options`). Matching the
method is the whole of what those add over `define-any`, and the matcher is
exported, so that one route is spelled the way the macros expand:

```lisp
(wrap-request-matches-method (define-any "/patch" (req) (echo-with-body req))
                             :patch)
```

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | **The whole program**: quickload, the `../httpbin-clack` helpers verbatim, the routes, `clackup`. |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight — the local edit/run loop, and what the examples manifest runs. |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../httpbin/src/index.js` and `../httpbin-clack/src/index.js`. |
| `src/worker.wasm` | The compiled module (~399 KB). A build product — run `./build.sh` first. |

## Developing without Cloudflare

Exactly as in [`../httpbin-clack`](../httpbin-clack/README.md#developing-without-cloudflare):
the synthesized export calls `clack.handler.cloudflare-workers:dispatch`, an
ordinary function, so the whole Worker — routes included — runs on the
interpreter, the JVM and the WASM backends:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Prog.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Prog
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

The first build downloads clack, lack and tiny-routes into
`~/.rontolisp/quicklisp`; after that everything is offline (the `ql:quickload`
is resolved at **compile** time and inlined into the module).

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../httpbin-clack`](../httpbin-clack/README.md#limitations) apply unchanged.
One more is this directory's own, stated above: the lite matcher refuses
regex-shaped templates at route-build time. Everything else — the clackup
banner going to a discarded stdout, the envelope, the arena bracket in
`src/index.js` — reads identically to there.
