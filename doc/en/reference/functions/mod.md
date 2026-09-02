# mod

`(mod number divisor)`

Returns the remainder of `number` divided by `divisor` using floored division, so a nonzero result always takes the sign of the divisor. This makes it the companion of `floor`. Use `rem` instead when you want the result to follow the sign of the dividend.

A **zero** float result is `0.0`, and `-0.0` only when the dividend is `-0.0` and the divisor is positive. `mod` is the second value of `floor` -- `number - divisor*quotient` for a quotient that is an exact integer -- so the sign of a zero falls out of that subtraction rather than following the divisor.

The float result is the **exact** remainder at every magnitude: `number - divisor*quotient` is the value of that expression in exact arithmetic, not its floating-point evaluation, so a dividend past 2^53 still gets its true remainder. An **infinite** divisor leaves the quotient at zero, so the result is the dividend -- or the divisor's infinity when the two signs differ, which is what the floored quotient of -1 gives. A **zero** divisor gives `NaN`, the same non-trapping policy `(/ 1.0 0.0)` follows.

```lisp
(mod 10 3) ; => 1
```

```lisp
(mod -13 4) ; => 3
```

```lisp
(mod 7/2 3) ; => 1/2
```

```lisp
(mod -4.0 2.0) ; => 0.0
```

```lisp
(mod -0.0 2.0) ; => -0.0
```

```lisp
(mod 1d18 7.0) ; => 1.0
```
