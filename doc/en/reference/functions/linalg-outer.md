# linalg:outer

`(linalg:outer u v)`

The outer product of two vectors: element `(i j)` of the resulting matrix is the product of `u`'s element `i` and `v`'s element `j`. Like numpy, both inputs are flattened first, so matrices are accepted and treated as their row-major element sequence. For the inner product, use [`linalg:dot`](linalg-dot.md).

```lisp
(linalg:outer #(1 2) #(3 4 5)) ; => #d((3.0 4.0 5.0) (6.0 8.0 10.0))
```
