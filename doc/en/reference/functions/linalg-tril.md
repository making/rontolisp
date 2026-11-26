# linalg:tril

`(linalg:tril array &key k)`

Returns the lower triangle of `array`: a copy with everything **above** the `k`-th diagonal set to zero (numpy's `np.tril`). The [`linalg:triu`](linalg-triu.md) rules with the comparison flipped -- `:k` defaults to 0, rank must be at least 2, and a stack of matrices is masked on its last two axes.

```lisp
(linalg:tril #2A((1 2 3) (4 5 6) (7 8 9)))       ; => #d((1.0 0.0 0.0) (4.0 5.0 0.0) (7.0 8.0 9.0))
(linalg:tril #2A((1 2 3) (4 5 6) (7 8 9)) :k -1) ; => #d((0.0 0.0 0.0) (4.0 0.0 0.0) (7.0 8.0 0.0))
```
