# torch:cat

`(torch:cat tensors &key axis)`

Differentiable concatenation of the list `tensors` along an existing axis (`linalg:concatenate`, `torch.cat`; default 0, negative counts from the end). The backward pass slices the gradient back into each input's extent along that axis.

```lisp
(torch:data (torch:cat (list (torch:tensor '(1.0 2.0)) (torch:tensor '(3.0))))) ; => #f(1.0 2.0 3.0)
```
