# linalg:argmax

`(linalg:argmax vector)`

ベクタの最大の要素の 0 始まりのインデックスを返します。同値の場合は最初のインデックスを採用します。受け付けるのはベクタのみで、空のベクタはエラーを通知します。値そのもの（および行列のサポート）には [`linalg:amax`](linalg-amax.md) を使ってください。対応物は [`linalg:argmin`](linalg-argmin.md) です。

```lisp
(linalg:argmax #(1 9 3)) ; => 1
```
