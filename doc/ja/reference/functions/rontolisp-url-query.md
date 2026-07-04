# rontolisp:url-query

`(rontolisp:url-query string)`

URL やリクエストターゲット文字列の生のクエリ文字列部分を返します。最初の
`?` より後ろのテキスト(空のこともあります)で、`?` がなければ `nil` です。
対になる [`rontolisp:url-path`](rontolisp-url-path.md) は `?` より前の部分を
返します。結果はデコードされません —
[`rontolisp:query-params`](rontolisp-query-params.md) または
[`rontolisp:query-param`](rontolisp-query-param.md) に渡してください。

```lisp
(rontolisp:url-query "/get?a=1&b=2")   ; => "a=1&b=2"
(rontolisp:url-query "/get")   ; => nil
(rontolisp:query-param (rontolisp:url-query "https://example.com/s?q=lisp") "q")   ; => "lisp"
```

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 を含む)で動作します。
ライブラリは rontolisp 自身で書かれており、使用時にプログラムへコンパイル
されます。
