# truncate

`(truncate number &optional divisor)`

Rounds `number` (or `number/divisor` when a divisor is given) toward zero to an integer, discarding any fractional part. In an ordinary (single-value) context the result is the quotient only; the remainder is the second value, observable through [`multiple-value-bind`](../macros/multiple-value-bind.md) and the other multiple-value consumers.

```lisp
(truncate 3.7) ; => 3
```

```lisp
(multiple-value-bind (q r) (truncate -7 2)
  (list q r)) ; => (-3 -1)
```
