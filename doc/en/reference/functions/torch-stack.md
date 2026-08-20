# torch:stack

`(torch:stack tensors &key axis)`

Differentiable join of the list `tensors` along a new axis (`linalg:stack`): equal shapes, result rank + 1, the new axis at `:axis` (negative counts from the end of the result). The backward pass slices the gradient at each input's index and drops the axis again.

```lisp
(torch:data (torch:stack (list (torch:tensor '(1.0 2.0)) (torch:tensor '(3.0 4.0)))))
; => #f((1.0 2.0) (3.0 4.0))
```
