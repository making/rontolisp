# multiple-value-prog1

`(multiple-value-prog1 first-form form...)`

Evaluates `first-form`, then the remaining forms for effect, and returns **all** the values of `first-form` -- [`prog1`](prog1.md) widened to multiple values. The values are captured with [`multiple-value-list`](multiple-value-list.md) and republished with [`values-list`](../functions/values-list.md), so they survive whatever the intervening forms do.

```lisp
(multiple-value-list
  (multiple-value-prog1 (floor 17 5) (list :cleanup))) ; => (3 2)
```
