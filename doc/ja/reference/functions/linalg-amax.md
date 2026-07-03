# linalg:amax

`(linalg:amax array)`

ベクタまたは行列の最大の要素を返します。空の配列はエラーを通知します。ベクタの最大要素の*インデックス*には [`linalg:argmax`](linalg-argmax.md) を使ってください。最小の要素に対する対応物は [`linalg:amin`](linalg-amin.md) です。

```lisp
(linalg:amax (linalg:from-list '((1 9) (3 4)))) ; => 9
```
