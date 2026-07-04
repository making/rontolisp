# linalg:flatten

`(linalg:flatten array)`

配列の要素を行優先の順序で並べた新しいランク 1 のベクタとして返します。総数 [`linalg:size`](linalg-size.md) を目標の shape として [`linalg:reshape`](linalg-reshape.md) を呼ぶのと等価で、入力がベクタの場合は自身の新しいコピーを返します。

```lisp
(linalg:flatten #2A((1 2) (3 4))) ; => #(1 2 3 4)
```
