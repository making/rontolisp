# realp

`(realp object)`

Returns `t` if `object` is a real number -- an integer, a ratio, or a float -- otherwise `nil`. A complex number is not real, even one whose imaginary part is zero. Works on all four backends.

```lisp
(realp 1/2) ; => T
```

```lisp
(realp #c(1 2)) ; => NIL
```
