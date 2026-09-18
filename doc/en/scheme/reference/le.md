# <=

`(<= x1 x2 x3 ...)`

Returns `#t` when the arguments are non-decreasing. Takes two or more real numbers.

```scheme
(<= 1 1 2) ; => #t
(<= 2 1) ; => #f
```
