# Argument-type errors outside the numeric operators are unnamed and diverge

Difficulty: Medium

Todo 969 named the numeric operators' wrong-type reports (`compiler/OperandTypes`); every
other built-in's argument-type error still reports without the operator, and several differ
by backend (measured 2026-09-26 with `(defvar *n* nil)`):

- `(car 5)`: interpreter `car expects a cons cell, got: 5`, JVM `the value is not of the
  expected type`, wasm an uncatchable trap.
- `(nth *n* '(1 2))`, `(aref #(1 2) *n*)`: interpreter `The value NIL is not of type
  INTEGER`, JVM a raw `NullPointerException` text (`Cannot invoke "java.lang.Long.longValue()"`),
  wasm an uncatchable trap.
- `(random *n*)`: interpreter `random expects an integer or float limit, got: NIL`, JVM
  `The value NIL is not of type NUMBER`, wasm `... INTEGER`.
- `(numerator *n*)`: interpreter `numerator expects a rational, got: NIL`, JVM answers `NIL`
  (no error), wasm `The value NIL is not of type INTEGER`.
- `(lcm *n*)` / `(gcd *n*)` with one argument: the compile path lowers them to `abs`
  (`LispMacroExpander.expandReduction`), so the compiled backends report `ABS: ... NUMBER` and
  accept a float (`(lcm 1.5)` is `1.5`), where the interpreter signals `LCM: ... INTEGER`.
- `(setf (aref #d(1.0) 0) "x")` and `(complex #c(1 2) 3)` report unnamed, the latter as
  `NUMBER` where the part must be `REAL`.

Goal: each reports `OP: The value X is not of type T` with the CL type the operator requires,
identically on all four backends, as a catchable `type-error` where the backend types its
throws. Extend `OperandTypes` rather than adding per-backend texts; the funnels' attachment
points (the interpreter's built-in seam, the JVM wrappers, the wasm operator register) already
carry any named operator.
