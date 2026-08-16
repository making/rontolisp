# rontolisp:query-param

`(rontolisp:query-param query name)`

クエリ文字列の中で最初に `name` に一致したパラメーターの URL デコード済みの
値を返します。名前が見つからないときは `nil` です。`query` は `nil` でも
構いません(結果も `nil`)。そのため
[`rontolisp:http-handler`](rontolisp-http-handler.md) のハンドラー内では、
クエリ文字列のないリクエストでもワンライナー
`(rontolisp:query-param (getf env :query-string) "name")` がそのまま動きます。

```lisp
(rontolisp:query-param "a=1&name=ronto%20lisp" "name")   ; => "ronto lisp"
(rontolisp:query-param "q=1&q=2" "q")   ; => "1"
(rontolisp:query-param "a=1" "missing")   ; => NIL
(rontolisp:query-param nil "a")   ; => NIL
```

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 を含む)で動作します。
ライブラリは rontolisp 自身で書かれており、使用時にプログラムへコンパイル
されます。すべてのパラメーターを一度に読むには
[`rontolisp:query-params`](rontolisp-query-params.md) を使ってください。
