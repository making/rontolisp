# copy-tree

`(copy-tree tree)`

cons ツリーの深いコピーを返します: すべての cons セルが新しく作られ、cons でない葉(数値、シンボル、文字列など)は元の木と共有されます。トップレベルの背骨だけをコピーする `copy-list` と対比してください。

```lisp
(copy-tree '(1 (2 3) . 4)) ; => (1 (2 3) . 4)
```

```lisp
(let* ((orig (list (list 1 2)))
       (copy (copy-tree orig)))
  (setf (car (car copy)) 99)
  orig) ; => ((1 2))
```
