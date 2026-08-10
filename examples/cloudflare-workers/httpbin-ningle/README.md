# httpbin-ningle — the same endpoints, written the way ningle wants them written

[`../httpbin-clack`](../httpbin-clack) answers a mini httpbin with a hand-written
`cond` over `:path-info`. [`../httpbin-tiny-routes`](../httpbin-tiny-routes)
answers the same thing with a *list of routes*. This is the third model:
[ningle](https://github.com/fukamachi/ningle), where the application is a CLOS
**object** you assign routes to.

The endpoints match its two neighbours. **The code deliberately does not** — the
helpers are not shared, because ningle is not a spelling variant of the other
two and writing it in their shape would hide everything about it worth reading.

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

The `(status headers body)` triple a Clack application returns never appears in
this file. A controller returns a **string** — ningle makes that the body — and
says everything else by mutating `ningle:*response*`, the response object bound
for the current request:

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

So nothing in this file reads a stream or parses JSON, and the five echo
endpoints share one controller, because per method there is nothing left to do:

```lisp
(defun echo (params)
  (declare (ignore params))
  (let ((request ningle:*request*))
    (respond-json
     (rontolisp:plist-hash-table
      (list :method (symbol-name (lack.request:request-method request))
            :path (lack.request:request-path-info request)
            :args (rontolisp:alist-hash-table
                   (lack.request:request-query-parameters request))
            :form (rontolisp:alist-hash-table
                   (lack.request:request-body-parameters request))
            :headers (lack.request:request-headers request))))))
```

That is the visible difference from the two neighbours' documents, and it is not
a cosmetic one: they answer `data` (the raw body) and `json` (their own parse of
it) because they read `:raw-body` themselves. Here the parse already happened.

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

A `"/status/:code"` template would match `/status/teapot` as happily, and leave
the controller holding a request it has no good answer for. This way
`/status/teapot` matches no rule at all and reaches:

```lisp
(defmethod ningle:not-found ((app ningle:app)) ...)
```

`ningle:not-found` is a generic function on the application class — ningle's own
extension point, and the 404 is an *override* of it rather than a route at the
bottom of a list, which is where the tiny-routes Worker puts it.

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

Measured on node 24 driving [`src/index.js`](src/index.js)'s boundary code
against the four httpbin modules built together, imports zero in every case
(median of three runs; `warm GET` is the mean of 2,000 calls after 200 warm-up
calls):

| | [`../httpbin`](../httpbin) | [`../httpbin-clack`](../httpbin-clack) | [`../httpbin-tiny-routes`](../httpbin-tiny-routes) | this |
| --- | --- | --- | --- | --- |
| the routing | a `cond` over `:path-info` | the same `cond` | `define-routes` + declining | `setf` on an app object |
| `_initialize` | 7.9 ms | 11.1 ms | 12.2 ms | **72.0 ms** |
| warm `GET` | 0.035 ms | 0.035 ms | 0.034 ms | **0.128 ms** |

Module sizes: the
[size report](../../../size-report/results/cloudflare-workers.md). This is by an
order of magnitude the largest and slowest to start of the four — **and almost
none of that is ningle.** It is the `lack-request` chain the section above is
about: getting the request decoded before the controller runs means
`http-body`, `fast-http`'s generated header and multipart state machines,
`smart-buffer`, `circular-streams`, `yason`, `trivial-mimes` and `quri` all ship
and all run. tiny-routes never touches any of it. It still fits the free plan's
bundle limit with room to spare — this is a cost, not a wall — but it is the
reason to reach for `tiny-routes` when routing is all you need.

There is also no size opt-in to offer the way tiny-routes has one: myway
compiles every rule to a **cl-ppcre scanner**, so the regex engine is genuinely
reachable and no amount of tree-shaking can remove it.

## Developing without Cloudflare

As in [`../httpbin-tiny-routes`](../httpbin-tiny-routes/README.md): the
synthesized export calls `clack.handler.reactor:dispatch`, an ordinary function,
so the whole Worker — routes, the `not-found` method and all — runs on every
backend, which [`examples/examples.yaml`](../../examples.yaml) pins:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Check.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Check
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

(Key order inside a JSON object differs per backend — it follows hash-table
iteration order — which is why the manifest checks with `contains`.)

The first build downloads clack, lack and ningle into `~/.rontolisp/quicklisp`;
after that everything is offline, because the `ql:quickload` is resolved at
**compile** time and inlined into the module.

To serve the same model over a real socket instead, drop the
`clack-handler-reactor` line and use `:server :rontolisp` — that is what
[`examples/net/httpbin-ningle.lisp`](../../net/httpbin-ningle.lisp) does.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | **The whole program**: quickload, the routes, the `not-found` method, `clackup` |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight — the local edit/run loop, and what the examples manifest runs |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../httpbin/src/index.js` |
| `src/worker.wasm` | A build product — run `./build.sh` first |

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../httpbin-clack`](../httpbin-clack/README.md#limitations) apply unchanged.
