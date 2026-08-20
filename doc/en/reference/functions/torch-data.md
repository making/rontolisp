# torch:data

`(torch:data tensor)`

Returns the tensor's data: a linalg array (packed float, any rank), or a number for a scalar tensor. This is the raw array the `linalg` functions accept, so a value leaves the differentiable layer through this reader (use [`torch:detach`](torch-detach.md) to stay a tensor while leaving the tape).

```lisp
(torch:data (torch:tensor '((1.0 2.0) (3.0 4.0)))) ; => #f((1.0 2.0) (3.0 4.0))
(torch:data (torch:sum (torch:tensor '(1.0 2.0))))  ; => 3.0
```
