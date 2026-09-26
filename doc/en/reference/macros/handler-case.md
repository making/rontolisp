# handler-case

`(handler-case expression (type ([var]) body...)... [(:no-error ([var]) body...)])`

Evaluates `expression`; when an error is signaled during it, control transfers to the first clause whose condition `type` matches the signaled condition, with `var` (optional) bound to the condition object, and the clause body's value becomes the value of the whole form. When no clause matches, the error propagates outward (an enclosing `handler-case` may still catch it). The clause type is any `typecase` specifier, including condition classes defined by [`define-condition`](define-condition.md) and the built-in hierarchy (`condition` > `serious-condition` > `error`, `warning`); an error signaled without a condition object is caught as the class its cause names, with the message in the condition's `format-control` slot (as the control that prints it verbatim, every `~` doubled): a plain `(error "...")` is a `simple-error`, while a failure inside a built-in carries its own type -- a bad `car`, a wrong argument type or an out-of-range index is a `type-error`, a zero divisor a `division-by-zero`, a call to an undefined function an `undefined-function`, a read of an unbound variable an `unbound-variable` (on the wasm-GC backends the reachable cases are the undefined-function one, caught as a `simple-error` there, and a wrong-type argument, a `type-error` there too -- see below), and a call whose keyword tail is malformed -- a keyword the operator does not accept, an odd tail, a non-keyword in keyword position -- a `program-error` on every backend (unless `:allow-other-keys t` admits the extra keys; the compiled backends also warn at compile time, since the call is known to fail). The `:no-error` clause runs on normal completion with `var` bound to the (primary) value, outside the handler. Non-local exits (`return`/`return-from`) pass through uncaught, and an `unwind-protect` inside the expression runs its cleanup before the handler.

`handler-case` is supported on **every backend** except `--no-gc` (a compile error there). On the wasm-GC backends (Preview 1 and `--component`, including `wasmtime serve`) it compiles through the WebAssembly exception-handling proposal. A program without catching forms is byte-identical to before and keeps its usual command line. Divergence: the WASM backends catch **signaled conditions only** — a runtime trap (integer division by zero, a list walk over a non-list such as `(nthcdr 1 5)`) stays uncatchable there, while the interpreter and the JVM catch it as an error. A wrong-type argument reaching an arithmetic or comparison operator, `car`/`cdr`, an array or `nthcdr` index, `numerator`/`denominator`, `random` or `complex` (`(+ 1 nil)`, `(< 1 "x")`, `(car 5)`, `(aref v nil)`) **is** signaled and caught on every backend, with the same message naming the operator and the type it requires — `(+ 1 nil)` reports `+: The value NIL is not of type NUMBER`, `(car 5)` reports `CAR: The value 5 is not of type LIST` — as a `type-error` answering `type-error-datum` and `type-error-expected-type`. So is an array subscript outside its dimension: `(aref (vector 1 2 3) 5)` reports `AREF: The value 5 is not of type (INTEGER 0 (3))` everywhere, its expected type the list `(INTEGER 0 (3))`. Handlers are per thread of control, so concurrent `rontolisp:http-handler` requests do not interfere. To run a handler at the signal point *without* unwinding — e.g. to invoke a [`restart-case`](restart-case.md) restart — use [`handler-bind`](handler-bind.md).

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
