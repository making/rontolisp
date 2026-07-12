# linalg:transpose

`(linalg:transpose array &optional axes)`

行列の転置を返します。結果の要素 `(i j)` は入力の要素 `(j i)` です。numpy と同様に、ランク 1 のベクタはそのまま返されます -- 行ベクトルと列ベクトルを区別する表現はありません。ベクタを本当の 1 行または 1 列の行列にするには [`linalg:reshape`](linalg-reshape.md) を使ってください。

`axes` リストを与えると(numpy の `x.transpose(0, 3, 1, 2)`)、代わりにランク n の軸の並べ替えを返します: 結果の軸 `k` は入力の軸 `(nth k axes)` になり、結果の shape は入力の shape を `axes` で並べ替えたものです。リストは入力の各軸をちょうど 1 回ずつ指定しなければなりません。

```lisp
(linalg:transpose #2A((1 2 3) (4 5 6))) ; => #d((1.0 4.0) (2.0 5.0) (3.0 6.0))
(linalg:transpose #(1 2 3))             ; => #(1 2 3)
(linalg:shape (linalg:transpose (linalg:zeros '(2 3 4)) '(1 0 2))) ; => (3 2 4)
```
