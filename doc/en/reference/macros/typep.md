# typep

`(typep object 'type-specifier)`

Tests whether `object` is of the given type. Lite: the type specifier must be a literal (quoted) type — the same set [`typecase`](typecase.md) supports (atomic names, registered classes, zero-parameter user [`deftype`](deftype.md) names, and the compound specifiers `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)`/ranged numerics/`(unsigned-byte n)`/`(signed-byte n)`); an unknown specifier matches nothing.

```lisp
(typep 5 '(unsigned-byte 8)) ; => T
```

```lisp
(typep 500 '(unsigned-byte 8)) ; => NIL
```
