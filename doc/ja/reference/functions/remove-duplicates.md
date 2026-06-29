# remove-duplicates

`(remove-duplicates list)`

重複する要素を取り除いた新しいリストを返します。各要素の最後の出現を残します (したがって残る要素の順序は最後に現れた位置に従います)。要素の比較は `eql` のみで行われ、`:test` や `:key` 引数はありません。元のリストは変更されません。

```lisp
(remove-duplicates '(1 2 1 3)) ; => (2 1 3)
```
