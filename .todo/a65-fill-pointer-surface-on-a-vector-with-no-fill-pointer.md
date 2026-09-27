# The fill-pointer surface on a vector with no fill pointer

Difficulty: Medium

Measured 2026-09-27 with `(fill-pointer "abc")`, `(fill-pointer (vector 1 2))`,
`(vector-pop (vector 1 2))`, `(vector-push 1 (vector 1 2))`, `(vector-push-extend #\a "abc")`,
`(setf (fill-pointer "abc") 0)` under a `handler-case`:

- Interpreter: a `simple-error`, texts differing by operator and representation --
  `FILL-POINTER: string has no fill pointer`, `FILL-POINTER: array has no fill pointer`,
  `vector-pop: vector has no fill pointer`, `vector-push: vector has no fill pointer`,
  `VECTOR-PUSH-EXTEND: string has no fill pointer` (`Environment`, `LispArray.requireVectorWithFillPointer`).
- JVM: the general vector a lowercase `RuntimeException` (`fill-pointer: array has no fill
  pointer`, `JvmArrayRuntimeBuilder`'s `_fillPointer`/`_vectorPop`/...), a string the datum-less
  `ClassCastException` type-error (`the value is not of the expected type`; a string passes
  `_ckArr` and fails the helper's cast).
- wasm-GC: a trap (`emitRequireFillPointer`, the cell cast of a string), EH mode included.

SBCL signals a `type-error` whose expected type is `(AND VECTOR (NOT SIMPLE-ARRAY))`. A
packed array handed the same operators is a third text (`not applicable to a packed float
array`) and belongs to the same decision.

Plan: decide the condition (a `type-error` with that compound type, named by the operator
as the wrong-type rows are -- `.kb/error-handling.md`, "A sequence, array or hash-table
operand of the wrong kind", whose "Not covered" bullet this is) and make the four backends
byte-identical; `vector-pop` of an empty vector and a fill pointer set out of range are the
neighbouring refusals to check at the same time.
