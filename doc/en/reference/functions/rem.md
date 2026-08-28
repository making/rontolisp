# rem

`(rem number divisor)`

Returns the remainder of `number` divided by `divisor` using truncated division, so the result always takes the sign of the dividend. It is the companion of `truncate`. Use `mod` instead when you want the result to follow the sign of the divisor.

```lisp
(rem 13 4) ; => 1
```

```lisp
(rem -13 4) ; => -1
```

```lisp
(rem 7/2 3) ; => 1/2
```
