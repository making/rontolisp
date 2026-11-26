# linalg:triu

`(linalg:triu array &key k)`

Returns the upper triangle of `array`: a copy with everything **below** the `k`-th diagonal set to zero (numpy's `np.triu`). `:k` defaults to 0, which keeps the main diagonal; a positive `:k` moves the boundary up and to the right, a negative one down and to the left. Rank must be at least 2, and for a stack of matrices the last two axes are the matrix. Applied to an all-ones matrix with `:k 1` this is the causal ("subsequent") attention mask. The mirror is [`linalg:tril`](linalg-tril.md).

```lisp
(linalg:triu #2A((1 2 3) (4 5 6) (7 8 9))) ; => #d((1.0 2.0 3.0) (0.0 5.0 6.0) (0.0 0.0 9.0))
(linalg:triu (linalg:ones '(3 3)) :k 1)    ; => #d((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0))
```
