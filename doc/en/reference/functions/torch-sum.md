# torch:sum

`(torch:sum a &key axis keepdims)`

Differentiable sum, of every element (no `:axis`) or along an axis, following `linalg:sum`'s `:axis` / `:keepdims` rules. The backward pass broadcasts the gradient back over the reduced extent.

```lisp
(torch:item (torch:sum (torch:tensor '(1.0 2.0 3.0))))               ; => 6.0
(torch:data (torch:sum (torch:tensor '((1.0 2.0) (3.0 4.0))) :axis 0)) ; => #f(4.0 6.0)
```
