# The floor family's second value is lost in a lambda's tail

Difficulty: Medium

Found by `.todo/888` (2026-09-19). Every backend, the interpreter included:

| form | rontolisp | SBCL |
|---|---|---|
| `(multiple-value-list (funcall (lambda () (floor 7 2))))` | `(3)` | `(3 1)` |
| `(multiple-value-list (funcall (lambda () (floor 7.5))))` | `(7)` | `(7 0.5)` |
| `(multiple-value-list (funcall (lambda (x) (truncate x)) 7.5))` | `(7)` | `(7 0.5)` |

A `defun`'s tail keeps both values (`(defun g () (floor 7 2))` answers `(3 1)`), as do
`let`/`progn`/`if` tails and `(funcall (lambda () (values 1 2)))`. So the lambda body does not
get the multiple-value lowering of a floor-family tail (`LispMacroExpander.lowerMvProducer`)
that a defun body gets. Find where the defun tail is marked and why a lambda tail is not.

## Test plan

- `ci-spec.yaml` case with the rows above plus `mapcar`/`funcall` of a named lambda.
- Check the other multiple-value producers the lowering covers, not only `floor`.
