# torch:argmax

`(torch:argmax a &key axis)`

Non-differentiable: returns the index of the largest element (`linalg:argmax`) as a raw value, not a tensor -- the integer index for a vector, the per-slice index array with `:axis`. The indices feed [`torch:gather`](torch-gather.md) / [`torch:index-select`](torch-index-select.md), and greedy decoding reads its result directly.

```lisp
(torch:argmax (torch:tensor '(1.0 5.0 3.0)))                    ; => 1
(torch:argmax (torch:tensor '((1.0 4.0) (3.0 2.0))) :axis 1)     ; => #d(1.0 0.0)
```
