# `vector-push-extend`'s default extension differs per backend, and inside the interpreter

Difficulty: Low

Found 2026-08-31 while doing `.todo/613` (a sized string specifier reads the array
DIMENSION, so the dimension a grown vector ends up with became observable through
`typep`). Growing a capacity-2 vector to five elements:

```lisp
(defvar *v* (make-array 2 :fill-pointer 0 :adjustable t))
(dotimes (i 5) (vector-push-extend i *v*))
(array-dimension *v* 0)
;; SBCL 2.2.9: 8   interpreter: 5   JVM: 5

(defvar *s* (make-array 2 :element-type 'character :fill-pointer 0 :adjustable t))
(dotimes (i 5) (vector-push-extend #\a *s*))
(array-dimension *s* 0)
;; SBCL 2.2.9: 8   interpreter: 8   JVM: 5
```

So the general vector and the CHARACTER vector disagree on the INTERPRETER
(grow-by-one vs doubling), and the compile paths grow by one for both -- the
compilers pass a literal `1` as the missing extension argument
(`JvmArrayCompiler.compileVectorPushExtend` / the wasm twin), while the
interpreter's character-vector path doubles.

CLHS leaves the default extension implementation-dependent, so no single answer
is required -- but a value that a program can OBSERVE (`array-dimension`, and
now `(typep v (list 'string (array-dimension v 0)))`) must not depend on the
backend, and a growth policy that is quadratic in the number of pushes is the
wrong one to standardize on. Pick DOUBLING (SBCL's, and what the interpreter
already does for a character vector), write it once so all four backends read
the same rule, and pin `array-dimension` after a growth run in
`LispEvaluatorTest` + `JvmLispCompilerTest` +
`WasmLispCompilerIntegrationTest` and a ci-spec case.

Note while measuring: the fill-pointer surface's cross-backend pin
(`fill-pointer-arrays-cross-backend`) never reads a dimension after a growth,
which is why this survived (`.kb/adjustable-arrays.md`).
