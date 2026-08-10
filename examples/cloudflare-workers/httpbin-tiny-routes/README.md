# httpbin-tiny-routes — the same endpoints, composed

The five echo endpoints of [`../httpbin-clack`](../httpbin-clack) written the
way [tiny-routes](https://github.com/jeko2000/tiny-routes) wants them: route
macros instead of a `cond`, a `/status/:code` **path template**, the
route-decline protocol, and **middleware** for everything a handler would
otherwise do itself.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"args":{"b":"two","a":"1"},"headers":{...},"method":"GET","path":"/get"}

$ curl http://localhost:8787/status/418
418

$ curl http://localhost:8787/status/teapot     # :code must parse -> the route
{"error":"not found","path":"/status/teapot"}  # declines into the 404
```

## Middleware does the work

No echo handler reads a stream, parses a query string or sets a header. `pipe`
threads the route table through the library's own middleware:

```lisp
(defparameter *app*
  (tiny:pipe *routes* (tiny:wrap-request-body) (tiny:wrap-query-parameters)))
```

so `(tiny:request-body req)` is the raw body as a string and
`(tiny:request-get req :query-parameters)` is the parsed query — and each route
group gets its content type the same way:

```lisp
(tiny:pipe *json-routes* (tiny:wrap-response-content-type "application/json"))
```

`/status/:code` answers `text/plain`, so it is a group of its own; it is also
the one route that names a status, which `tiny:make-response` takes.

`tiny` is the library's own nickname, so there is no `defpackage` in the file:
every name tiny-routes contributes is reachable qualified.

## Declining is the whole error story

Each echo endpoint is declared with the macro for the **one** method it answers,
so a wrong method claims nothing and falls through — and the single catch-all at
the bottom decides both of httpbin's error answers: a path that is one of the
five gives the 405 (naming the method that would have worked), any other path
the 404. One route, not one per endpoint.

`/status/:code` declines too, on a `:code` that is not a number, and the
catch-all has no entry for it — which is exactly the 404 httpbin answers for
`/status/teapot`.

`PATCH` has no macro in tiny-routes. Matching the method is the whole of what
those add over `define-any`, and the matcher is exported, so that one route is
spelled the way the macros expand:

```lisp
(tiny:wrap-request-matches-method
 (tiny:define-any "/patch" (req) (echo req t)) :patch)
```

| | |
| --- | --- |
| `GET /get` | echo the request: `args`, `headers`, `method`, `path` |
| `POST /post`, `PUT /put`, `PATCH /patch`, `DELETE /delete` | the same, plus `data` (the raw body) and `json` (its parsed value) |
| any of those, wrong method | 405 from the catch-all, naming the method that works |
| `GET /status/NNN` | answer with that status; a non-numeric `:code` declines |
| anything else | 404 from the catch-all |

`args` comes from tiny-routes' `parse-query-parameters`, which splits on `&`
and `=` and does not percent-decode — the library's own behaviour, and the one
visible difference from the neighbouring documents.

## `tiny-routes/lite`

The opt-in system: the same library with the path-template matcher swapped for a
ppcre-free one, described in
[`../hello-tiny-routes`](../hello-tiny-routes/README.md#tiny-routeslite-and-why-it-is-on-the-quickload-line).
This is where the choice is measured — the
[size report](../../../size-report/results/cloudflare-workers.md) carries both
rows, built from this `worker.lisp` with only the `ql:quickload` line changed
(they answer the same probes byte for byte).

## Developing without Cloudflare

As in [`../httpbin-clack`](../httpbin-clack/README.md): the synthesized export
calls `clack.handler.reactor:dispatch`, an ordinary function, so the whole
Worker — routes included — runs on every backend:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Check.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Check
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

The first build downloads clack, lack and tiny-routes into
`~/.rontolisp/quicklisp`; after that everything is offline, because the
`ql:quickload` is resolved at **compile** time and inlined into the module.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | **The whole program**: quickload, the handlers, the routes, the middleware, `clackup` |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight — the local edit/run loop |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../httpbin/src/index.js` |
| `src/worker.wasm` | A build product — run `./build.sh` first |

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../httpbin-clack`](../httpbin-clack/README.md#limitations) apply unchanged.
One more is this directory's own: the lite matcher refuses regex-shaped
templates at route-build time.
