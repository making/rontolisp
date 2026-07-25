# integer-length

`(integer-length integer)`

Number of bits needed to represent the two's-complement magnitude of `integer`, excluding the sign. For a non-negative `integer` this is the position of the highest set bit plus one; for a negative `integer` it is the length of the ones' complement, so `(integer-length -1)` is `0` and `(integer-length -5)` is `3`. `integer` may be any magnitude on every backend.

```lisp
(integer-length 255) ; => 8
```

```lisp
(integer-length -5) ; => 3
```
