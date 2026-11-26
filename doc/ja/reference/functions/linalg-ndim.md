# linalg:ndim

`(linalg:ndim a)`

`a` の次元数を返します（numpy の `np.ndim`）: 数値なら 0、ベクタなら 1、行列なら 2、以降も同様です。スカラーも受け付けるように拡張した `array-rank` の linalg 版にあたります。各次元のサイズが必要な場合は [`linalg:shape`](linalg-shape.md) を、要素の総数が必要な場合は [`linalg:size`](linalg-size.md) を使ってください。

```lisp
(linalg:ndim 3.0)              ; => 0
(linalg:ndim #(1 2 3))         ; => 1
(linalg:ndim #2A((1 2) (3 4))) ; => 2
```
