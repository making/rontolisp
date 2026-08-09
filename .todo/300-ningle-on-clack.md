# ningle: the second routing library on Clack

Difficulty: Medium

[ningle](https://github.com/fukamachi/ningle) 0.3.0 (dist `ningle-20241012-git`,
LLGPL, Eitaro Fukamachi) is the "super micro framework" half of the fukamachi
web stack — Sinatra-shaped routing over Clack, `(setf (ningle:route app "/x")
controller)`, path templates with `:name` tokens and `*` splats, regex routes,
requirement-based dispatch (`:accept` and user-defined), `*request*`/`*response*`
/`*session*` specials and a per-request `context`. tiny-routes is already ours
(`.todo/291`, `.kb/clack.md`); ningle is the other router a Clack user reaches
for, and unlike tiny-routes it is CLOS-based and brings a routing engine of its
own (myway).

A spike (2026-08-09) established that the library is **five gaps away from
running on all four backends**, and that FOUR of the five are general bugs that
have nothing to do with ningle. The spike ran the real upstream sources with
only those five patched — everything else worked untouched:
`uiop:define-package` with `:use-reexport`/`:shadowing-import-from`, `defclass`
over a `lack-component`, `defmethod call :around` with `call-next-method`,
`(setf (find-class '<app>) (find-class 'app))`, a `defgeneric` **`(setf route)`**
writer with `&rest args &key ... &allow-other-keys`, `initialize-instance
:after`, `symbol-macrolet` (`with-context-variables`), `defstruct (:include ...)`
with a typed slot, `map-set`'s adjustable fill-pointer vector, `check-type`,
`multiple-value-bind` over `call-next-method`, the whole cl-ppcre matcher
(`split` with `:with-registers-p`, `regex-replace-all` with `:simple-calls t`,
`scan-to-strings`, `quote-meta-chars`), `read-from-string` interning `:name`
keywords, `~{~A~}` / `~:[~;~:*~A~]` / `~A` format directives, and the lack
request/response/session chain.

## The dependency chain, and what it costs

`ningle` -> `ningle/main` -> `ningle/app` + `ningle/context` + `ningle/route`
-> **myway** (`myway-20221106-git` v0.1.0, LLGPL — Sinatra-compatible routing:
rule compilation, mapper, `next-route`) -> cl-ppcre, quri, alexandria,
cl-utilities, **map-set** (`map-set-20230618-git`, BSD-3-Clause, Robert Smith —
90 lines, an insertion-ordered set) — plus `lack-component` / `lack-request` /
`lack-response`, all already ours (`.kb/lack.md`).

Nothing here has a ppcre-free route: myway compiles every rule to a scanner, so
unlike `tiny-routes/lite` there is no size opt-in to offer.

**The module is big, and it is not ningle's fault.** Measured on the spike
build, `--no-wasi --optimize=size`, `gzip -9 -n`:

| program | raw | gzip |
| --- | --- | --- |
| `clackup` + clack, one `defun` handler | 247,992 | 75,286 |
| the same + `lack-request` (one `make-request`) | 2,224,140 | 495,245 |
| the same + ningle, three routes | 2,658,950 | 606,783 |

So **lack-request's chain is ~2 MB of the 2.66 MB** — `http-body` -> `fast-http`
(the generated header/multipart state machines), `smart-buffer`,
`circular-streams`, `yason`, `trivial-mimes`, `quri`. ningle itself, myway and
map-set are the last 435 KB. tiny-routes escapes this because it never touches
lack-request; ningle's `call` reads `request-headers`/`-method`/`-path-info`/
`-parameters` on every request, so there is no route around it. Still inside
Cloudflare's limit (3 MB compressed on the free plan), but it makes a
"shake http-body when only urlencoded parsing is reachable" item worth filing
once ningle lands. Other targets, same source: a `--component` serve build
3,184,731 B, the JVM class 3,001,700 B, Preview 1 2,782,046 B.

## The five gaps

1. ~~`.todo/301`~~ — **DONE 2026-08-09.** `:class :package-inferred-system`.
   `ningle.asd` has no `:components` at all; the component graph is derived from
   each file's `defpackage`, and its three `register-system-packages` lines are
   what map the package `lack.request` to the system `lack-request`. Both `.asd`
   consumers now do it (`.kb/asdf.md`), so `(ql:quickload "ningle")` resolves
   and splices the whole graph — ningle, myway, map-set, the lack trio — on all
   four backends, and now stops at gap 2 below instead.
