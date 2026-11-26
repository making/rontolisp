# complexp

`(complexp object)`

Returns `t` if `object` is a complex number, otherwise `nil`. A real number -- even one with a zero imaginary part, which [`complex`](complex.md) demotes -- is not complex. Works on all four backends.

```lisp
(complexp #c(1 2)) ; => T
```

```lisp
(complexp 1) ; => NIL
```
