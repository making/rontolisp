# linalg:linspace

`(linalg:linspace start stop n)`

Creates the packed double-float vector of `n` evenly spaced numbers from `start` to `stop`, both endpoints included. For a half-open integer range driven by a step size, use [`linalg:arange`](linalg-arange.md).

```lisp
(linalg:linspace 0 1 5) ; => #f(0.0 0.25 0.5 0.75 1.0)
```
