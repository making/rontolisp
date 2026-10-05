# substitute

`(substitute new old sequence &key test key start end count from-end)`

`old` に一致するすべての要素を `new` に置き換えた新しいシーケンスを返します。その他の要素は変更されません。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較の前に各要素へ適用されます (置き換える値は `new` そのものです)。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します (`new` は文字にしてください)。元のシーケンスは変更されません。破壊的な操作にはリスト専用の `nsubstitute` を使います。`:start`/`:end` は走査する部分列を区切り (範囲外の要素はテストも処理もされません)、`:count` は処理する一致要素の個数を制限し、`:from-end` は要素を訪れる順序を逆にします。そのため `:count` と併用すると、後ろから数えた一致が対象になります。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。

```lisp
(substitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(substitute #\o #\a "banana") ; => "bonono"
```

```lisp
(substitute "X" "b" '("a" "b" "c") :test #'string=) ; => ("a" "X" "c")
```

```lisp
(substitute 0 2 '(1 2 3 2) :count 1 :from-end t) ; => (1 2 3 0)
```
