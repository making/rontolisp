# setf of (apply #'aref ...) fails to compile on the JVM

Difficulty: Low

## Symptom

Full `./mvnw test` on develop `ab532ff7c` fails deterministically (reproduced twice):

```
JvmFfiInteropCompilerTest.setfOfApplyArefIsTheRuntimeRankPlace:269->compileAndRun:51
  » UnsupportedOperation Cannot compile: ARRAY-ROW-MAJOR-INDEX
```

```lisp
(let ((a (make-array '(2 3) :initial-element 0)))
  (setf (apply #'aref a (list 1 2)) 42)
  (print (aref a 1 2)))
```

## Cause (suspected)

Commit `d18030909` added `#'aref` / `#'array-row-major-index` to
`BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`. The `(setf (apply #'aref ...))`
expansion refers to `#'array-row-major-index` after the user source was scanned, so the
gate never sees the reference and the wrapper is not emitted.

## Goal

The test passes again without dropping the rank/axis checks the commit added. Check the
same shape on wasm, and grep other expander paths that introduce a gated `#'name` the
source scan cannot see.
