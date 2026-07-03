# linalg:linspace

`(linalg:linspace start stop n)`

Creates the vector of `n` evenly spaced numbers from `start` to `stop`, both endpoints included. Integer endpoints produce exact rationals rather than floats, so no rounding error accumulates across the steps. For a half-open integer range driven by a step size, use [`linalg:arange`](linalg-arange.md).

```lisp
(linalg:linspace 0 1 5) ; => #(0 1/4 1/2 3/4 1)
```
