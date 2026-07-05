# check-type

`(check-type place typespec [string])`

Evaluates `place` and signals an error when the value is not of the given type; returns nil when it is. This is the lite version of Common Lisp's `check-type`: there is no restart system, so the error cannot be corrected interactively and the place is never re-stored.

Supported type specifiers are the `typecase` type names (`integer`, `float`, `number`, `rational`, `string`, `symbol`, `keyword`, `cons`, `list`, `null`, `atom`, `character`, `hash-table`, `boolean`) plus the compound forms `(or ...)`, `(and ...)`, `(not ...)`, `(member item...)`, `(eql object)`, `(satisfies function)`, and ranged numeric types like `(integer 0 9)` (`*` means unbounded and a `(bound)` list is exclusive). The optional string replaces the "of type ..." part of the error message.

```lisp
(let ((n 5)) (check-type n (integer 0 9)) n) ; => 5
```

A failing check aborts execution, so it is shown statically:

```console
(let ((n 12)) (check-type n (integer 0 9)))
; error: The value of n is 12, which is not of type (integer 0 9).
```
