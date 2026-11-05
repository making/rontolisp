# check-type

`(check-type place typespec [string])`

Evaluates `place` and signals a `simple-type-error` when the value is not of the given type; returns nil when it is. The condition is a `type-error`: `type-error-datum` is the value and `type-error-expected-type` the specifier. It is also a `simple-condition`, so a `simple-condition` clause catches it and `simple-condition-format-control` reads its message. This is the lite version of Common Lisp's `check-type`: it establishes no `store-value` restart, so the error cannot be corrected interactively and the place is never re-stored.

A type specifier is whatever [`typep`](typep.md) takes: a standard type name, a class, a condition, a structure, a `deftype`, and the compound forms `(or ...)`, `(and ...)`, `(not ...)`, `(member item...)`, `(eql object)`, `(satisfies function)`, `(mod n)` and ranged numeric types like `(integer 0 9)` (`*` means unbounded and a `(bound)` list is exclusive), each of them holding any other. A specifier nothing defines is not checked, and neither is `restart`: the place is evaluated and the form returns nil. `generic-function`, `structure-class`, `logical-pathname` and `extended-char` accept what `typep` does not call one here, the function, class, pathname or character standing for it. The optional string replaces the "of type ..." part of the error message.

```lisp
(let ((n 5)) (check-type n (integer 0 9)) n) ; => 5
(handler-case (let ((n 12)) (check-type n (integer 0 9)))
  (type-error (c) (list (type-error-datum c) (type-error-expected-type c))))
; => (12 (INTEGER 0 9))
```

An uncaught failing check aborts execution, so it is shown statically:

```console
(let ((n 12)) (check-type n (integer 0 9)))
; error: The value of n is 12, which is not of type (integer 0 9).
```
