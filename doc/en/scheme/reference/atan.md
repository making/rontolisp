# atan

`(atan z)` `(atan y x)`

Returns the arctangent of `z` in radians or, with two arguments, the angle of the point (`x`, `y`) in (-π, π], taking the signs of both into account. `(atan 0)`, and `(atan 0 x)` for a positive exact `x`, are the exact `0`.

```scheme
(atan 1) ; => 0.7853981633974483
(atan 1 0) ; => 1.5707963267948966
(atan 0 1) ; => 0
```
