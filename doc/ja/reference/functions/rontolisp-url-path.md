# rontolisp:url-path

`(rontolisp:url-path string)`

URL やリクエストターゲット文字列のうち、最初の `?` より前の部分を返します
(`?` がなければ文字列全体)。対になる
[`rontolisp:url-query`](rontolisp-url-query.md) は `?` より後ろの部分を返し
ます。

```lisp
(rontolisp:url-path "/get?a=1")   ; => "/get"
(rontolisp:url-path "/get")   ; => "/get"
(rontolisp:url-path "https://example.com/a/b?x=1")   ; => "https://example.com/a/b"
```

[`rontolisp:http-handler`](rontolisp-http-handler.md) のハンドラー内では
リクエスト plist の `:path` が既にパスのみを保持しているため、このヘルパー
は主に [`rontolisp:fetch`](rontolisp-fetch.md)(クライアント)側で URL
文字列を分割するのに使います。

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 を含む)で動作します。
ライブラリは rontolisp 自身で書かれており、使用時にプログラムへコンパイル
されます。
