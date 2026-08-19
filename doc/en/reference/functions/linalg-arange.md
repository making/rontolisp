# linalg:arange

`(linalg:arange stop &key element-type)` / `(linalg:arange start stop &optional step &key element-type)`

Creates the vector of numbers from `start` (default 0) up to but excluding `stop`, advancing by `step` (default 1; may be negative). With one argument it counts from 0, like numpy's `arange`. Double-float by default; pass `:element-type 'single-float` for a packed single-float (`#f`) result. For a fixed element count with both endpoints included, use [`linalg:linspace`](linalg-linspace.md) instead.

```lisp
(linalg:arange 5)      ; => #d(0.0 1.0 2.0 3.0 4.0)
(linalg:arange 2 10 2) ; => #d(2.0 4.0 6.0 8.0)
(linalg:arange 5 0 -1) ; => #d(5.0 4.0 3.0 2.0 1.0)
(linalg:arange 0 4 :element-type 'single-float) ; => #f(0.0 1.0 2.0 3.0)
```
