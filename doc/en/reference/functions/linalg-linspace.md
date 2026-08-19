# linalg:linspace

`(linalg:linspace start stop n &key element-type)`

Creates the packed vector of `n` evenly spaced numbers from `start` to `stop`, both endpoints included. Double-float by default; pass `:element-type 'single-float` for a packed single-float (`#f`) result. For a half-open integer range driven by a step size, use [`linalg:arange`](linalg-arange.md).

```lisp
(linalg:linspace 0 1 5) ; => #d(0.0 0.25 0.5 0.75 1.0)
(linalg:linspace 0 1 3 :element-type 'single-float) ; => #f(0.0 0.5 1.0)
```
