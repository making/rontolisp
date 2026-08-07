# A Clack application on Cloudflare Workers

Difficulty: Low

`examples/cloudflare-workers/httpbin/` ports `examples/net/httpbin.lisp` to a Worker.
The Clack flavour -- `examples/net/httpbin-clack.lisp` -- has no Worker counterpart,
and it turns out it needs no new language or runtime support: the whole of `clackup`
is replaced by about fifteen lines of adapter, and the application function is
carried over VERBATIM.

## Why it already works

Since the Clack cutover rontolisp's server protocol IS Clack's, and the two halves of
that boundary are already factored out as callable entry points
(`HttpServerLibrary.ENTRY_POINTS`, so naming them from a user program splices the
library): `%http-make-env` turns a positional raw tuple into the Clack environment,
`%http-normalize-response` turns the Clack response into the
`(status header-alist body-string)` triple. A Worker's `index.js` has already parsed
the request, so the adapter only has to build the tuple:

```lisp
(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)

(defun handle-request (request-json)
  (handler-case
      (let* ((req (rontolisp:json-parse request-json))
             (raw (list (gethash "method" req)
                        (gethash "target" req)          ; path?query, still encoded
                        (header-alist (gethash "headers" req))
                        (rontolisp::%http-body-stream (gethash "body" req))
                        "HTTP/1.1" "https" "localhost" 443 nil nil))
             (triple (rontolisp::%http-normalize-response
                      (funcall #'app (rontolisp::%http-make-env raw)))))
        ...(status headers body) as the JSON envelope...)
    (error (e) ...500...)))
```

`app` and every helper under it are `examples/net/httpbin-clack.lisp` unchanged --
including `read-body`, which drains the bivalent `:raw-body` with `read-char`.

## Measured, end to end (2026-08-07)

`app.lisp` = the verbatim httpbin-clack application + the adapter above, built
`--no-wasi --optimize`, driven on node 24 (V8, the same engine family as workerd)
through the byte-for-byte boundary code of `cloudflare-workers/httpbin/src/index.js`:

| | value |
| --- | --- |
| imports | **zero** (so the Worker instantiates with `{}`, no WASI shim) |
| exports | `memory`, `_initialize`, `__ronto_alloc`, `__ronto_alloc_mark`, `__ronto_alloc_reset`, `handle-request` -- IDENTICAL to the existing httpbin Worker, so `src/index.js` is reusable as is |
| module | 1,571,732 B raw / **343,422 B gzip** (existing httpbin: 283,200 / 92,233) |
| `_initialize` | 6.4 ms -- this is where clack's entire load-time runs |
| request | ~5 ms first, **~1.4 ms** warm |

All five endpoints answered correctly, plus 405 for the wrong method, 404 for an
unknown path, and `"json": null` for a body that does not parse.

343 KB gzip is ~11% of the free plan's 3 MB bundle limit, so it fits with room; it is
still 3.7x the hand-rolled Worker, which is the honest trade the README should state.

## What to build

`examples/cloudflare-workers/httpbin-clack/`, mirroring the existing `httpbin/`
directory: `app.lisp`, `demo.lisp`, `build.sh`, `package.json`, `wrangler.jsonc`,
`src/index.js`, `README.md`. Plus the row in
`examples/cloudflare-workers/README.md`'s table and the `examples/examples.yaml`
entry (`demo.lisp`, backends `[interpreter, jvm, wasm]`, following the httpbin
entry's shape).

Two details the JavaScript side must get right, both found by measurement:

- Pass the **raw target** (`url.pathname + url.search`) as one string, NOT the
  pre-split `path` + `query` object the existing httpbin Worker sends:
  `%http-make-env` does the `?` split and the percent-decoding itself, and
  `:path-info` / `:query-string` have to come from it for a Clack app to see what
  Clack promises.
- Forward **`content-length`**. `%http-make-env` reads `:content-length` off the
  header table, and `lack/request`'s body parsing returns nothing without it -- the
  first version of this probe silently produced empty parameters.

The README should say what this directory answers that `httpbin/` does not: the
application is a Clack application, so it is portable to any Clack handler
(hunchentoot, woo, `wasmtime serve`) unchanged, and `clackup` is the ONLY thing the
Worker replaces.

## Dependency

The base example above is unblocked -- it uses no `lack-request`. A variant that
demonstrates the middleware stack (`lack:builder`, `lack/request:request-parameters`,
sessions) needs `.todo/279` first: quickloading `lack-request` alongside a Clack
server currently fails to compile on every compile backend.

Whether to ship the middleware variant here or leave it to `279` is an open call for
whoever picks this up; the base example stands on its own either way.

## Related

`.kb/clack.md`, `.kb/http-server.md`, `examples/cloudflare-workers/README.md`
(the settled facts about workerd: wasm-GC and wasm EH run with no flag, a Worker may
not compile WASM at run time, an instance is per isolate).
