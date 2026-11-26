# linalg:gradient

`(linalg:gradient samples &optional spacing)`

Returns the numerical derivative of a vector of samples (numpy's `np.gradient`): second-order central differences at interior points and first-order one-sided differences at the two ends, so the result has the same length as the input (unlike [`linalg:diff`](linalg-diff.md)). `spacing` is either a uniform sample spacing (a number, default 1) or a coordinate vector of the same length for non-uniformly spaced samples (numpy's second-order interior formula, exact for quadratics). Vectors only, with at least 2 samples; the result preserves the input's width.

```lisp
(linalg:gradient #(0 1 4 9 16)) ; => #d(1.0 2.0 4.0 6.0 7.0)
```

```lisp
(linalg:gradient #(0 1 4 9 16) 2) ; => #d(0.5 1.0 2.0 3.0 3.5)
```

```lisp
(linalg:gradient #(0 1 9) #(0 1 3)) ; => #d(1.0 2.0 4.0)
```
