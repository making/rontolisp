# The other list consumers over a non-list answer wrong values or unnamed errors

Difficulty: Medium

984 named `length`, `last`, `rplaca`/`rplacd`, the `map*` family and `loop`'s `for-in`
(`.kb/error-handling.md`, "A wrong-type argument names its operator"). Measured 2026-09-26 with
`*five*` = 5:

| Form | Interpreter | JVM | wasm (EH) |
| --- | --- | --- | --- |
| `(reverse *five*)` | `NIL` | pad's generic text | `NIL` |
| `(append *five* nil)` | simple-error `append expects a list` | pad's generic text | trap |
| `(member 1 *five*)`, `(assoc 1 *five*)` | `NIL` | `NIL` | `NIL` |
| `(list-length *five*)` | type-error, report prints the instance | same | same |
| `(length '(1 2 . 3))` | `2` | `2` | `2` |
| `(mapcar #'1+ '(1 2 . 3))` | `(2 3)` | error | trap |

CL: each is a `type-error` (`LIST`; `length` of an improper list is not a proper sequence).
Goal: `OP: The value X is not of type T` on all four backends, through `%check-list` /
`OperandTypes` as 984 did. Also: `(mapcon f l)` whose `f` answers a non-list keeps the
message-only `MAPCON: argument is not a list`, and a multi-list `(funcall #'mapcar f l 5)`
reports `CAR` (the value wrapper's walk), not `MAPCAR`.
