# handler-bind

`(handler-bind ((type handler)...) body...)`

Evaluates `body...` with the handlers established. When a condition is signaled during the body — by [`error`](error.md), [`signal`](signal.md), [`warn`](warn.md) or [`cerror`](cerror.md) — each matching handler is called **at the signal point, before any unwinding**, with the condition object as its one argument. That is the difference from [`handler-case`](handler-case.md): the stack between the signaler and the handler is still intact, so the handler can [`invoke-restart`](../functions/invoke-restart.md) a restart established by a [`restart-case`](restart-case.md) *inside* the body and transfer control there. A handler that returns normally **declines**: the search continues with outer handlers, and an unhandled `error` then aborts (or is caught by an enclosing `handler-case`) exactly as if no `handler-bind` were present. The `type` is any `handler-case` clause type, including classes from [`define-condition`](define-condition.md); the handler expressions are evaluated when the `handler-bind` is entered.

Supported on every backend except `--no-gc`. A program using the restart system compiles in EH mode on the wasm-GC backends, so add `-W exceptions=y` to `wasmtime run`/`wasmtime serve`. Handlers also run for the errors **built-ins** raise (a `(car 5)`-style type error, an out-of-range `aref`, an undefined function): the interpreter runs them at the signal point like a signaled condition; the compiled backends run them when the error unwinds past the `handler-bind` itself, so restarts established *inside* the body are gone and intervening [`unwind-protect`](../special-forms/unwind-protect.md) cleanups have already run by then (a **signaled** condition keeps the exact signal-point semantics everywhere). On the wasm-GC backends a failure that traps instead of signaling (`(car 5)` compiles to a failed cast; so does integer division by zero) still ends the program without running handlers — only what rides the condition channel (signaled conditions, an undefined-function call) reaches them.

```lisp
(handler-bind ((error (lambda (c) (invoke-restart :use-value 42))))
  (restart-case (error "boom")
    (:use-value (v) (list :recovered v)))) ; => (:RECOVERED 42)
```

A handler that returns declines, and the error keeps propagating:

```lisp
(let ((log nil))
  (handler-case
      (handler-bind ((error (lambda (c) (setq log :seen))))
        (error "boom"))
    (error (e) (list :caught log)))) ; => (:CAUGHT :SEEN)
```

An error a built-in raises runs the handlers too — how a test framework turns a broken test body into a recorded failure instead of an aborted run:

```lisp
(block b
  (handler-bind ((error (lambda (e) (return-from b :caught))))
    (car 1))) ; => :CAUGHT
```
