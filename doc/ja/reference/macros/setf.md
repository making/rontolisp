# setf

`(setf place value)`

汎用的な代入です。`value` を `place` で指定した場所に格納し、その値を返します。単純な変数のほかに、サポートされる place としてリストのアクセサ `car`、`cdr`、`nth`、`first` から `fourth`、`rest`、および `caXXXr` の合成があり、既存の構造の特定のスロットをその場で変更できます。適切なプリミティブな変更操作（`rplaca`／`rplacd` など）に展開されます。

```lisp
(let ((x (list 1 2 3))) (setf (second x) 99) x) ; => (1 99 3)
```
