# List consumers over a non-list answer wrong values or unnamed errors

Difficulty: Medium

980 named the `nthcdr` walk and `endp`/`dolist`. Other list consumers, measured 2026-09-26 with
`*five*` = 5:

| Form | Interpreter | JVM | wasm (EH) |
| --- | --- | --- | --- |
| `(length *five*)` | `0` | `0` | `0` |
| `(last *five*)` | `NIL` | `5` | `5` |
| `(rplaca *five* 0)` | type-error `rplaca expects a cons cell`, no datum | pad's generic text | trap |
| `(mapcar #'1+ *five*)` | simple-error `MAPCAR: argument is not a list` | same text | trap |
| `(loop for x in *five* collect x)` | `CAR: ... LIST` | `CAR: ... LIST` | `CAR: ... LIST` |

CL: `length` of a non-sequence is a `type-error` (`SEQUENCE`), `last`/`mapcar`/`loop ... in` of a
non-list a `type-error` (`LIST`), `rplaca`/`rplacd` of a non-cons a `type-error` (`CONS`). Goal:
`OP: The value 5 is not of type T` on all four backends, `loop`'s under `ENDP` as `dolist`'s is.
`length` answering 0 and `last` diverging are wrong values, not only wrong messages.
