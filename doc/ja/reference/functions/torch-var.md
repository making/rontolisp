# torch:var

`(torch:var a &key axis keepdims ddof)`

除数 `(n - ddof)` を持つ微分可能な分散です (`linalg:var` の規則: デフォルトの `:ddof 0` は torch の `unbiased=False`、`:ddof 1` は標本分散)。[`torch:mean`](torch-mean.md)、[`torch:sub`](torch-sub.md)、[`torch:mul`](torch-mul.md)、[`torch:sum`](torch-sum.md) の合成なので、backward はテープから得られます。

```lisp
(torch:item (torch:var (torch:tensor '(1.0 2.0 3.0 4.0))))          ; => 1.25
(torch:item (torch:var (torch:tensor '(1.0 2.0 3.0 4.0)) :ddof 1))   ; => 1.6666666666666667
```
