# linalg:squeeze

`(linalg:squeeze array &key axis)`

Returns a copy of `array` with extent-1 axes removed (numpy's `np.squeeze`). With no `:axis` every such axis goes; with an integer `:axis` -- or a list of them, negative counting from the end -- only those, and an axis whose extent is not 1 signals an error. Squeezing away *every* axis returns the single element itself, because `linalg` has no rank-0 arrays (a plain number is what rank 0 means here). The inverse is [`linalg:expand-dims`](linalg-expand-dims.md).

```lisp
(linalg:squeeze #2A((1 2 3)))                         ; => #d(1.0 2.0 3.0)
(linalg:squeeze (linalg:expand-dims #(1 2) 0) :axis 0) ; => #d(1.0 2.0)
```
