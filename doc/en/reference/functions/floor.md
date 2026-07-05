# floor

`(floor number &optional divisor)`

Rounds `number` (or `number/divisor` when a divisor is given) toward negative infinity to an integer. In an ordinary (single-value) context the result is the quotient only; the remainder is the second value, observable through [`multiple-value-bind`](../macros/multiple-value-bind.md) and the other multiple-value consumers.

```lisp
(floor 3.7) ; => 3
```

```lisp
(floor -3.7) ; => -4
```

```lisp
(multiple-value-bind (q r) (floor 7 2)
  (list q r)) ; => (3 1)
```
