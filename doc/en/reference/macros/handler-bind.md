# handler-bind

`(handler-bind ((type handler)...) body...)`

Evaluates `body...` with the handlers established. When a condition is signaled during the body — by [`error`](error.md), [`signal`](signal.md), [`warn`](warn.md) or [`cerror`](cerror.md) — each matching handler is called **at the signal point, before any unwinding**, with the condition object as its one argument. That is the difference from [`handler-case`](handler-case.md): the stack between the signaler and the handler is still intact, so the handler can [`invoke-restart`](../functions/invoke-restart.md) a restart established by a [`restart-case`](restart-case.md) *inside* the body and transfer control there. A handler that returns normally **declines**: the search continues with outer handlers, and an unhandled `error` then aborts (or is caught by an enclosing `handler-case`) exactly as if no `handler-bind` were present. The `type` is any `handler-case` clause type, including classes from [`define-condition`](define-condition.md); the handler expressions are evaluated when the `handler-bind` is entered.

Supported on every backend except `--no-gc`. A program using the restart system compiles in EH mode on the wasm-GC backends, so add `-W exceptions=y` to `wasmtime run`/`wasmtime serve`. Handlers run for conditions signaled by Lisp code (`error`/`signal`/`warn`/`cerror` and library code built on them); a runtime failure inside a built-in (a `(car 5)`-style type error) unwinds without running `handler-bind` handlers — `handler-case` still catches those on the interpreter and the JVM.

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
