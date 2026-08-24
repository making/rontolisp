# Rank-2 `:initial-contents` silently zero-fills a packed float array

Difficulty: Low

Filed 2026-08-24, found incidentally while spiking `.todo/501` (unrelated to it).

## The bug

```lisp
(print (vec:matvec (make-array '(2 2) :element-type 'double-float
                               :initial-contents '((1.0 2.0) (3.0 4.0)))
                   #d(1.0 1.0)))
```

Interpreter answers `#d(0.0 0.0)`. The correct answer is `#d(3.0 7.0)`, which the same
program gives when the matrix is filled with `setf aref` instead:

```lisp
(let ((m (make-array '(2 2) :element-type 'double-float)))
  (setf (aref m 0 0) 1.0 (aref m 0 1) 2.0 (aref m 1 0) 3.0 (aref m 1 1) 4.0)
  (print (vec:matvec m #d(1.0 1.0))))   ; => #d(3.0 7.0), correct
```

So `:initial-contents` is being dropped for a rank >= 2 array of a packed element type,
leaving the zero-initialized block -- and nothing says so. A silent wrong answer out of a
numeric kernel is the worst shape a bug can have here: the program runs, prints a
plausible array, and every downstream number is wrong.

The compiled backends REFUSE the same form:

```
error: kernel.lisp:13:16: make-array :initial-contents supports rank-1 arrays only on
the compiled backends
```

which is how the divergence went unnoticed: the compile paths tell you, the interpreter
does not.

## What to decide

Refusing on the interpreter too would restore cross-backend agreement in one line, and it
is strictly better than the current silence -- but a general (non-packed) rank-2
`:initial-contents` presumably works on the interpreter today, so check that first: if it
does, the honest fix is to make the PACKED path honour nested `:initial-contents` (the
data is contiguous row-major behind a `[rank, dims..., data...]` header, so filling it
from nested lists is a walk), and to lift the compiled-backend refusal with it rather than
spread it.

Either way the two must end up agreeing, per CLAUDE.md's cross-backend rule.

## Acceptance

A failing test first (`LispEvaluatorTest`, the rank-2 packed `:initial-contents` case
above), then the fix; the same expectation in `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest` whichever way it is settled, and a `ci-spec.yaml` case
if `:initial-contents` starts working rather than starting to refuse.
