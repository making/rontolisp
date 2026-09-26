# An out-of-range array subscript reports one type-error on every backend

Difficulty: High

`Unhandled condition: <report>` is meant to be the same line on all four backends
(`.kb/error-handling.md`, "An uncaught condition reports ONE line"). An `aref` whose subscript is
out of range is not, and neither is the caught condition:

```lisp
(defun f (v n)
  (declare (type (simple-array double-float (*)) v) (fixnum n))
  (let ((s 0d0)) (declare (double-float s))
    (dotimes (i n) (setf s (+ s (aref v i)))) s))
(f (make-array 3 :element-type 'double-float :initial-element 1d0) 5)
```

Measured 2026-09-26:

| | interpreter | JVM | wasm-GC (EH mode too) |
| --- | --- | --- | --- |
| the typed loop above, uncaught | `aref: index out of bounds` | `Index 5 out of bounds for length 5` (the raw `DALOAD`; the index and length count the two header slots) | trap `out of bounds array access` |
| `(aref (vector 1 2 3) 5)`, uncaught | `aref: index out of bounds` | `Index 5 out of bounds for length 3` | trap |
| same, caught | `type-error`, `aref: index out of bounds` | `type-error`, `index out of bounds` (the pad's `ClosRegistry.INDEX_OUT_OF_BOUNDS_MESSAGE`) | uncatchable trap |
| `(aref "abc" 5)`, caught | `simple-error`, `AREF: index 5 out of bounds for string of length 3` | `type-error`, `index out of bounds` | trap |
| `(aref dv -1)`, `dv` a 3-element `double-float` vector | `type-error` | answers `3.0` -- the header's dimension | trap |
| `(aref m 0 2)`, `m` a 2x2 `double-float` array | answers `m[1][0]` | answers `m[1][0]` (typed loop and boxed path alike) | answers `m[1][0]` |
| `(aref m 0 5)`, `m` a general 2x2 array | `type-error` (past the total size only) | `type-error` | trap |

The JVM's uncaught line prints the raw exception because `JvmUncaughtHandler` reports
`getMessage()`; the pad's substitution never reaches it, and the typed loops
(`.kb/jvm-typed-loops.md`) deliberately rethrow the same `ArrayIndexOutOfBoundsException` the boxed
path would.

## The CL text

A subscript outside `[0, dimension)` is a `type-error` whose datum is the subscript and whose
expected type is `(INTEGER 0 (d))` -- SBCL's `invalid-array-index-error` is a `type-error` with
exactly those slots. In the report shape every wrong-type argument uses
(`compiler/OperandTypes`, "A wrong-type argument names its operator"):

```
AREF: The value 3 is not of type (INTEGER 0 (3))
```

`(setf aref)` reports as `(SETF AREF)`, `svref` as `AREF`, `row-major-aref` as itself, and the axis
that failed decides `d` for a rank-2 array. `char`/`schar` over a string are `.todo/186`; decide
together whether they share this text.

## Goal

The report above, the `type-error` class and its two slots, byte-identical on the interpreter, the
JVM and both wasm-GC backends (EH mode), for reads and stores through every array representation
(general, packed float/int, bf16/f16 where they exist, strings via `aref`) and through the JVM typed
loops. Pin one uncaught program in `ci-spec.yaml`'s `standalone:` section and the caught matrix in
the corpus.

Constraints to measure, not assume:

- wasm-GC: outside EH mode the bare trap is kept (byte-identical module, as every other landing).
  In EH mode each checked access needs the index against the length -- decide whether the check
  replaces the engine's own bounds check in cost (measure a hot `aref` loop under wasmtime; a
  struct accessor's separate type test cost 78% on a write loop, `.kb/defstruct.md`).
- JVM typed loops: a range check the JIT can hoist (a `CmpU` against a loop-invariant length) is
  free after loop predication; verify on a `dotimes` over `double[]`, and keep the typed path's
  "same error shape as the boxed path" invariant.
- Per-axis bounds: today only the row-major total is checked, so a too-large column wraps into
  the next row (`(aref m 0 2)` above, on all four). CL requires each subscript in its
  own dimension; every path must check per axis, or the four backends still disagree.

Read first: `.kb/error-handling.md` ("A built-in error carries its CONDITION CLASS", "A wrong-type
argument names its operator"), `.kb/jvm-typed-loops.md`, `.kb/cons-access-runtime.md` (the
`br_on_cast_fail` checked read precedent).
