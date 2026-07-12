# linalg:rand

`(linalg:rand shape &optional element-type)`

Returns an array of uniform draws in `[0, 1)` (numpy's `np.random.rand`, but taking a shape designator like [`linalg:zeros`](linalg-zeros.md): an integer for a vector, a list for a matrix). Double-float by default; pass `'single-float` for a packed `#f` result. Draws come from the shared generator, so a program that calls [`linalg:seed`](linalg-seed.md) first gets the same values on every backend.

```lisp
(linalg:seed 42) ; => 42
(linalg:emap (lambda (x) (truncate (* 1024 x))) (linalg:rand 4)) ; => #d(457.0 189.0 499.0 381.0)
```
