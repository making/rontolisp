# linalg:emap

`(linalg:emap function array)`

Returns a fresh array of the same shape with `function` applied to every element -- the array analogue of `mapcar`. It works on vectors and matrices alike, and accepts any function value: a `lambda`, a `#'`-quoted built-in, or a `#'`-quoted linalg function. The binary elementwise operators [`linalg:add`](linalg-add.md), [`linalg:sub`](linalg-sub.md), [`linalg:mul`](linalg-mul.md) and [`linalg:div`](linalg-div.md) cover the common two-operand cases.

```lisp
(linalg:emap (lambda (x) (* x x)) (linalg:arange 4)) ; => #(0 1 4 9)
```
