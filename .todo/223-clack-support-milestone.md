# Clack support milestone: `(ql:quickload "clack")` + `clackup` on rontolisp

Goal: Eitaro Fukamachi's Clack (web application environment,
https://github.com/fukamachi/clack) loads verbatim via `ql:quickload` and a
Clack app runs through `clack:clackup` on a rontolisp handler backend. The
sources come from the real Quicklisp dist (clack-20250622-git +
lack-20260101-git, already in `~/.rontolisp/quicklisp/software/`).

## Spike result (2026-08-01, interpreter, all workarounds in Lisp userland)

A full end-to-end round trip ALREADY WORKS with ~40 lines of workarounds:
`(ql:quickload "clack")` completes, and

```lisp
(clack:clackup (lambda (env) (list 200 '(:content-type "text/plain") (list "...")))
               :server :rontolisp :port 15055 :use-thread nil
               :use-default-middlewares nil)
```

serves `curl http://127.0.0.1:15055/hello` -> `Hello, Clack on rontolisp! GET /hello`
through a hand-written `clack.handler.rontolisp` package bridging the Clack env
plist onto `rontolisp:http-handler`. Even `~:(~A~)` in clackup's banner renders
("Rontolisp server is going to start."). The workarounds map 1:1 onto the work
units below; nothing else was needed on the minimal path.

Minimal dependency closure (all resolve today except swank): clack <- alexandria
(real, loads), bordeaux-threads (shim, LOCKING ONLY — gap), lack, lack-component,
lack-util <- ironclad/core (slice has `random-data` + `byte-array-to-hex-string`,
verified), lack-middleware-backtrace <- uiop (shim — gaps), swank (nothing — gap),
usocket (shim — 2 names missing), uiop.

## Scope: ALL backends except WASM Preview 1

The target is the interpreter, the JVM AND the WASM `--component` backend
(the cl-postgres/postmodern precedent). Preview 1 alone stays out: no incoming
TCP by design (`.kb/tcp-sockets.md`), so it keeps the call-time-error policy.
Consequences:

- `.todo/229` (runtime intern->funcall name table) is ON the critical path,
  not an optional follow-up — clack's handler protocol is late-bound by name
  and the compile backends have no route to a compiled function today.
- The component leg has a structural wrinkle (see `.todo/228`): the
  `http-handler` directive is detected among TOP-LEVEL forms only
  (`HttpLibrary.process` iterates the program list), while the clack bridge
  calls it inside the shim's `run` defun. Detection must be widened to a
  nested call with a literal quoted handler name.
- Threads (`.todo/227`) stay interpreter+JVM; a component clackup runs with
  `:use-thread nil` semantics (the host owns the socket and lifecycle;
  `stop` is meaningless under `wasmtime serve`) — documented divergence.

## Work units (difficulty in each file)

Interpreter leg lands first in each unit, but a unit is DONE only when the
JVM and component legs are green too (or their divergence is recorded).

1. `.todo/224` ASDF front-end: system-level `:pathname`, `register-system-packages`,
   `load-system` kwargs — 低 — **DONE 2026-08-01** (all four backends; the
   unpatched `lack.asd` parses whole and every lack/clack component file loads,
   see `.kb/asdf.md`). The next blocker on the verbatim path is `.todo/226`'s
   `uiop:symbol-call`, which fails at package-resolution time of lack's
   `src/util.lisp` — a userland `(defun uiop::symbol-call ...)` cannot work
   around it, so the cached-source patch the spike used is NOT reproducible
   without 226.
2. `.todo/225` missing CL builtins batch (substitute-if, file-write-date, sleep,
   ensure-directories-exist, file-length, export, ...) — 低〜中
