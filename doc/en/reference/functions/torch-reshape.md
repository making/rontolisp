# torch:reshape

`(torch:reshape a shape)`

Differentiable reshape (row-major, `linalg:reshape`'s rules: sizes must agree, one extent may be `-1` and is inferred); the backward pass reshapes the gradient back to the input's shape.

```lisp
(torch:data (torch:reshape (torch:tensor '(1.0 2.0 3.0 4.0)) '(2 2))) ; => #d((1.0 2.0) (3.0 4.0))
```
