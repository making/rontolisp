# linalg:linspace

`(linalg:linspace start stop n)`

`start` から `stop` まで (両端点を含む) を等間隔に並べた `n` 個の数の packed double-float ベクタを作成します。ステップ幅で駆動する半開区間の整数範囲には [`linalg:arange`](linalg-arange.md) を使ってください。

```lisp
(linalg:linspace 0 1 5) ; => #f(0.0 0.25 0.5 0.75 1.0)
```
