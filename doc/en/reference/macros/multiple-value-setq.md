# multiple-value-setq

`(multiple-value-setq (var...) values-form)`

Assigns the values of `values-form` to the existing variables with `setq` and returns the primary value. The producer is recognized like in [`multiple-value-bind`](multiple-value-bind.md) (a literal [`values`](../functions/values.md) call, the two-value built-ins [`floor`](../functions/floor.md)/[`ceiling`](../functions/ceiling.md)/[`round`](../functions/round.md)/[`truncate`](../functions/truncate.md) and [`gethash`](../functions/gethash.md)); extra variables receive nil.

```lisp
(let (a b)
  (multiple-value-setq (a b) (floor 17 5))
  (list a b)) ; => (3 2)
```

```lisp
(let (a b)
  (multiple-value-setq (a b) (values 1 2))) ; => 1
```
