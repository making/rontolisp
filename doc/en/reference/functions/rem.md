# rem

`(rem number divisor)`

Returns the remainder of `number` divided by `divisor` using truncated division, so a nonzero result always takes the sign of the dividend. It is the companion of `truncate`. Use `mod` instead when you want the result to follow the sign of the divisor.

A **zero** float result is `0.0`, and `-0.0` only when the dividend is `-0.0` and the divisor is positive. `rem` is the second value of `truncate` -- `number - divisor*quotient` for a quotient that is an exact integer -- so the sign of a zero falls out of that subtraction instead of being copied from the dividend.

The float result is the **exact** remainder at every magnitude: `number - divisor*quotient` is the value of that expression in exact arithmetic, not its floating-point evaluation, so a dividend past 2^53 still gets its true remainder. An **infinite** divisor leaves the quotient at zero, so the result is the dividend. A **zero** divisor gives `NaN`, the same non-trapping policy `(/ 1.0 0.0)` follows.

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

```lisp
(rem 1d18 7.0) ; => 1.0
```
