# httpbin-ningle — the same endpoints on an application object

The endpoints of [`../httpbin-clack`](../httpbin-clack) written the way
[ningle](https://github.com/fukamachi/ningle) wants them: the application is a
CLOS **object** you assign routes to, and the request arrives already decoded.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
rontolisp check.lisp    # drive the whole Worker locally, on any backend
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"method":"GET","path":"/get","args":{"a":"1","b":"two"},"form":{},"headers":{...}}

$ curl -H 'content-type: application/json' -d '{"name":"rontolisp"}' \
       http://localhost:8787/post
{"method":"POST","path":"/post","args":{},"form":{"name":"rontolisp"},...}

$ curl http://localhost:8787/status/418
418

$ curl http://localhost:8787/status/teapot     # not three digits -> no rule
{"error":"not found","path":"/status/teapot"}  # matches -> not-found
```

## The four things that make it ningle

### A route is an assignment, so routes can be a loop

There is no enclosing route-list form. `(setf (ningle:route app url) controller)`
mutates the application object, which means routes can be added from another
file, from a function, or at run time — and that the five echo endpoints are
written once:

```lisp
(dolist (endpoint '(("/get" . :GET) ("/post" . :POST) ("/put" . :PUT)
                    ("/patch" . :PATCH) ("/delete" . :DELETE)))
  (let ((path (car endpoint)) (allowed (cdr endpoint)))
    (setf (ningle:route *app* path :method allowed) #'echo)
    (setf (ningle:route *app* path :method :ANY) ...)))   ; the 405
```

Rules are tried in the order they were assigned, so the second rule of each pair
is a per-path fallback: the method that path answers goes to `echo`, anything
else to a 405 naming it. That leaves `ningle:not-found` with only the answer it
is really for.

### A controller returns the body; `*response*` carries the rest

The `(status headers body)` triple never appears in this file. A controller
returns a **string** — ningle makes that the body — and says everything else by
mutating `ningle:*response*`, the response object bound for the current request:

```lisp
(defun respond-json (object)
  (setf (lack.response:response-headers ningle:*response*)
        (list :content-type "application/json"))
  (format nil "~a~%" (rontolisp:json-stringify object)))

(defun set-status (code)
  (setf (lack.response:response-status ningle:*response*) code))
```

### The request arrives decoded, so there is one echo controller

A controller receives the **parameters**, not the Clack environment — and by the
time it runs, `lack/request` has decoded the query string *and parsed the request
body*. `args` is the query string, `form` is the parsed body (for the JSON post
above, the JSON object itself), and the alist ningle hands the controller is
those two appended.

So nothing here reads a stream or parses JSON, and the five echo endpoints share
one controller. That is also the visible difference from the neighbouring
documents, and not a cosmetic one: they answer `data` (the raw body) and `json`
(their own parse of it) because they read `:raw-body` themselves. Here the parse
already happened.

### Declining means not matching — so `/status` is a regex rule

Returning `nil` from a ningle controller is **not** a decline; it answers an
empty body. The only way a route declines is by not matching, so the status
endpoint is written as a rule that cannot match a bad code — myway's other rule
spelling, a regex instead of a template, whose capture groups arrive as
`:captures`:

```lisp
(setf (ningle:route *app* "/status/([0-9]{3})" :regexp t)
      (lambda (params)
        (let ((code (parse-integer (first (cdr (assoc :captures params))))))
          (set-status code)
          (respond-text code))))
```

A `"/status/:code"` template would match `/status/teapot` as happily and leave
the controller holding a request it has no good answer for. This way
`/status/teapot` matches no rule at all and reaches `ningle:not-found`, a generic
function on the application class — ningle's own extension point, and the 404 is
an *override* of it rather than a route at the bottom of a list.

## The endpoints

| | |
| --- | --- |
| `GET /get` | echo the request: `method`, `path`, `args`, `form`, `headers` |
| `POST /post`, `PUT /put`, `PATCH /patch`, `DELETE /delete` | the same, with the parsed body in `form` |
| any of those, wrong method | 405 from that path's `:ANY` rule, naming the method that works |
| `ANY /anything` | echo, whatever the method — `:ANY` used as itself rather than as a fallback |
| `GET /status/NNN` | answer with that status; a code that is not three digits matches no rule |
| anything else | 404 from the overridden `ningle:not-found` |

## What it costs

This is by an order of magnitude the largest and slowest to start of the four
httpbin Workers ([size
report](../../../size-report/results/cloudflare-workers.md)) — **and almost none
of that is ningle.** It is the `lack-request` chain the section above is about:
getting the request decoded before the controller runs means `http-body`,
`fast-http`'s generated header and multipart state machines, `smart-buffer`,
`circular-streams`, `yason`, `trivial-mimes` and `quri` all ship and all run.
tiny-routes never touches any of it. There is no size opt-in to offer either:
myway compiles every rule to a **cl-ppcre scanner**, so the regex engine is
genuinely reachable. It still fits the free plan's bundle limit with room to
spare — a cost, not a wall — but it is the reason to reach for `tiny-routes`
when routing is all you need.

## Developing without Cloudflare

As in [`../httpbin-clack`](../httpbin-clack/README.md): the synthesized export
calls `clack.handler.reactor:dispatch`, an ordinary function, so the whole
Worker — routes, the `not-found` method and all — runs on every backend, which
[`examples/examples.yaml`](../../examples.yaml) pins:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Check.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Check
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

(Key order inside a JSON object differs per backend — it follows hash-table
iteration order — which is why the manifest checks with `contains`.)

To serve the same model over a real socket, drop `clack-handler-reactor` and use
`:server :rontolisp` — that is what
[`examples/net/httpbin-ningle.lisp`](../../net/httpbin-ningle.lisp) does.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | **The whole program**: quickload, the routes, the `not-found` method, `clackup` |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight — the local edit/run loop |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../httpbin/src/index.js` |
| `src/worker.wasm` | A build product — run `./build.sh` first |

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../httpbin-clack`](../httpbin-clack/README.md#limitations) apply unchanged.
