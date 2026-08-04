# rontolisp:query-params

`(rontolisp:query-params query)`

`"a=1&b=two&flag"` のようなクエリ文字列を、`(key . value)` 文字列ペアの
連想リスト(alist)にパースします。キーと値は
[`rontolisp:url-decode`](rontolisp-url-decode.md) で URL デコードされます。
`=` のないキーは値 `""` を持ち、重複するキーは順序を保って保持され、空の
セグメントはスキップされます。`nil`(クエリ文字列のないリクエスト)は
`nil` を返すため、[`rontolisp:http-handler`](rontolisp-http-handler.md) の
ハンドラー内で `(rontolisp:query-params (getf env :query-string))` は常に安全
です。

```lisp
(rontolisp:query-params "a=1&b=two&flag")   ; => (("a" . "1") ("b" . "two") ("flag" . ""))
(rontolisp:query-params "q=%E3%81%82&q=2")   ; => (("q" . "あ") ("q" . "2"))
(rontolisp:query-params nil)   ; => NIL
```

alist は読みやすく印字され、`assoc` でも扱えます:

```lisp
(cdr (assoc "b" (rontolisp:query-params "a=1&b=two") :test #'string=))   ; => "two"
```

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 を含む)で動作します。
ライブラリは rontolisp 自身で書かれており、使用時にプログラムへコンパイル
されます。「1 つの名前の値」を取り出すだけなら
[`rontolisp:query-param`](rontolisp-query-param.md) を使ってください。
