# linalg:where

`(linalg:where mask x y)`

Elementwise selection (numpy's `np.where`): the element of `x` wherever `mask` is **non-zero**, the element of `y` wherever it is zero. The 0.0/1.0 masks that [`linalg:greater`](linalg-greater.md) and its siblings return therefore select directly, with no multiply-by-mask detour -- which matters because multiplying turns an infinite operand into a `NaN` while selecting does not, so a `-infinity` attention mask survives into [`linalg:softmax`](linalg-softmax.md) as a weight of exactly zero. All three arguments may be scalars or arrays and broadcast together by the numpy rules; the result keeps `x`'s element width when `x` is an array, otherwise `y`'s.

```lisp
(linalg:where (linalg:greater #(1 5 3) 2) #(1 5 3) 0) ; => #d(0.0 5.0 3.0)
(linalg:where #(1 0 1) 10 20)                         ; => #d(10.0 20.0 10.0)
```
