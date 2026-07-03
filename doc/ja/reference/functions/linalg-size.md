# linalg:size

`(linalg:size array)`

配列の要素の総数、すなわち各次元のサイズの積を返します (`array-total-size` に相当)。次元ごとのサイズが必要な場合は [`linalg:shape`](linalg-shape.md) を使ってください。

```lisp
(linalg:size (linalg:from-list '((1 2 3) (4 5 6)))) ; => 6
```
