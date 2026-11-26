# exact-integer-sqrt

`(exact-integer-sqrt k)`

Returns two values: the largest exact integer `s` with `s*s` not greater than `k`, and the remainder `k - s*s`. `k` must be an exact non-negative integer; anything else signals an error.

```scheme
(exact-integer-sqrt 17) ; => 4, 1
(exact-integer-sqrt 16) ; => 4, 0
```
