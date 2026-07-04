# substitute

`(substitute new old sequence)`

`old` に `eql` なすべての要素を `new` に置き換えた新しいシーケンスを返します。その他の要素は変更されません。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します (`new` は文字にしてください)。引数は位置指定のみで、`:test` や `:key` はありません。元のシーケンスは変更されません。破壊的な操作にはリスト専用の `nsubstitute` を使います。

```lisp
(substitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(substitute #\o #\a "banana") ; => "bonono"
```
