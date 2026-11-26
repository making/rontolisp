# linalg:var

`(linalg:var array &key axis keepdims ddof)`

Returns the variance -- the mean of the squared deviations from the mean -- of every element (no `:axis`) or along one axis, following the same `:axis` / `:keepdims` rules as [`linalg:sum`](linalg-sum.md). The divisor is `n - ddof`: the default `:ddof 0` is numpy's `np.var` and torch's `unbiased=False`, while `:ddof 1` gives the sample variance (Bessel's correction). The square root is [`linalg:std`](linalg-std.md).

```lisp
(linalg:var #(1 2 3 4))                   ; => 1.25
(linalg:var #(1 2 3 4) :ddof 1)           ; => 1.6666666666666667
(linalg:var #2A((0 1 2) (3 4 5)) :axis 1) ; => #d(0.6666666666666666 0.6666666666666666)
```
