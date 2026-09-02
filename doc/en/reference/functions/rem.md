# rem

`(rem number divisor)`

Returns the remainder of `number` divided by `divisor` using truncated division, so a nonzero result always takes the sign of the dividend. It is the companion of `truncate`. Use `mod` instead when you want the result to follow the sign of the divisor.

A **zero** float result is `0.0`, and `-0.0` only when the dividend is `-0.0` and the divisor is positive. `rem` is the second value of `truncate` -- `number - divisor*quotient` for a quotient that is an exact integer -- so the sign of a zero falls out of that subtraction instead of being copied from the dividend.

```lisp
(rem 13 4) ; => 1
```

```lisp
(rem -13 4) ; => -1
```

```lisp
(rem 7/2 3) ; => 1/2
```

```lisp
(rem -4.0 2.0) ; => 0.0
```

```lisp
(rem -0.0 2.0) ; => -0.0
```
