# linalg:diff

`(linalg:diff array &key n axis)`

Returns the `:n`-th discrete difference along `:axis` (numpy's `np.diff`; `:axis` defaults to the last axis, `-1`, and a negative axis counts from the end): each output element is `a[..., i+1, ...] - a[..., i, ...]`, applied `:n` times (default 1), so each step shortens that axis by one. It works for any rank -- a matrix differences within each row by default, down each column with `:axis 0` -- and the result is a fresh packed array of the input's width (`#f` stays `#f`). `:n 0` returns a packed copy. For a derivative estimate that keeps the input length, use [`linalg:gradient`](linalg-gradient.md).

```lisp
(linalg:diff #(1 2 4 7 0)) ; => #d(1.0 2.0 3.0 -7.0)
```

```lisp
(linalg:diff #(1 2 4 7 0) :n 2) ; => #d(1.0 1.0 -10.0)
```

```lisp
(linalg:diff #2A((1 3 6) (0 5 6))) ; => #d((2.0 3.0) (5.0 1.0))
```

```lisp
(linalg:diff #2A((1 3 6) (0 5 6)) :axis 0) ; => #d((-1.0 2.0 0.0))
```
