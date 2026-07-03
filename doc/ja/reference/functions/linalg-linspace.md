# linalg:linspace

`(linalg:linspace start stop n)`

`start` から `stop` まで (両端点を含む) を等間隔に並べた `n` 個の数のベクタを作成します。端点が整数の場合は浮動小数点数ではなく正確な有理数を生成するため、ステップを重ねても丸め誤差が蓄積しません。ステップ幅で駆動する半開区間の整数範囲には [`linalg:arange`](linalg-arange.md) を使ってください。

```lisp
(linalg:linspace 0 1 5) ; => #(0 1/4 1/2 3/4 1)
```