2. ~~`.todo/302`~~ — **DONE 2026-08-09.** `defstruct (:print-function ...)` and
   its `:print-object` sibling. Both lower to a synthesized `defmethod
   print-object` on the struct type, so they cost no printer machinery of their
   own (`.kb/defstruct.md`); `~:P` needed nothing (the runtime renderer has had
   `%fmt-plural` all along). `(ql:quickload "map-set")` now loads the verbatim
   upstream file and a map-set prints `#<MAP-SET of N element(s)>` — SBCL-identical
   — on all four backends, and `(ql:quickload "ningle")` LOADS. Same pass, because
   the map-set printer is what exposed it: `print-unreadable-object`'s `:type t`
   now follows `*print-escape*` (qualified under `prin1`, bare under `princ`), which
   is how CL writes the type symbol.
3. **`.todo/303`** — a closure over a LOOP `for (name val) on ... by #'cddr`
   variable sees NIL. ningle compiles a route's requirements into closures over
   exactly those variables, so **every requirement silently never matches** —
   `:accept` content negotiation and every `ningle:requirement` answer 404 with
   no error anywhere.
4. **`.todo/304`** — `pathnamep` claims every string, so lack's
   `finalize-response` shapes a STRING controller result as a bare-string Clack
   body, which the transport refuses. `(setf (ningle:route *app* "/") "Welcome
   to ningle!")` — the first line of ningle's README — is a 500.
5. **A response body list holding NIL** (this item). ningle's `not-found` sets
   the status and returns nil, and lack's `finalize-response` answers
   `(404 () (NIL))` — a body LIST whose one element is NIL. `%http-body-string`
   rejects it ("a list response body must hold strings"), so **every ningle 404
   is a 500**, as is every controller that returns nil. Upstream renders it as
   nothing: clack-handler-hunchentoot writes each chunk through
   `flex:string-to-octets`, and that answers `#()` for NIL (verified on SBCL).
   Match that — a NIL element contributes the empty string — in BOTH
   implementations of the contract: `%http-join-strings` in
   `src/main/resources/am/ik/rontolisp/eval/http-server.lisp` (the compile
   paths) and `LispEvaluator.responseBody` (the interpreter's Java mirror). Pin
   it in the `http-response-normalizer` case of `ci-spec.yaml`, next to the
   bodyless form.

Gaps 1 and 2 are LOAD failures; 3, 4 and 5 are silent-or-500 runtime failures
that only a real application reaches, which is why the spike drove a real one.

## Verified in the spike, all four backends

With the five patched, this ran on the interpreter, the JVM, WASM Preview 1
(routing only — no incoming TCP) and the WASM `--component` under
`wasmtime serve`, and the routing results were **byte-identical to SBCL 2.6.5**
running the same ningle sources:

```lisp
(ql:quickload "ningle")
(ql:quickload "clack")

(defpackage :demo (:use :cl))
(in-package :demo)

(defvar *app* (make-instance 'ningle:app))

;; a bare value is a controller: the response body
(setf (ningle:route *app* "/") "Welcome to ningle!")

;; :name path template -> an alist entry keyed by the keyword
(setf (ningle:route *app* "/hello/:name")
      (lambda (params) (format nil "Hello, ~A" (cdr (assoc :name params)))))

;; splats
(setf (ningle:route *app* "/say/*/to/*")
      (lambda (params) (format nil "splat=~S" (cdr (assoc :splat params)))))

;; a regex route
(setf (ningle:route *app* "/re/([\\w]+)" :regexp t)
      (lambda (params) (format nil "cap=~A" (first (cdr (assoc :captures params))))))

;; the full Clack triple, when a controller wants the headers
(setf (ningle:route *app* "/list")
      (lambda (params) (declare (ignore params))
        '(200 (:content-type "text/plain") ("as-list"))))

(clack:clackup *app* :server :rontolisp :port 5599 :use-thread nil)
```

```console
$ curl -s localhost:5599/                 -> Welcome to ningle!
$ curl -s localhost:5599/hello/Eitaro     -> Hello, Eitaro
$ curl -s localhost:5599/say/hello/to/world -> splat=("hello" "world")
$ curl -s localhost:5599/re/abc           -> cap=abc
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:5599/nope   -> 404
```

The wider exercise (also SBCL-identical once `.todo/303` is in) covered: a
SYMBOL controller, `:method` (single and a list), `:accept` negotiation between
two routes on one path, a user-defined `ningle:requirement`, mutating
`lack.response:response-status` / `-headers` through `ningle:*response*`,
`ningle:context` and `with-context-variables`, query parameters, POST body
parameters over the buffered `:raw-body`, a controller returning nil,
`ningle:next-route` falling through to a second route on the same path, and a
`lack:builder :session` round trip (a cookie-backed counter incrementing across
two requests, over a real socket).

## The Worker example, already verified

`hello-tiny-routes`' counterpart ran in the spike on the interpreter, the JVM
(`java -cp rontolisp-exec.jar:. HelloNingle`) and wasm-GC, through the same
`check.lisp` shape — so this is the example's source, not a sketch:

```lisp
;;; worker.lisp
(ql:quickload '("clack" "clack-handler-reactor" "ningle"))

(defpackage :hello-ningle (:use :cl))
(in-package :hello-ningle)

(defvar *app* (make-instance 'ningle:app))

;; a bare value is a controller: ningle answers it as the body
(setf (ningle:route *app* "/")
      (format nil "Hello from ningle on Cloudflare Workers!~%"))

(setf (ningle:route *app* "/hello/:name")
      (lambda (params)
        (format nil "Hello, ~a!~%" (cdr (assoc :name params)))))

;; the 404 is a METHOD, not a route: ningle's own extension point
(defmethod ningle:not-found ((app ningle:app))
  (setf (lack.response:response-status ningle:*response*) 404)
  (format nil "no route for ~a~%"
          (lack.request:request-path-info ningle:*request*)))

(clack:clackup *app* :server :reactor :use-thread nil)
```

```console
--> /                  <-- {"status":200,"headers":[],"body":"Hello from ningle on Cloudflare Workers!\n"}
--> /hello/rontolisp   <-- {"status":200,"headers":[],"body":"Hello, rontolisp!\n"}
--> /anything          <-- {"status":404,"headers":[],"body":"no route for /anything\n"}
```

(Key order differs per backend, as `hello-tiny-routes`' manifest entry already
notes; check with `contains`.) Overriding `not-found` instead of adding a
catch-all route is the deliberate difference from the tiny-routes Worker — it is
where ningle and a route-list router genuinely differ, and it exercises
`defmethod` on a library generic from the application's package.

## Work

- Both load blockers are in (`.todo/301`, `.todo/302`), so `(ql:quickload
  "ningle")` already loads: land `.todo/303` and `.todo/304`, then gap 5 above.
- Verify `(ql:quickload "ningle")` UNPATCHED and the two programs above on all
  four backends. The compile paths must be driven through `ql:quickload` in the
  program, not through `load` of the dist files — that is the path `LoadInliner`
  splices.
- `NingleE2eTest`, modelled on `ClackE2eTest`'s tiny-routes legs (live
  `ql:quickload`, opt-in): interpreter + JVM over a real socket, and the
  `--component` leg under `wasmtime serve`. A hermetic `AsdfLibraryE2eSupport`
  test in the style of `TinyRoutesE2eTest` is NOT on — it would mean vendoring
  the whole lack request/response chain (http-body, fast-http, circular-streams,
  smart-buffer, quri, …) into `src/test/resources`, where tiny-routes needed
  only cl-ppcre.
