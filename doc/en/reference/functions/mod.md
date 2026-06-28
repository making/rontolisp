# mod

`(mod number divisor)`

Returns the remainder of `number` divided by `divisor` using floored division, so the result always takes the sign of the divisor. This makes it the companion of `floor`. Use `rem` instead when you want the result to follow the sign of the dividend.

```lisp
(mod 10 3) ; => 1
```

```lisp
(mod -13 4) ; => 3
```
