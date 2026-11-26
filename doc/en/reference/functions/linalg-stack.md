# linalg:stack

`(linalg:stack arrays &key axis)`

Joins the arrays in the list `arrays` along a **new** axis (numpy's `np.stack`). Every input must have exactly the same shape; the result has one more axis, of extent `(length arrays)`, inserted at `:axis` (default 0, negative counting from the end of the *result*, so `-1` appends it). The result is a fresh array with the first input's element width. This is how a list of per-sample arrays becomes one batch array; to join along an axis that already exists, use [`linalg:concatenate`](linalg-concatenate.md).

```lisp
(linalg:stack (list #(1 2) #(3 4)))         ; => #d((1.0 2.0) (3.0 4.0))
(linalg:stack (list #(1 2) #(3 4)) :axis 1) ; => #d((1.0 3.0) (2.0 4.0))
```
