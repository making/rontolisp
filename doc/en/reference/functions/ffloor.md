# ffloor

`(ffloor number &optional divisor)`

Like [`floor`](floor.md), rounds `number` (or `number/divisor` when a divisor is given) toward negative infinity, but the primary value is always a float -- CLHS defines `ffloor` as `floor` with a FLOAT quotient. The second value (the remainder) is the same one `floor` answers.

```lisp
(ffloor 7 2) ; => 3.0
```

```lisp
(multiple-value-bind (q r) (ffloor 7 2)
  (list q r)) ; => (3.0 1)
```
