# linalg:arange

`(linalg:arange stop)` / `(linalg:arange start stop &optional step)`

Creates the vector of numbers from `start` (default 0) up to but excluding `stop`, advancing by `step` (default 1; may be negative). With one argument it counts from 0, like numpy's `arange`. For a fixed element count with both endpoints included, use [`linalg:linspace`](linalg-linspace.md) instead.

```lisp
(linalg:arange 5)      ; => #(0 1 2 3 4)
(linalg:arange 2 10 2) ; => #(2 4 6 8)
(linalg:arange 5 0 -1) ; => #(5 4 3 2 1)
```
