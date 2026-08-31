# make-array refuses a rank-0 array, so as-array of a scalar has nowhere to go

Difficulty: Medium

Found enabling array-operations (`.kb/asdf.md`). `(make-array nil ...)` -- a
0-dimensional array, one element reached with no subscripts -- errors on every
backend:

```lisp
(make-array nil :initial-element 5)     ; => error: MAKE-ARRAY expects at least one dimension
(array-rank (make-array nil))           ; SBCL: 0
(array-dimensions (make-array nil))     ; SBCL: NIL
(array-total-size (make-array nil))     ; SBCL: 1
(aref (make-array nil :initial-element 5))          ; SBCL: 5
(setf (aref (make-array nil) ) 7)                   ; SBCL: 7
(type-of (make-array nil :initial-element 5))       ; SBCL: (SIMPLE-ARRAY T NIL)
```

`Environment.parseDimensions` raises it explicitly ("expects at least one
dimension") and `LispArray`'s javadoc states rank >= 1 as the model, matching the
compiled backends -- so this is one decision taken in four places, not a bug in
one.

Why it matters beyond conformance: a rank-0 array is CL's box for "a scalar seen
as an array", and generic array libraries lean on it. array-operations'
`as-array` default method IS `(make-array nil :initial-element object)`, so
`(aops:dims 1)` fails, and with it the 0-dimensional-object arm of
`stack-rows`/`stack-cols` -- 8 of the 19 assertions its own clunit2 suite fails
here (200/219 against SBCL 2.2.9's 219/219 on the same sources).

Scope: `LispArray` (dimensions of length 0, total size 1, the row-major fold over
zero subscripts), `Environment.parseDimensions` + the `aref`/`array-rank`/
`array-dimensions`/`array-total-size`/`row-major-aref` built-ins, the JVM and both
WASM array runtimes, the `#0A` printer and reader, and `type-of`/`typep` (see
`.todo/604`, which shares the type-specifier work). Behavior must be identical on
all four backends: add a ci-spec case and rows in `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`, then re-run
array-operations' suite and move the number in `.kb/asdf.md`.
