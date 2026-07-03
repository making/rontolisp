# linalg:amin

`(linalg:amin array)`

ベクタまたは行列の最小の要素を返します。空の配列はエラーを通知します。ベクタの最小要素の*インデックス*には [`linalg:argmin`](linalg-argmin.md) を使ってください。最大の要素に対する対応物は [`linalg:amax`](linalg-amax.md) です。

```lisp
(linalg:amin #(5 2 8)) ; => 2
```
