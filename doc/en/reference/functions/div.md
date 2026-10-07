# /

`(/ number &rest numbers)`

Divides the first argument by the rest, left to right; with one argument returns its reciprocal. Integer division is exact: when the result is not a whole number it is returned as a reduced ratio rather than truncated, and an evenly dividing pair yields an integer. If any argument is a float the result is a float. Dividing by an exact zero signals `division-by-zero`; a division with a float operand follows IEEE 754 instead, so `(/ 1.5 0)` and `(/ 1 0.0)` are infinities and `(/ 0.0 0)` is NaN.

With a complex argument the division runs one pair at a time and each pair is SBCL's: a complex over a real divides each part by it (`(/ #c(1d0 2d0) 3d0)` costs one rounding per part, and `(/ #c(1d0 2d0) 0d0)` is `#C(Infinity Infinity)`, what the real division answers), and a quotient over a complex folds on whichever part of the divisor is larger, so nothing intermediate overflows where the operands themselves are representable: `(/ #c(1d200 1d200) #c(1d200 1d200))` is `#C(1.0 0.0)`. An exact operand turns into a float only where it meets one, so exact parts stay exact rationals, an exact step stays exact whatever float follows, and the signed zeros are SBCL's: `(/ 1 #c(1.0 0.0))` is `#C(1.0 -0.0)`. An exact zero divisor signals the same division-by-zero error a real one does.

```lisp
(/ 1 2) ; => 1/2
```

```lisp
(/ 10 2) ; => 5
```