- Examples, matching what tiny-routes has:
  - `examples/net/httpbin-ningle.lisp` — `httpbin-clack.lisp`'s five echo
    endpoints routed through ningle (`(setf (ningle:route ...))` per method, a
    `/status/:code` path template, a catch-all telling 405 from 404), with the
    same "everything below the quickload is the Worker's `worker.lisp`
    verbatim" relationship the tiny-routes one has. `examples/examples.yaml`
    entry with `backends: [jvm-compile, wasm-component]` (clackup blocks, so
    compile-only), plus its row in `examples/README.md`.
  - `examples/cloudflare-workers/hello-ningle/` — the reactor leg: `worker.lisp`
    + `check.lisp` (drives `dispatch` on the interpreter, the JVM and wasm-GC,
    `backends: [interpreter, jvm, wasm]`) + `build.sh` + `wrangler.jsonc` +
    `package.json`, reusing `hello-clack/src/index.js` byte-identically, plus
    rows in `examples/cloudflare-workers/README.md` (including the measured
    size) and `examples/README.md`.
  - Record the real module sizes when they are measured; the numbers above are
    the spike's and predate the fixes.
- Docs, both languages, same file set, byte-identical code fences:
  - a row in the `doc/{en,ja}/guides/asdf-systems.md` "What can I actually load?"
    table naming the version, the backends and the Preview-1 caveat — the
    tiny-routes row is the template, and the size opt-in paragraph has no
    counterpart here (say why: myway compiles every rule to a scanner).
  - a pointer from `doc/{en,ja}/guides/clack.md`, beside the tiny-routes one:
    the guide teaches the application protocol, and "how do I get from one
    handler function to a set of routes" now has two answers.
- `.kb/clack.md`: ningle beside tiny-routes as a verified routing layer, and the
  fact that the requirement machinery is what makes it a different test from
  tiny-routes (closures compiled at route-definition time).

## Not in scope

- `ningle-test` — it depends on `prove`, `lack-test` and `yason`; the first two
  are unmeasured here. Verify with our own E2E instead, like every other library.
- ningle's Caveman-flavoured extras that are not in this dist (`ningle/context`
  is all there is).
- WebSocket (`clack.socket`), still out per `.todo/223`.

## Done when

`(ql:quickload "ningle")` loads the unpatched upstream sources on all four
backends; the two programs above answer identically on the interpreter, the JVM
and the `--component` build (routing only on Preview 1), pinned by
`NingleE2eTest`; and the examples, the README rows and both guide tables are in.
