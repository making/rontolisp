# packagep

`(packagep object)`

`t` when `object` is a package designator -- a string, symbol, or keyword --
naming a registered package, `nil` otherwise. Never signals: a non-designator
answers `nil` instead of a type error.

```lisp
(list (packagep :cl) (packagep "CL-USER") (packagep :no-such-doc-pkg) (packagep 42)) ; => (T T NIL NIL)
```
