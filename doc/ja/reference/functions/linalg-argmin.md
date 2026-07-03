# linalg:argmin

`(linalg:argmin vector)`

ベクタの最小の要素の 0 始まりのインデックスを返します。同値の場合は最初のインデックスを採用します。受け付けるのはベクタのみで、空のベクタはエラーを通知します。値そのもの（および行列のサポート）には [`linalg:amin`](linalg-amin.md) を使ってください。対応物は [`linalg:argmax`](linalg-argmax.md) です。

```lisp
(linalg:argmin (linalg:from-list '(5 2 8))) ; => 1
```
