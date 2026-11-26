# floor

`(floor number &optional divisor)`

Rounds `number` (or `number/divisor` when a divisor is given) toward negative infinity to an integer. In an ordinary (single-value) context the result is the quotient only; the remainder is the second value, observable through [`multiple-value-bind`](../macros/multiple-value-bind.md) and the other multiple-value consumers. The function object `#'floor` takes the same optional divisor and answers the same two values. A NaN or an infinity as `number` (or a NaN `divisor`) has no integer to round to and signals an error; a zero `divisor`, exact or float, signals `division-by-zero` (`(floor 7.5 0.0)`). The same holds for `ceiling`, `round`, `truncate` and each `f` variant.

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

```lisp
(mapcar #'floor '(7 9) '(2 4)) ; => (3 2)
```
