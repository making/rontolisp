# torch:unsqueeze

`(torch:unsqueeze a axis)`

Differentiable extent-1 axis insertion (`linalg:expand-dims`, torch's `unsqueeze`): a negative axis counts from the end of the result, so `-1` appends. Row-major order is unchanged, so the backward pass is a reshape.

```lisp
(torch:shape (torch:unsqueeze (torch:tensor '(1.0 2.0 3.0)) 0))  ; => (1 3)
(torch:shape (torch:unsqueeze (torch:tensor '(1.0 2.0 3.0)) -1)) ; => (3 1)
```
