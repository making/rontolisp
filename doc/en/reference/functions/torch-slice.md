# torch:slice

`(torch:slice a specs)`

Differentiable numpy basic slicing (`linalg:slice`: one spec per axis -- `nil` leaves the axis whole, `(start end)` / `(start end step)` selects along it, negative indexing and steps included). The backward pass scatters the gradient back into zeros at the positions the slice read from.

```lisp
(torch:data (torch:slice (torch:tensor '((0.0 1.0 2.0) (3.0 4.0 5.0))) '(nil (0 2))))
; => #d((0.0 1.0) (3.0 4.0))
```
