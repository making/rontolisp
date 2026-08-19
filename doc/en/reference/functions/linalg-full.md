# linalg:full

`(linalg:full shape value &key element-type)`

Creates an array with every element set to `value`. `shape` is an integer for a rank-1 vector or a list `(rows cols)` for a rank-2 matrix. [`linalg:zeros`](linalg-zeros.md) and [`linalg:ones`](linalg-ones.md) are the special cases for 0 and 1. Double-float by default; pass `:element-type 'single-float` for a packed single-float (`#f`) result.

```lisp
(linalg:full '(2 2) 7) ; => #d((7.0 7.0) (7.0 7.0))
(linalg:full 2 0.5 :element-type 'single-float) ; => #f(0.5 0.5)
```
