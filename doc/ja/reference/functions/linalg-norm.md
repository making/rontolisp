# linalg:norm

`(linalg:norm array)`

ベクタのユークリッド (L2) ノルム、または行列のフロベニウスノルム、すなわち要素の 2 乗和の平方根を返します。`sqrt` が浮動小数点数を返すため、整数の入力に対しても結果は浮動小数点数です。

```lisp
(linalg:norm (linalg:from-list '(3 4))) ; => 5.0
```
