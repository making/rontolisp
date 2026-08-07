# `(clack:clackup #'app :server :cloudflare-workers)` on a `--no-wasi` reactor

Difficulty: Medium

Make the two halves of `examples/cloudflare-workers/httpbin-clack/` symmetric:

```lisp
;; serve.lisp -- today
(load "app.lisp")
(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)

;; worker.lisp -- today                    ;; worker.lisp -- the goal
(ql:quickload "clack-handler-cloudflare-workers")
(load "app.lisp")                          (load "app.lisp")
(rontolisp:wasm-export 'handle-request
  :params '(:string) :returns :string)
(defun handle-request (json)
  (clack.handler.cloudflare-workers:handle
   #'app json))                            (clack:clackup #'app :server :cloudflare-workers)
```

`.todo/281` is the general version of this and is scoped as High: it wants ONE
source running on every host with no `#+`/`#-`, and leaves the entry-point
convention, the `:server` designator and the `clackup`/`run` contract all open.
This item is the narrow, already-measured slice: the designator is settled
(`:cloudflare-workers`, because the handler backend now exists under that name),
the envelope is settled (it is the backend's documented API), and the remaining
work is two mechanical pieces plus one real decision.

## What is already true (measured 2026-08-07, node 24)

**`clackup` itself runs on a `--no-wasi` reactor today.** `.todo/281` reported
`_initialize TRAPPED: unreachable` and concluded clackup does not work there.
That conclusion was too broad: the trap came from the `:rontolisp` backend's
wasm `run`, which delegates to the `rontolisp:http-handler` directive (a
Preview-1 call-time error). With a backend whose `run` merely stores the app,
clackup works — as long as its two `format t` calls are suppressed:

```lisp
(ql:quickload "clack")
(ql:quickload "clack-handler-cloudflare-workers")
(defpackage :clack.handler.probe (:use :cl) (:export :run :stop))
(defvar clack.handler.probe::*app* nil)
(defun clack.handler.probe:run (app &rest ignored)
  (declare (ignore ignored)) (setf clack.handler.probe::*app* app) nil)
(defun clack.handler.probe:stop (server) (declare (ignore server)) nil)
(defun app (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "~a ~a" (getf env :request-method) (getf env :path-info)))))
(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)
(defun handle-request (json)
  (clack.handler.cloudflare-workers:handle clack.handler.probe::*app* json))
(clack:clackup #'app :server :probe :use-thread nil :silent t :debug nil)
```

```console
$ rontolisp p.lisp -o p.wasm --no-wasi --optimize
$ node -e '...instantiate, _initialize, call handle-request...'
handle-request -> {"body":"GET /hi","headers":[["content-type","text/plain"]],"status":200}
```

Drop **either** keyword and it traps again:

| `clackup` arguments | `_initialize` |
| --- | --- |
| `:use-thread nil :silent t :debug nil` | **OK** |
| `:use-thread nil :silent t` | TRAP |
| `:use-thread nil :debug nil` | TRAP |
| `:use-thread nil :silent t :use-default-middlewares nil` | TRAP |

The two prints are independent and are in upstream clack:

- `clackup` prints `"~:(~A~) server is going to start.~%Listening on ~A:~A"`
  under `(and (not use-thread) (not silent))`. On WASM `use-thread` defaults to
  nil (no `:thread-support`), so the banner always fires — `:silent t` is the
  only off switch.
- `clack.handler:run` prints `"NOTICE: Running in debug mode..."` under
  `(when debug)`, and `debug` defaults to `t`. `:silent` does not cover it;
  only `:debug nil` does.

Under `--no-wasi` a `format t` lowers to the stubbed `fd_write` import, which is
a bare `unreachable` — confirmed in the disassembly (the trapping callee is the
`(param i32 i32 i32 i32) (result i32)` stub, reached from the top-level init
function). The individual pieces are all fine on their own: `clack.util:find-handler`,
`clack.handler:run`, `lack:builder :backtrace` and a `(cons :clackup *features*)`
binding each instantiate cleanly.

## What is left to build

1. **The backend's `run`.** `clack.handler.cloudflare-workers:run` currently
   signals ("clackup cannot run on a host-driven reactor"). It becomes: store
   the app in `*app*`, return nil, and touch NOTHING that Preview 1 stubs — in
   particular not the `rontolisp:http-handler` directive, which is precisely
   what makes the `:rontolisp` backend trap here. On the interpreter and the JVM
   it must do something useful too; see the decision below.

2. **Synthesizing the export.** `rontolisp:wasm-export` needs a literal quoted
   name at compile time, so the user's program can no longer declare it. The
   precedent is exact and already in the tree: for `--component`,
   `HttpLibrary.process` detects the `http-handler` directive NESTED in the
   shim's `run`, extracts the static handler name, lowers the call site to nil,
   and APPENDS a synthesized bridge plus `(rontolisp:wasm-export '%serve-handle
   :as "handle" ...)` after the program, so package-qualified names resolve
   against the shim's own spliced `defpackage`. The reactor needs the same shape
   and less of it — no async, no task-return, no `:raw-body` variants:

   ```lisp
   (defun %reactor-dispatch (%reactor-json)
     (clack.handler.cloudflare-workers:handle clack.handler.cloudflare-workers::*app*
                                              %reactor-json))
   (rontolisp:wasm-export '%reactor-dispatch :as "handle-request"
                          :params '(:string) :returns :string)
   ```

   The trigger should be a marker the shim's `run` contains — a new INTERNAL
   directive (`rontolisp::%http-reactor`) detected the way `http-handler` is,
   rather than overloading `http-handler` itself, which means something else on
   every other backend.

3. **The two prints.** This is the decision, and it is worth making
   deliberately rather than papering over:

   - *Have the example pass `:silent t :debug nil`.* Zero work, works today —
     but it is exactly the asymmetry this item exists to remove, and every
     Worker author would have to know the incantation.
   - *Give `--no-wasi` stdout a sink instead of a trap.* Then clackup works
     verbatim, and so does every other library that logs at load time. This is
     the same root cause as `.todo/284` (a WASI-only primitive reached from a
     top-level form becomes a bare `unreachable` with no diagnostic), so the two
     items should be decided together — a discard sink, a host-supplied optional
     import, or the todo-195 call-time-error policy applied to the `--no-wasi`
     stubs.
   - *Have `run` bind `*standard-output*` to a broadcast stream over nothing.*
     Cannot work: `clackup`'s banner is printed BEFORE `run` is applied.

   The second option is the only one that makes the target snippet work
   unchanged, which is what this item is for.

## What `run` should do on the interpreter and the JVM

`demo.lisp` drives `handle-request` on all three backends today, and that is how
the example is pinned by `examples/examples.yaml`. If `worker.lisp` stops
defining `handle-request`, the demo needs an entry point that exists everywhere.
Options: `run` defines a real `handle-request` function on the non-WASM
backends (symmetric, but a `defun` from a library is surprising); or the shim
exports a `dispatch` the demo calls; or `demo.lisp` calls
`clack.handler.cloudflare-workers:handle` directly and stops pretending to be
the Worker. Pick one and say why in `.kb/clack.md`.

## Done when

- The target `worker.lisp` above compiles with `--no-wasi --optimize` and its
  instance answers requests, verified on V8 (node is enough; `wrangler dev` for
  the real check) — not inferred.
- `serve.lisp` and `worker.lisp` differ only in the `clackup` arguments.
- `examples/examples.yaml` still pins the example on the interpreter, the JVM
  and wasm-GC, and the deployed Worker still answers all five endpoints.
- `.kb/clack.md`'s reactor section records what `run` does per backend and WHY
  the print decision went the way it did, so the next visitor can tell whether
  the reason still holds.

## Related

`.kb/clack.md` (the reactor backend section), `.todo/281` (the general
host-driven-reactor item this is a slice of — close or narrow it if this lands),
`.todo/284` (`--no-wasi` traps at `_initialize` on a WASI-only primitive; the
same root cause as the prints above),
`examples/cloudflare-workers/httpbin-clack/`.
