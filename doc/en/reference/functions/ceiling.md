# ceiling

`(ceiling number &optional divisor)`

Rounds `number` (or `number/divisor` when a divisor is given) toward positive infinity to an integer. In an ordinary (single-value) context the result is the quotient only; the remainder is the second value, observable through [`multiple-value-bind`](../macros/multiple-value-bind.md) and the other multiple-value consumers.

```lisp
(ceiling 3.2) ; => 4
```

```lisp
(multiple-value-bind (q r) (ceiling 7 2)
  (list q r)) ; => (4 -1)
```
