# linalg:det

`(linalg:det matrix)`

正方行列の行列式を返します。部分ピボット選択付きのガウスの消去法で計算されます。整数と有理数の入力に対しては厳密な結果を返します。特異行列は浮動小数点の微小値ではなく厳密に `0` を返します。正方でない引数はエラーを通知します。行列式が 0 であることは、[`linalg:inv`](linalg-inv.md) と [`linalg:solve`](linalg-solve.md) が失敗する条件です。

```lisp
(linalg:det (linalg:from-list '((1 2) (3 4)))) ; => -2
(linalg:det (linalg:from-list '((1 2) (2 4)))) ; => 0
```