3. `.todo/226` shim widening: uiop `symbol-call` + `uiop/image`, usocket host
   resolution, swank stub system — 低 — **DONE 2026-08-01** (all four backends;
   see the todo's own status section). With 224 + 226 the whole LACK side now
   loads verbatim with ZERO userland workarounds: `(ql:quickload "lack")` /
   `"lack-util"` / `"lack-middleware-backtrace"` complete on the interpreter and
   the backtrace middleware runs end to end. `(ql:quickload "clack")` now stops
   at `No such package: BT2` in clack's own `src/handler.lisp` — i.e. the next
   blocker is exactly `.todo/227`, and nothing in 224/225/226 stands in front of
   it any more.
4. `.todo/227` bordeaux-threads: `bt2` package + real thread creation — 中〜高
5. `.todo/228` the `clack-handler-rontolisp` backend + E2E + docs — 中〜高
6. `.todo/229` runtime intern->funcall dispatch on the compile backends — 高
   (critical path: the JVM and component legs are in scope, see above)
7. `.todo/230` `subtypep` on class metaobjects (old-Clack middleware detection)
   — 低〜中, optional
8. `.todo/231` lack-request/lack-response + middleware ecosystem (quri lineage)
   — 高, stretch/survey

## Acceptance (interpreter + JVM + WASM component; P1 = call-time error)

- `(ql:quickload "clack")` with ZERO userland workarounds and UNPATCHED cached
  sources (the spike patched `uiop:symbol-call` -> `uiop::` and the two
  `usocket:` names in cached files; those must resolve as exported names).
- `clackup` with `:use-default-middlewares t` (the default) works — this pulls
  `lack-middleware-backtrace` through `builder` and `find-middleware`'s runtime
  `substitute-if` + `asdf:find-system`/`load-system` path.
- `clackup` default `:use-thread t` returns a handler object and the server
  answers in the background; `(clack:stop handler)` stops it.
- E2E test + docs per the asdf-library integration checklist (see `.todo/228`),
  with three live legs — interpreter, JVM class, WASM component under
  `wasmtime serve` — asserting the same HTTP round-trip output, plus the
  Preview 1 call-time-error pin (the `ClPostgresE2eTest` shape).

## Spike appendix (self-contained — the scratchpad copy is gone)

### Verified working ALREADY (no work needed, probed 2026-08-01 interpreter)

alexandria `delete-from-plist` (real alexandria loads), ironclad `random-data`
+ `byte-array-to-hex-string` (lack-util's `generate-random-id` works),
`defgeneric` with a `function` specializer + `t` fallback (lack/component's
`call`/`to-app`), `handler-bind` + `return` block exit (backtrace middleware),
`find-class` with errorp nil, `standard-object`/compound `typecase`,
`(typep x '(vector (unsigned-byte 8)))`, format `~:(~A~)` and `~2&`,
`loop with/for=/until/finally-return` and `for (k v) on ... by #'cddr`,
`reduce :from-end :initial-value`, `&allow-other-keys` + `&rest`+`&key` mix,
`destructuring-bind`, `maphash`, `substitute`, `string-downcase`,
`boundp`/`symbol-value`/`functionp`, runtime `eval` + `multiple-value-list`,
runtime `read` with eof-value, `apply`/`funcall` of an interned symbol
(INTERPRETER only — compile paths are `.todo/229`), runtime
`(asdf:find-system name nil)`, `check-type` with compound types.

Confirmed FAILING (the work units): system-level `:pathname` (224),
`register-system-packages` top-level form (224), `load-system` kwargs (224),
`substitute-if` / `file-write-date` / `sleep` / `ensure-directories-exist` /
runtime `export` undefined, `file-length` on a stream returns NIL (225),
`uiop:symbol-call` internal-not-exported, no `uiop/image` package (226),
usocket `host-to-hostname`/`get-host-by-name` missing (226), no swank system
(226), no `bt2` package / no thread creation in the bt shim (227), `subtypep`
on class metaobjects NIL (230), `(member :quicklisp *features*)` NIL — fine,
it routes find-package-or-load onto the asdf branch, keep it that way.

Cache patches used by the spike (all REVERTED; the real fixes make them moot):
lack.asd rewritten without `:pathname`/`lack/tests`, `register-system-packages`
stripped from 3 lack `.asd`s, `uiop:symbol-call` -> `uiop::` in lack
src/util.lisp, `usocket:host-to-hostname`/`get-host-by-name` -> `usocket::` in
clack src/handler.lisp.

### The full working spike program (interpreter, 2026-08-01)

Run with `java -jar target/rontolisp-*-exec.jar spike.lisp`, then
`curl http://127.0.0.1:15055/hello` -> `Hello, Clack on rontolisp! GET /hello`.
The first block = the workarounds that todos 224-227 replace; the
`clack.handler.rontolisp` block = the starting point for `.todo/228`'s shim.

```lisp
;; Spike: clackup end-to-end on the interpreter with a fake rontolisp handler
(defun uiop::symbol-call (pkg name &rest args)
  (apply (intern (string name) (find-package (string pkg))) args))
(asdf:defsystem "swank")
(defpackage :swank (:export :create-server :stop-server))
(defun swank:create-server (&rest args) (error "swank is not supported"))
(defun swank:stop-server (&rest args) nil)
(defpackage :uiop/image (:export :print-condition-backtrace))
(defun uiop/image:print-condition-backtrace (c &key stream)
  (format stream "~A~%" c))
(defpackage :bt2 (:export :*default-special-bindings* :make-thread :threadp
                          :thread-alive-p :destroy-thread))
(defvar bt2:*default-special-bindings* nil)
(defun bt2:make-thread (fn &rest args) (funcall fn))
(defun bt2:threadp (x) nil)
(defun bt2:thread-alive-p (x) nil)
(defun bt2:destroy-thread (x) nil)
(defun bordeaux-threads::threadp (x) nil)
(defun bordeaux-threads::make-thread (fn &rest args) (funcall fn))
(defun bordeaux-threads::thread-alive-p (x) nil)
(defun bordeaux-threads::destroy-thread (x) nil)

(ql:quickload "clack")

;; --- the rontolisp handler backend, as the shim system would provide it ---
(defpackage :clack.handler.rontolisp (:use :cl) (:export :run :stop))
(in-package :clack.handler.rontolisp)

(defvar *app* nil)

(defun %env (request)
  (list :request-method (intern (string-upcase (getf request :method)) :keyword)
        :script-name ""
        :path-info (getf request :path)
        :query-string (getf request :query)
        :server-name "localhost"
        :server-port 5000
        :server-protocol :http/1.1
        :url-scheme "http"
        :headers (let ((h (make-hash-table :test 'equal)))
                   (dolist (pair (getf request :headers) h)
                     (setf (gethash (car pair) h) (cdr pair))))
        :raw-body (getf request :body)))

(defun %headers->alist (plist)
  (let ((acc nil))
    (loop for (k v) on plist by #'cddr
          do (push (cons (string-downcase (symbol-name k)) v) acc))
    (nreverse acc)))

(defun %bridge (request)
  (let ((res (funcall *app* (%env request))))
    (list :status (first res)
          :headers (%headers->alist (second res))
          :body (apply #'concatenate 'string (third res)))))

(defun run (app &key (port 5000) debug &allow-other-keys)
  (setf *app* app)
  (rontolisp:http-handler '%bridge port))

(defun stop (acceptor) nil)

(in-package :cl-user)
(clack:clackup
 (lambda (env)
   (list 200 '(:content-type "text/plain")
         (list (format nil "Hello, Clack on rontolisp! ~A ~A~%"
                       (getf env :request-method) (getf env :path-info)))))
 :server :rontolisp
 :port 15055
 :use-thread nil
 :use-default-middlewares nil)
```

Spike-run notes: the workaround `defpackage`s need `(:use :cl)` when followed
by `in-package` (the spike first died on unresolved `DEFVAR`); clackup's
banner exercised `~:(~A~)` ("Rontolisp server is going to start."); default
`:debug t` prints the NOTICE block; `:use-default-middlewares nil` avoids the
runtime `substitute-if` hole until 225 lands.

## Out of scope (documented limitations for the first release)

- `clack.socket` / WebSocket (needs a socket-upgrade seam in the server).
- Streaming responses (a Clack app returning a function/responder): v1 may
  error with a clear message; revisit with the async machinery.
- `:swank-port` (swank stays a stub that errors, `.todo/226`).
- Preview 1 WASM leg: no incoming TCP by design (`.kb/tcp-sockets.md`).
