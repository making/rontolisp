# mapcon

`(mapcon function list)`

`maplist` と同様に `function` は `list` の連続する末尾（tail）に適用されますが、結果のリストは 1 つに連結されます（`mapcan` の末尾を辿る版です）。各部分は `append` で結合されます。単一リストの形式のみ対応しています。

```lisp
(mapcon (lambda (x) (list (car x))) '(1 2 3)) ; => (1 2 3)
```
