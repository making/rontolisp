# torch:amax

`(torch:amax a &key axis keepdims)`

Differentiable maximum, of every element or along an axis (`linalg:amax`'s rules). The gradient flows to every element equal to the maximum, split evenly among ties (PyTorch's `amax` rule).

```lisp
(torch:item (torch:amax (torch:tensor '(1.0 5.0 3.0))))                     ; => 5.0
(torch:data (torch:amax (torch:tensor '((1.0 4.0) (3.0 2.0))) :axis 1))      ; => #f(4.0 3.0)
```
