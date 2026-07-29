# handler-case

`(handler-case expression (type ([var]) body...)... [(:no-error ([var]) body...)])`

Evaluates `expression`; when an error is signaled during it, control transfers to the first clause whose condition `type` matches the signaled condition, with `var` (optional) bound to the condition object, and the clause body's value becomes the value of the whole form. When no clause matches, the error propagates outward (an enclosing `handler-case` may still catch it). The clause type is any `typecase` specifier, including condition classes defined by [`define-condition`](define-condition.md) and the built-in hierarchy (`condition` > `serious-condition` > `error`, `warning`); an error signaled without a condition object (a plain `(error "...")` or a runtime failure inside a built-in) is caught as a `simple-error` whose `format-control` slot carries the message. The `:no-error` clause runs on normal completion with `var` bound to the (primary) value, outside the handler. Non-local exits (`return`/`return-from`) pass through uncaught, and an `unwind-protect` inside the expression runs its cleanup before the handler.

`handler-case` is supported on **every backend** except `--no-gc` (a compile error there). On the wasm-GC backends (Preview 1 and `--component`, including `wasmtime serve`) it compiles through the WebAssembly exception-handling proposal, so running a program that uses a catching form needs wasmtime 37+ with the proposal enabled: add `-W exceptions=y` to the usual `wasmtime run`/`wasmtime serve` flags. A program without catching forms is byte-identical to before and keeps its usual command line. Divergence: the WASM backends catch **signaled conditions only** — a runtime trap (a `(car 5)`-style type failure, integer division by zero) stays uncatchable there, while the interpreter and the JVM catch it as an error. Handlers are per thread of control, so concurrent `rontolisp:http-handler` requests do not interfere. To run a handler at the signal point *without* unwinding — e.g. to invoke a [`restart-case`](restart-case.md) restart — use [`handler-bind`](handler-bind.md).

```lisp
(handler-case (error "boom")
  (error (e) (list :caught (simple-condition-format-control e)))) ; => (:CAUGHT "boom")
```

Typed conditions dispatch through the class hierarchy, first matching clause wins:

```lisp
(define-condition low-fuel (warning) ((level :initarg :level :reader low-fuel-level)))
(handler-case (error 'low-fuel :level 5)
  (error (e) :error)
  (warning (w) (list :warned (low-fuel-level w)))) ; => (:WARNED 5)
```

```lisp
(handler-case (+ 1 2)
  (error (e) :err)
  (:no-error (v) (list :ok v))) ; => (:OK 3)
```
