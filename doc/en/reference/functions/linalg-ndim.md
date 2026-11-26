# linalg:ndim

`(linalg:ndim a)`

Returns the number of dimensions of `a` (numpy's `np.ndim`): 0 for a plain number, 1 for a vector, 2 for a matrix, and so on. It is the linalg spelling of `array-rank`, extended to accept scalars. For the dimension sizes themselves, use [`linalg:shape`](linalg-shape.md); for the total element count, [`linalg:size`](linalg-size.md).

```lisp
(linalg:ndim 3.0)              ; => 0
(linalg:ndim #(1 2 3))         ; => 1
(linalg:ndim #2A((1 2) (3 4))) ; => 2
```
