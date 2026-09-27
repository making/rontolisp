# Sequence and accessor built-ins on a wrong-type argument

Difficulty: Medium

Measured 2026-09-26, interpreter vs `-o X.class`, a program WITHOUT `java:`, `x` = `5`
(a host `ArrayList` answers the same rows since the host-collection guards landed):

- Silent answers in the interpreter, where CL signals a `type-error`: `(every #'numberp x)`
  `T`, `(some #'numberp x)` `NIL`, `(sort x #'<)` `NIL` (compiled answers `5`),
  `(stable-sort x #'<)`, `(remove-duplicates x)`, `(substitute 2 1 x)`, `(find 1 x)`,
  `(position 1 x)` all `NIL` on both; `(coerce x 'vector)` answers `5` on both.
- Different refusals: `subseq` / `copy-seq` `SUBSEQ expects a string, list, or vector,
  got: 5` vs `the value is not of the expected type` (a `ClassCastException`); `fill`
  `FILL: expected a sequence, got: 5`, `concatenate` `not a sequence: 5`, `replace`
  `REPLACE: expected a sequence, got: 5` vs `LENGTH: The value 5 is not of type SEQUENCE`;
  `(aref x 0)` / `(gethash 1 x)` `AREF expects an array` / `GETHASH expects a hash table`
  (simple-errors) vs a `ClassCastException` type-error with no datum.
- `(apply #'aref v '(0))` on a non-array names `ARRAY-DIMENSIONS`, not `AREF`, on the
  compiled path: the `#'aref` wrapper (`BuiltinFunctionWrappers.arefFoldBody`) reads the
  dimensions first.

Plan:
- Decide the one refusal per operator (CL: a `type-error` whose datum is the argument and
  expected type `sequence` / `array` / `hash-table`) and make the interpreter, the JVM and
  both WASM backends signal it; the silent interpreter answers are the first to go.
- The JVM guards `_jckarr` / `_jcktab` (`.kb/java-interop.md`, "What a host object is")
  are the place a `java:` program already refuses a host collection; a wrong-type check
  for every program is the same call site with a cheaper test.
- Pin the rows on every backend with one shared program.
