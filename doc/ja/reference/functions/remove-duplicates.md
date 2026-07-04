# remove-duplicates

`(remove-duplicates sequence)`

重複する要素を取り除いた新しいシーケンスを返します。各要素の最後の出現を残します (したがって残る要素の順序は最後に現れた位置に従います)。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します。要素の比較は `eql` のみで行われ、`:test` や `:key` 引数はありません。元のシーケンスは変更されません。

```lisp
(remove-duplicates '(1 2 1 3)) ; => (2 1 3)
```

```lisp
(remove-duplicates "banana") ; => "bna"
```
