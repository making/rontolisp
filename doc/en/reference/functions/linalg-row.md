# linalg:row

`(linalg:row array index)`

Returns the axis-0 slice `index` of `array` with axis 0 **dropped** (numpy's `x[i]` integer indexing), as a fresh array of the input's width: a matrix yields the row vector, a rank-4 batch yields the rank-3 sample. The index value is truncated to an integer.

This is the one-slice sibling of [`linalg:take-rows`](linalg-take-rows.md), which keeps axis 0 (numpy's `x[[i]]`, so a matrix stays a `(1 n)` matrix). Use `linalg:row` wherever numpy writes `x[i]` -- feeding one image of a batch to a forward pass, for instance -- and `aref` to read a single element. `array` must have rank >= 2; on a vector `linalg:row` signals an error, since `(aref v i)` already returns the element.

```lisp
(linalg:row #2A((1 2 3) (4 5 6) (7 8 9)) 1) ; => #d(4.0 5.0 6.0)
```
