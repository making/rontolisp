# 532. Clack on the Servlet transport (`clackup :server :rontolisp` in a war)

Difficulty: Medium (the shim change is a fourth `run` leg with an existing
precedent to copy; the work is the E2E, which has to quickload Clack and deploy
a war)

Child of `.todo/529`. Blocked by `.todo/530` (needs `Features.JVM_SERVLET` /
`:rontolisp-servlet` and the register-instead-of-serve arm). Reads better after
`.todo/531`, since a mounted Clack application is exactly what misroutes without
it.

## Why the shim needs a leg at all

`:server :rontolisp` means "serve on THIS target's native inbound transport",
and the choice is made at COMPILE time by the reader features -- the
one-clackup-source rule (`.kb/clack.md`). A war is a fourth target, so it is a
fourth leg, and nothing else in the tree has to learn about it.

`clack-handler-rontolisp.lisp`'s current interpreter/JVM leg binds a port and
blocks:

```lisp
#-rontolisp-wasm
(defun clack.handler.rontolisp:run (app &key (port 5000) (address "127.0.0.1") debug &allow-other-keys)
  (declare (ignore debug))
  (setf clack.handler.rontolisp::*app* app)
  (let ((server (rontolisp::%http-server-start app port address :raw-body :buffered)))
    (unwind-protect (progn (rontolisp::%http-server-join server) server)
      (rontolisp::%http-server-stop server))))
```

In a war the container owns the port and `<clinit>` must return.

## What lands

The reactor leg is the shape to copy -- it is the other transport with nothing
to bind and nothing to block on:

```lisp
;; The servlet leg: the container owns the port, so run just registers the
;; application in the single handler slot and returns. `stop` is a no-op:
;; undeploying the war is what stops it.
#+rontolisp-servlet
(defun clack.handler.rontolisp:run (app &key (port 5000) (address "127.0.0.1") debug &allow-other-keys)
  (declare (ignore port address debug))
  (setf clack.handler.rontolisp::*app* app)
  (rontolisp::%http-server-start app 0 nil :raw-body :buffered))
```

...where the exact spelling of the register call is whatever `.todo/530`
decided the `%http-server-start` seam does in war mode. If that item chose to
REFUSE the seam in a war, this leg calls the register primitive it introduced
instead; either way the leg is four lines.

Guards that move with it:

- `#-rontolisp-wasm` on both `run` and `stop` becomes
  `#-(or rontolisp-wasm rontolisp-servlet)`.
- A `stop` leg for `#+rontolisp-servlet` answering `nil`, like the wasm one.
- `:use-thread` is irrelevant here -- there is nothing to run on a thread -- so
  it is ignored, not honoured. `:thread-support` stays in `Features.JVM_SERVLET`
  because the program may still make threads of its own; only `run` stops
  caring.
- The header comment block at the top of the shim lists its transports; the
  fourth goes there.

`:raw-body :buffered` is not optional and is the reason this is worth doing:
lack-request / circular-streams / http-body need a synchronous bivalent body
stream, and the `.todo/529` spike already verified that a buffered POST body
reads correctly through the servlet transport with `read-sequence`.

## Acceptance

- `ClackE2eTest` gains a war leg over BOTH of its application shapes -- the bare
  handler lambda and the tiny-routes routing application -- because "one handler
  function" is not what an application looks like and that is why the suite runs
  both (`.kb/clack.md`).
- `NingleE2eTest` likewise, or a stated reason it is redundant here. The ningle
  legs are the ones that read a request BODY on the normal path, which is the
  half of the transport a lambda never touches.
- `RontoLispCliTest` gains the `clackRontolispBackendUnderNoWasi*` twin for war
  mode: the compiled program must contain no bind and no join.
- `.kb/clack.md`'s "How the `:rontolisp` backend picks its transport" list goes
  from three bullets to four; `doc/{en,ja}/guides/clack.md` gains the war row,
  mirrored.
