# parse-integer

`(parse-integer string &key start end radix junk-allowed)`

前後の空白を読み飛ばしつつ、文字列から整数をパースします。`:start`/`:end` はパース範囲を限定し、`:radix` は基数を選択し（デフォルトは 10）、`:junk-allowed` は非 nil の場合、最初の非数字文字で停止し、そこまでにパースした整数を返します（何もなければ `nil`）。`:junk-allowed` を指定しない場合、末尾に空白以外の文字があるとエラーになります。第 2 戻り値はパースが停止した位置で、そのまま次の `:start` に渡せます。キーワード一式と両方の戻り値はすべてのバックエンドで同一に動作します。第一級の値として利用できます（`#'parse-integer`）。

```lisp
(parse-integer "ff" :radix 16) ; => 255
```

```lisp
(multiple-value-bind (n pos) (parse-integer "42x" :junk-allowed t)
  (list n pos)) ; => (42 2)
```

`(parse-integer "42")` は `42` を返し、`(parse-integer "x9x" :start 1 :end 2)` は範囲内だけをパースして `9` を返します。
