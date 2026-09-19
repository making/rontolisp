# The floor family as a function object: no divisor, no second value

Difficulty: Medium

Found by `.todo/895` (2026-09-19). Every backend, the interpreter included:

| form | rontolisp | SBCL |
|---|---|---|
| `(funcall #'floor 7 2)` | error `FLOOR expects 1 arguments, got 2` (wasm traps) | `3` |
| `(mapcar #'truncate '(7 9) '(2 4))` | same error | `(3 2)` |
| `(multiple-value-list (funcall #'floor 7.5))` | `(7)` | `(7 0.5)` |
| `(multiple-value-list (let ((g #'floor)) (funcall g 7.5)))` | `(7)` | `(7 0.5)` |

The built-in `floor`/`ceiling`/`round`/`truncate` (and `ffloor`...) take one argument as a
FUNCTION: the two-argument form only exists as syntax (`expandFloorFamilyDivisor`), and the
secondary value only through the syntactic lowering (`.kb/multiple-values.md`). A function
object needs an optional divisor on every backend (the `BuiltinFunctionWrappers` entry and the
interpreter's `LispFunction`), and -- if the second value should travel -- a publish from the
built-in itself, which the interpreter's value-count register and the wrapper's `CLEAR` tail
(`settleWrapperLambdas`) currently both drop. Measure the cost of a publishing wrapper before
choosing; `(funcall #'floor ...)` over a literal designator is compiled inline and classified
by name, so it must agree with the variable case.

## Test plan

- ci-spec case with the rows above, SBCL's answers.
