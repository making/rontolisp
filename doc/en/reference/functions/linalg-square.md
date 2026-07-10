# linalg:square

`(linalg:square array)`

Returns a fresh array of the same shape with every element multiplied by itself (numpy's `np.square`) -- `(linalg:mul array array)` under a numpy-parity name, so it rides [`linalg:mul`](linalg-mul.md)'s [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) kernels. A plain number squares to a plain number.

```lisp
(linalg:square #(1 2 3)) ; => #d(1.0 4.0 9.0)
```
