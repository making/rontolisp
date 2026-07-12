# handler-case

`(handler-case expression (type ([var]) body...)... [(:no-error ([var]) body...)])`

Evaluates `expression`; when an error is signaled during it, control transfers to the first clause whose condition `type` matches the signaled condition, with `var` (optional) bound to the condition object, and the clause body's value becomes the value of the whole form. When no clause matches, the error propagates outward (an enclosing `handler-case` may still catch it). The clause type is any `typecase` specifier, including condition classes defined by [`define-condition`](define-condition.md) and the built-in hierarchy (`condition` > `serious-condition` > `error`, `warning`); an error signaled without a condition object (a plain `(error "...")` or a runtime failure inside a built-in) is caught as a `simple-error` whose slot 1 carries the message. The `:no-error` clause runs on normal completion with `var` bound to the (primary) value, outside the handler. Non-local exits (`return`/`return-from`) pass through uncaught, and an `unwind-protect` inside the expression runs its cleanup before the handler.

`handler-case` is supported on the **interpreter and the JVM backend**; the WASM compilers reject it (a WASM error is an uncatchable trap). Handlers are per thread of control, so concurrent `rontolisp:http-handler` requests do not interfere. Lite: `handler-bind`/`restart-case` restarts are not available (`.todo/116` Phase 4).

```lisp
(handler-case (error "boom")
  (error (e) (list :caught (nth 1 e)))) ; => (:caught "boom")
```

Typed conditions dispatch through the class hierarchy, first matching clause wins:

```lisp
(define-condition low-fuel (warning) ((level :initarg :level :reader low-fuel-level)))
(handler-case (error 'low-fuel :level 5)
  (error (e) :error)
  (warning (w) (list :warned (low-fuel-level w)))) ; => (:warned 5)
```

```lisp
(handler-case (+ 1 2)
  (error (e) :err)
  (:no-error (v) (list :ok v))) ; => (:ok 3)
```
