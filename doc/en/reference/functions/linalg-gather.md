# linalg:gather

`(linalg:gather matrix indices)`

Returns the per-row elements `a[i, idx[i]]` of a matrix (numpy's `y[np.arange(n), t]` fancy-indexing idiom) as a vector of the input's width -- the pick-the-target-class-probability step of a cross-entropy loss. Index values are truncated to integers. It signals an error unless the input is a matrix and the index length matches its rows. For selecting whole rows instead, use [`linalg:take-rows`](linalg-take-rows.md).

```lisp
(linalg:gather #2A((10 11 12) (20 21 22)) #(2 0)) ; => #d(12.0 20.0)
```
