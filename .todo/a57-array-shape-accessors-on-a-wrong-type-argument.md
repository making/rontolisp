# Array-shape accessors on a wrong-type argument

Difficulty: Medium

The rest of the array family `.kb/error-handling.md` ("A sequence, array or hash-table operand of
the wrong kind") left out, measured 2026-09-27 with `(fill-pointer 5)` / `(vector-pop 5)`:

- Interpreter: the unnamed `The value 5 is not of type ARRAY` type-error (`Environment.requireArray`,
  no table row).
- JVM: a datum-less `ClassCastException` type-error, `the value is not of the expected type` (a
  `java:` program's host `ArrayList` already gets the interpreter's report through `_jckarr`).
- wasm-GC: a trap, EH mode included.

Operators: `fill-pointer`, `(setf fill-pointer)`, `vector-push`, `vector-push-extend`, `vector-pop`,
`array-element-type`, `adjustable-array-p`, `array-has-fill-pointer-p`, `array-displacement`,
`adjust-array` (and `#'` of each).

Plan: rows in `OperandTypes.ARRAY_OPERATORS` (named, funnel-typed); the JVM helpers behind the
`emitHostArrayGuard` sites test before their cast (`JvmArrayRuntimeBuilder.emitArrayCheck`) and
are invoked under the operator's wrapper; wasm in EH mode checks through
`_arr_check_rank(arr, ANY_RANK | id << 8)` at each site (`WasmArrayCompiler.emitArrayCheck`). Pin
the rows in `WrongTypeArgumentFixture` and the ci-spec case (mind the corpus class's constant-pool
tripwire, `JvmClassShakerCorpusTest`, 51,893 of 52,000 on 2026-09-27).
