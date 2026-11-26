# torch:var

`(torch:var a &key axis keepdims ddof)`

Differentiable variance with the `(n - ddof)` divisor (`linalg:var`'s rules: the default `:ddof 0` is torch's `unbiased=False`, `:ddof 1` the sample variance). It is composed from [`torch:mean`](torch-mean.md), [`torch:sub`](torch-sub.md), [`torch:mul`](torch-mul.md) and [`torch:sum`](torch-sum.md), so its backward pass comes from the tape.

```lisp
(torch:item (torch:var (torch:tensor '(1.0 2.0 3.0 4.0))))          ; => 1.25
(torch:item (torch:var (torch:tensor '(1.0 2.0 3.0 4.0)) :ddof 1))   ; => 1.6666666666666667
```
