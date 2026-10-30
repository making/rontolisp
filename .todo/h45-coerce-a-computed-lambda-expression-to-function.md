# h45. `(coerce <computed lambda expression> 'function)` signals

Difficulty: Medium

Found 2026-10-30 probing `lisp-unit`. Every test errors, because `lisp-unit.lisp:707` runs a
test body with `(funcall (coerce `(lambda () ,@code) 'function))`:

```
SYMBOL-FUNCTION expects a symbol, got (LAMBDA NIL (LISP-UNIT:ASSERT-EQUAL 2 (+ 1 1)))
```

A LITERAL `(coerce '(lambda (x) ...) 'function)` works, and so does `(compile nil ...)` on a
computed one. Only the computed `coerce` fails, on the interpreter. CLHS: coerce of a
lambda expression is `(compile nil ...)`'s result (or the evaluated `function` form).
Route the computed case to the same place as `compile nil`. Check what the compile paths
do (`.kb/eval-runtime.md`). Add the failing case first.
