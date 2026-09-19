# floor

`(floor number &optional divisor)`

Rounds `number` (or `number/divisor` when a divisor is given) toward negative infinity to an integer. In an ordinary (single-value) context the result is the quotient only; the remainder is the second value, observable through [`multiple-value-bind`](../macros/multiple-value-bind.md) and the other multiple-value consumers. A NaN or an infinity (as `number`, or as the quotient) has no integer to round to and signals an error, as do `ceiling`, `round`, `truncate` and each `f` variant.

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
