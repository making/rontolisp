# torch:gather

`(torch:gather a idx)`

Differentiable per-row selection of a matrix: element `a[i, idx[i]]` for each row `i`, as a vector (`linalg:gather` -- the "pick the target logit" idiom of a cross-entropy loss). `idx` may be an index vector, a list or a tensor. The backward pass scatters the gradient back to the picked cells.

```lisp
(torch:data (torch:gather (torch:tensor '((1.0 2.0 3.0) (4.0 5.0 6.0))) #(2 0))) ; => #f(3.0 4.0)
```
