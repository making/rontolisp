# linalg:transpose

`(linalg:transpose array)`

行列の転置を返します。結果の要素 `(i j)` は入力の要素 `(j i)` です。numpy と同様に、ランク 1 のベクタはそのまま返されます -- 行ベクトルと列ベクトルを区別する表現はありません。ベクタを本当の 1 行または 1 列の行列にするには [`linalg:reshape`](linalg-reshape.md) を使ってください。

```lisp
(linalg:transpose #2A((1 2 3) (4 5 6))) ; => #d((1.0 4.0) (2.0 5.0) (3.0 6.0))
(linalg:transpose #(1 2 3))             ; => #(1 2 3)
```
