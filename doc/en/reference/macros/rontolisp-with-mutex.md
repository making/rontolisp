# rontolisp:with-mutex

`(rontolisp:with-mutex (mutex-form) body...)`

Evaluates `mutex-form` once, acquires the resulting lock (see
[`rontolisp:make-mutex`](../functions/rontolisp-make-mutex.md)), runs the body, and
releases the lock on **every** exit — including one caused by a signalled error. The value
of the last body form is the value of the whole expression (`nil` for an empty body).

This is the form to reach for when a served handler mutates state shared between requests:
[`rontolisp:http-handler`](../functions/rontolisp-http-handler.md) puts one virtual thread
per request on the interpreter and the JVM backend, so a read-modify-write of a global is
a real race there. Both WASM backends run a single thread, so acquire and release are
no-ops and the same source runs on all four.

```lisp
(defvar *counter-lock* (rontolisp:make-mutex))
(defvar *counter* 0)
(rontolisp:with-mutex (*counter-lock*)
  (setq *counter* (+ *counter* 1)))  ; => 1
```

## Arguments

- A one-element list holding the form that produces the mutex. It is evaluated once,
  before the body.
- The body forms, evaluated in order like a `progn` while the lock is held.

## Reentrancy

The lock is reentrant, so nesting is safe — a function that takes the lock may call
another that takes the same lock:

```lisp
(let ((m (rontolisp:make-mutex)))
  (rontolisp:with-mutex (m)
    (rontolisp:with-mutex (m) :nested)))  ; => :NESTED
```

## Limitations

- There is no way to spawn a thread from Lisp; the concurrency comes from the runtime.
- No timed or non-blocking variant: acquisition always blocks.
- Macros have no function value: `#'rontolisp:with-mutex` is an error.
