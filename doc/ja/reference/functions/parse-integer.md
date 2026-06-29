# parse-integer

`(parse-integer string &key radix junk-allowed)`

前後の空白を読み飛ばしつつ、文字列から整数をパースします。`:radix` は基数を選択し（デフォルトは 10）、`:junk-allowed` は非 nil の場合、最初の非数字文字で停止し、そこまでにパースした整数を返します（何もなければ `nil`）。`:junk-allowed` を指定しない場合、末尾に空白以外の文字があるとエラーになります。3 つのバックエンドすべてで動作します（`:start`/`:end` キーワードはインタプリタ専用で、コンパイル済みバックエンドではキーワード名はリテラルでなければなりません）。第一級の値として利用できます（`#'parse-integer`）。

```lisp
(parse-integer "ff" :radix 16) ; => 255
```

`(parse-integer "42")` は `42` を返し、`(parse-integer "12x" :junk-allowed t)` は `x` で停止して `12` を返します。
