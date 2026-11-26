# fround

`(fround number &optional divisor)`

Like [`round`](round.md), rounds `number` (or `number/divisor` when a divisor is given) to the nearest integer, ties to even, but the primary value is always a float -- CLHS defines `fround` as `round` with a FLOAT quotient. The second value (the remainder) is the same one `round` answers.

```lisp
(fround 7 2) ; => 4.0
```

```lisp
(multiple-value-bind (q r) (fround 7 2)
  (list q r)) ; => (4.0 -1)
```
