# linalg:choice

`(linalg:choice n size)`

Returns `size` uniform indices in `[0, n)`, drawn *with* replacement (numpy's `np.random.choice` default for an integer argument), as a packed double vector of integer values -- the mini-batch sampling idiom, typically fed to [`linalg:take-rows`](linalg-take-rows.md). Seed with [`linalg:seed`](linalg-seed.md) for a backend-identical sequence; for indices without replacement, use [`linalg:permutation`](linalg-permutation.md).

```lisp
(linalg:seed 42) ; => 42
(linalg:choice 60000 4) ; => #d(26833.0 11120.0 29256.0 22347.0)
```
