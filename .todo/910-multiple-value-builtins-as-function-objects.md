# The other multiple-value built-ins as function objects answer one value

Difficulty: Medium

Found by `.todo/902` (2026-09-19), which fixed the floor family only. Every backend, the
interpreter included:

| form | rontolisp | SBCL |
|---|---|---|
| `(multiple-value-list (funcall #'gethash 1 h))` (key present) | `(ONE)` | `(ONE T)` |
| `(multiple-value-list (let ((g #'gethash)) (funcall g 1 h)))` | `(ONE)` | `(ONE T)` |
| `(multiple-value-list (let ((g #'find-symbol)) (funcall g "CAR" "CL")))` | `(CAR)` | `(CAR :EXTERNAL)` |

Same class for `#'intern`, `#'subtypep`, `#'read-from-string`, `#'array-displacement`: the
syntactic producers publish only in call position. The floor family's mechanism
(`.kb/multiple-values.md`, "The floor family as a function object") generalizes: the
interpreter's `LispFunction` publishes and is `passesValues`, `settleWrapperLambdas` runs the
PUBLISH walk over the wrapper's tail (widen `isFloorFamilyName` to the producer set), and
`settleTail` treats a literal-designator funcall of the name as passing values. Check each
wrapper's shape first (`#'gethash` needs its optional default; `#'find-symbol` is
`findSymbolWrapper`), and measure the cost of a publishing `#'gethash` in a `maphash`-free
hot loop before choosing.

## Test plan

- ci-spec case with the rows above plus one per remaining name, SBCL's answers.
