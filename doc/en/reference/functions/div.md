# /

`(/ number &rest numbers)`

Divides the first argument by the rest, left to right; with one argument returns its reciprocal. Integer division is exact: when the result is not a whole number it is returned as a reduced ratio rather than truncated, and an evenly dividing pair yields an integer. If any argument is a float the result is a float. Dividing by an exact zero signals `division-by-zero`; a division with a float operand follows IEEE 754 instead, so `(/ 1.5 0)` and `(/ 1 0.0)` are infinities and `(/ 0.0 0)` is NaN.

A float complex quotient is computed by Smith's form -- the division folds on whichever part of the divisor is larger -- so a real divisor divides each part on its own (`(/ #c(1d0 2d0) 3d0)` costs one rounding per part, not three) and nothing intermediate overflows where the operands themselves are representable: `(/ #c(1d200 1d200) #c(1d200 1d200))` is `#C(1.0 0.0)`. Exact complex parts stay exact rationals, and an exact zero divisor signals the same division-by-zero error a real one does.

```lisp
(/ 1 2) ; => 1/2
```

```lisp
(/ 10 2) ; => 5
```
