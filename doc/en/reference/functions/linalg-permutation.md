# linalg:permutation

`(linalg:permutation n)`

Returns the integers `0..n-1` in a Fisher-Yates shuffle (numpy's `np.random.permutation` of an integer), as a packed double vector of integer values -- the epoch-shuffling idiom, typically fed to [`linalg:take-rows`](linalg-take-rows.md). Seed with [`linalg:seed`](linalg-seed.md) for a backend-identical shuffle; for indices *with* replacement, use [`linalg:choice`](linalg-choice.md).

```lisp
(linalg:seed 9) ; => 9
(linalg:permutation 10) ; => #d(4.0 5.0 6.0 2.0 9.0 7.0 1.0 0.0 8.0 3.0)
```
