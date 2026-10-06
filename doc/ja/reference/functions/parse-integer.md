# parse-integer

`(parse-integer string &key start end radix junk-allowed)`

前後の空白を読み飛ばしつつ、文字列から整数をパースします。`:start`/`:end` はパース範囲を限定し、`:radix` は基数を選択し（デフォルトは 10）、`:junk-allowed` は非 nil の場合、最初の非数字文字で停止し、そこまでにパースした整数を返します（何もなければ `nil`）。`:junk-allowed` を指定しない場合、範囲が整数でなければ（数字の後に空白以外の文字がある、数字が 1 つもない、範囲が空）`parse-error` を通知します。nil の `:end` は長さを意味します。`:radix` が 2 から 36 までの整数でない場合は、何かを読む前に `(INTEGER 2 36)` の `type-error`（[`digit-char-p`](digit-char-p.md) のもの）を通知します。空文字列や `:junk-allowed` 指定でも同じです。負の値、整数でない値（nil の `:start` を含む）、文字列の長さを超える値、終端より後ろの開始位置は、同じ範囲に対して [`subseq`](subseq.md) が通知するのと同じ `type-error` を通知します。第 2 戻り値はパースが停止した位置で、そのまま次の `:start` に渡せます。キーワード一式と両方の戻り値はすべてのバックエンドで同一に動作します。第一級の値として利用できます（`#'parse-integer`）。

```lisp
(parse-integer "ff" :radix 16) ; => 255
```

```lisp
(multiple-value-bind (n pos) (parse-integer "42x" :junk-allowed t)
  (list n pos)) ; => (42 2)
```

`(parse-integer "42")` は `42` を返し、`(parse-integer "x9x" :start 1 :end 2)` は範囲内だけをパースして `9` を返します。
