# rontolisp:url-encode

`(rontolisp:url-encode string)`

文字列を URL に埋め込める形にエンコードします。RFC 3986 の非予約文字
(英数字、`-`、`.`、`_`、`~`)はそのまま通り、それ以外の文字は UTF-8
バイト列のパーセントエンコード形式になります(空白は `+` ではなく `%20`
です)。逆変換は [`rontolisp:url-decode`](rontolisp-url-decode.md) です。

```lisp
(rontolisp:url-encode "a b/c~d")   ; => "a%20b%2Fc~d"
(rontolisp:url-encode "あ")   ; => "%E3%81%82"
(rontolisp:url-decode (rontolisp:url-encode "日本語 text?&="))   ; => "日本語 text?&="
```

典型的な用途は、実行時の値から
[`rontolisp:fetch`](rontolisp-fetch.md) の URL を組み立てることです:

```lisp
(concatenate 'string "https://httpbin.ik.am/get?q=" (rontolisp:url-encode "ronto lisp"))
; => "https://httpbin.ik.am/get?q=ronto%20lisp"
```

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 を含む)で動作します。
ライブラリは rontolisp 自身で書かれており、使用時にプログラムへコンパイル
されます。
