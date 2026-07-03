# linalg:shape

`(linalg:shape array)`

配列の各次元のサイズをリストで返します。ランク 1 のベクタなら `(n)`、ランク 2 の行列なら `(rows cols)` です。`array-dimensions` の linalg 版にあたります。要素の総数が必要な場合は [`linalg:size`](linalg-size.md) を使ってください。

```lisp
(linalg:shape (linalg:from-list '((1 2 3) (4 5 6)))) ; => (2 3)
(linalg:shape (linalg:from-list '(1 2 3)))           ; => (3)
```
