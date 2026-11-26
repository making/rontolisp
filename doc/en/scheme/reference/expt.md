# expt

`(expt z1 z2)`

Returns `z1` raised to the power `z2`. An exact base with an exact integer exponent gives an exact result, including a ratio for a negative exponent; `(expt 0 0)` is `1`. A non-integer exponent gives a flonum, even when the root is exact: `(expt 4 1/2)` is `2.0`. A complex base or a negative base with a non-integer exponent answers the complex power.

```scheme
(expt 2 10) ; => 1024
(expt 2 -2) ; => 1/4
(expt 2.0 0.5) ; => 1.4142135623730951
```
