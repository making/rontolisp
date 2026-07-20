# multiple-value-bind

`(multiple-value-bind (var...) values-form body...)`

Binds the variables to the values of `values-form` and evaluates the body. A literal [`values`](../functions/values.md) call supplies all of its values, and the two-value built-ins [`floor`](../functions/floor.md)/[`ceiling`](../functions/ceiling.md)/[`round`](../functions/round.md)/[`truncate`](../functions/truncate.md) (quotient and remainder) and [`gethash`](../functions/gethash.md) (value and present-p) supply both of theirs. A call to a USER function whose result is a `(values ...)` call also supplies all of its values: the extra values cross the function boundary through an internal channel the callee's `values` writes and the consumer reads. Extra variables bind to nil; surplus values are evaluated and discarded. Deviation from Common Lisp: a producer that calls `values` in a non-tail position and then returns normally may leave stale extra values behind, so prefer `values` in result position.

```lisp
(multiple-value-bind (q r) (floor 7 2)
  (list q r)) ; => (3 1)
```

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) nil)
  (multiple-value-bind (v present-p) (gethash 'a h)
    (list v present-p))) ; => (NIL T)
```

```lisp
(defun div-mod (a b) (values (floor (/ a b)) (mod a b)))
(multiple-value-bind (q r) (div-mod 17 5)
  (list q r)) ; => (3 2)
```
