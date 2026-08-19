# linalg:log-softmax

`(linalg:log-softmax array &key axis)`

Returns the logarithm of [`linalg:softmax`](linalg-softmax.md), computed as `(x - max) - log(sum(exp(x - max)))` rather than as the log of the softmax, so an exactly-zero weight gives `-infinity` instead of a `NaN`. The `:axis` rules are `linalg:softmax`'s. This is the numerically stable half of a cross-entropy loss.

```lisp
(linalg:log-softmax #(0 0))                   ; => #d(-0.6931471805599453 -0.6931471805599453)
(linalg:log-softmax #2A((0 0) (1 1)) :axis 1) ; => #d((-0.6931471805599453 -0.6931471805599453) (-0.6931471805599453 -0.6931471805599453))
```
