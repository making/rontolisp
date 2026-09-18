# truncate

`(truncate x)`

Returns the integer closest to `x` whose absolute value is not greater than that of `x`, a flonum for a flonum argument and exact for an exact one.

```scheme
(truncate -2.7) ; => -2.0
(truncate 7/2) ; => 3
```
