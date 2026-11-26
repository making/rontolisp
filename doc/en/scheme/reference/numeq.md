# =

`(= z1 z2 z3 ...)`

Returns `#t` when all arguments are numerically equal, regardless of exactness: `(= 1 1.0)` is `#t`. Takes two or more arguments.

```scheme
(= 1 1.0) ; => #t
(= 1 1 2) ; => #f
```
