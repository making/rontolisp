# fceiling

`(fceiling number &optional divisor)`

Like [`ceiling`](ceiling.md), rounds `number` (or `number/divisor` when a divisor is given) toward positive infinity, but the primary value is always a float -- CLHS defines `fceiling` as `ceiling` with a FLOAT quotient. The second value (the remainder) is the same one `ceiling` answers.

```lisp
(fceiling 7 2) ; => 4.0
```

```lisp
(multiple-value-bind (q r) (fceiling 7 2)
  (list q r)) ; => (4.0 -1)
```
