# substitute

`(substitute new old sequence &key test key)`

`old` に一致するすべての要素を `new` に置き換えた新しいシーケンスを返します。その他の要素は変更されません。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較の前に各要素へ適用されます (置き換える値は `new` そのものです)。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します (`new` は文字にしてください)。元のシーケンスは変更されません。破壊的な操作にはリスト専用の `nsubstitute` を使います。

```lisp
(substitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(substitute #\o #\a "banana") ; => "bonono"
```

```lisp
(substitute "X" "b" '("a" "b" "c") :test #'string=) ; => ("a" "X" "c")
```
