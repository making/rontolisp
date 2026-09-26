# handler-case

`(handler-case expression (type ([var]) body...)... [(:no-error ([var]) body...)])`

Evaluates `expression`; when an error is signaled during it, control transfers to the first clause whose condition `type` matches the signaled condition, with `var` (optional) bound to the condition object, and the clause body's value becomes the value of the whole form. When no clause matches, the error propagates outward (an enclosing `handler-case` may still catch it). The clause type is any `typecase` specifier, including condition classes defined by [`define-condition`](define-condition.md) and the built-in hierarchy (`condition` > `serious-condition` > `error`, `warning`); an error signaled without a condition object is caught as the class its cause names, with the message in the condition's `format-control` slot: a plain `(error "...")` is a `simple-error`, while a failure inside a built-in carries its own type -- a bad `car`, a wrong argument type or an out-of-range index is a `type-error`, a zero divisor a `division-by-zero`, a call to an undefined function an `undefined-function`, a read of an unbound variable an `unbound-variable` (on the wasm-GC backends the reachable cases are the undefined-function one and a non-number operand reaching arithmetic, and both are caught as a `simple-error` there -- see the divergence below), and a call whose keyword tail is malformed -- a keyword the operator does not accept, an odd tail, a non-keyword in keyword position -- a `program-error` on every backend (unless `:allow-other-keys t` admits the extra keys; the compiled backends also warn at compile time, since the call is known to fail). The `:no-error` clause runs on normal completion with `var` bound to the (primary) value, outside the handler. Non-local exits (`return`/`return-from`) pass through uncaught, and an `unwind-protect` inside the expression runs its cleanup before the handler.

`handler-case` is supported on **every backend** except `--no-gc` (a compile error there). On the wasm-GC backends (Preview 1 and `--component`, including `wasmtime serve`) it compiles through the WebAssembly exception-handling proposal. A program without catching forms is byte-identical to before and keeps its usual command line. Divergence: the WASM backends catch **signaled conditions only** — a runtime trap (a `(car 5)`-style type failure, integer division by zero) stays uncatchable there, while the interpreter and the JVM catch it as an error. A non-number operand reaching an arithmetic or comparison operator (`(+ 1 nil)`, `(< 1 "x")`) **is** signaled and caught on every backend, with the same message naming the operator and the type it accepts — `(+ 1 nil)` reports `+: The value NIL is not of type NUMBER` — as a `type-error` answering `type-error-datum` and `type-error-expected-type` on the interpreter and the JVM, as a `simple-error` on the WASM pair. Handlers are per thread of control, so concurrent `rontolisp:http-handler` requests do not interfere. To run a handler at the signal point *without* unwinding — e.g. to invoke a [`restart-case`](restart-case.md) restart — use [`handler-bind`](handler-bind.md).

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

An error a built-in raises dispatches on its class -- what a test framework's `(signals form 'type-error)` asserts. On the wasm-GC backends this particular failure traps instead (see the divergence above), so it is caught on the interpreter and the JVM:

```lisp
(handler-case (car 1)
  (type-error (e) :type-error)
  (error (e) :plain)) ; => :TYPE-ERROR
```

A malformed call is a `program-error` everywhere, with the same message -- the test suite shape `(signals-error (remove 'a nil :bogus t) program-error)`:

```lisp
(handler-case (remove 1 '(1 2 3) :bogus 4)
  (program-error (e) (princ-to-string e))
  (error (e) :plain)) ; => "REMOVE expects keyword arguments :TEST/:TEST-NOT/:KEY/:START/:END/:COUNT/:FROM-END, got: :BOGUS"
```
