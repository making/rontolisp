# ftruncate

`(ftruncate number &optional divisor)`

Like [`truncate`](truncate.md), rounds `number` (or `number/divisor` when a divisor is given) toward zero, but the primary value is always a float -- CLHS defines `ftruncate` as `truncate` with a FLOAT quotient. The second value (the remainder) is the same one `truncate` answers.

```lisp
(ftruncate -7 2) ; => -3.0
```

```lisp
(multiple-value-bind (q r) (ftruncate -7 2)
  (list q r)) ; => (-3.0 -1)
```
