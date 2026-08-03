# remove-duplicates

`(remove-duplicates sequence &key test key from-end)`

重複する要素を取り除いた新しいシーケンスを返します。各要素の最後の出現を残します (したがって残る要素の順序は最後に現れた位置に従います)。`:from-end t` を指定すると代わりに最初の出現を残します (リテラルの `t` または `nil` である必要があります)。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較の前に各要素へ適用されます。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します。元のシーケンスは変更されません。同じレンダリングを共有する [`delete-duplicates`](delete-duplicates.md) も参照してください。

```lisp
(remove-duplicates '(1 2 1 3)) ; => (2 1 3)
```

```lisp
(remove-duplicates "banana") ; => "bna"
```

```lisp
(remove-duplicates '("a" "b" "a" "c") :test #'string=) ; => ("b" "a" "c")
```
