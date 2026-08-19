# linalg:std

`(linalg:std array &key axis keepdims ddof)`

Returns the standard deviation: the square root of [`linalg:var`](linalg-var.md), with the same `:axis`, `:keepdims` and `:ddof` rules (numpy's `np.std`). Together with [`linalg:mean`](linalg-mean.md) along the same axis this is the LayerNorm normalizer.

```lisp
(linalg:std #(2 4 4 4 5 5 7 9))           ; => 2.0
(linalg:std #2A((0 1 2) (3 4 5)) :axis 0) ; => #d(1.5 1.5 1.5)
```
