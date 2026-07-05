# multiple-value-bind

`(multiple-value-bind (var...) values-form body...)`

Binds the variables to the values of `values-form` and evaluates the body. The producer is recognized syntactically: a literal [`values`](../functions/values.md) call supplies all of its values, and the two-value built-ins [`floor`](../functions/floor.md)/[`ceiling`](../functions/ceiling.md)/[`round`](../functions/round.md)/[`truncate`](../functions/truncate.md) (quotient and remainder) and [`gethash`](../functions/gethash.md) (value and present-p) supply both of theirs. Any other form -- including a call to a function whose body ends in `(values ...)` -- supplies a single value. Extra variables bind to nil; surplus values are evaluated and discarded.

```lisp
(multiple-value-bind (q r) (floor 7 2)
  (list q r)) ; => (3 1)
```

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) nil)
  (multiple-value-bind (v present-p) (gethash 'a h)
    (list v present-p))) ; => (nil t)
```
