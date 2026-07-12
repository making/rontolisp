# linalg:rand

`(linalg:rand shape &optional element-type)`

一様 [0, 1) の乱数で埋めた配列を返します (numpy の `np.random.rand` 相当ですが、`shape` は [`linalg:zeros`](linalg-zeros.md) と同じ shape designator で渡します。ベクタは整数、行列はリスト `(rows cols)`)。`element-type` に `'single-float` を渡すと packed single-float (`#f`) の結果になります。再現可能な列にするには、先に [`linalg:seed`](linalg-seed.md) を呼んでください -- シード済みの列はすべてのバックエンドで bit-identical です。

```lisp
(linalg:seed 42) ; => 42
(linalg:emap (lambda (x) (truncate (* 1024 x))) (linalg:rand 4)) ; => #d(457.0 189.0 499.0 381.0)
```
