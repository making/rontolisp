# linalg:linspace

`(linalg:linspace start stop n &key element-type)`

`start` から `stop` まで (両端点を含む) を等間隔に並べた `n` 個の数の packed ベクタを作成します。 デフォルトは packed double-float で、`:element-type 'single-float` を渡すと packed single-float (`#f`) になります。ステップ幅で駆動する半開区間の整数範囲には [`linalg:arange`](linalg-arange.md) を使ってください。

```lisp
(linalg:linspace 0 1 5) ; => #d(0.0 0.25 0.5 0.75 1.0)
(linalg:linspace 0 1 3 :element-type 'single-float) ; => #f(0.0 0.5 1.0)
```
