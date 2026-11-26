# round

`(round number &optional divisor)`

Rounds `number` (or `number/divisor` when a divisor is given) to the nearest integer, using banker's rounding: a value exactly halfway between two integers rounds to the even one. In an ordinary (single-value) context the result is the quotient only; the remainder is the second value, observable through [`multiple-value-bind`](../macros/multiple-value-bind.md) and the other multiple-value consumers.

```lisp
(round 3.5) ; => 4
```

```lisp
(round 2.5) ; => 2
```

```lisp
(multiple-value-bind (q r) (round 7 2)
  (list q r)) ; => (4 -1)
```
