# rontolisp:url-decode

`(rontolisp:url-decode string)`

パーセントエンコード(URL エンコード)された文字列をデコードします。各
`%XX` エスケープは 1 バイトになり、そのバイト列は UTF-8 としてデコードされ
ます(複数バイトにまたがるエスケープは 1 文字に組み立て直されます)。また
クエリ文字列の慣習に従い `+` は空白になります。逆変換は
[`rontolisp:url-encode`](rontolisp-url-encode.md) です。

```lisp
(rontolisp:url-decode "Will+it+work%3F")   ; => "Will it work?"
(rontolisp:url-decode "%E3%81%82%E3%81%84")   ; => "あい"
(rontolisp:url-decode "plain")   ; => "plain"
```

不正なエスケープ(`%` の後に 16 進数字が 2 つ続かない、または UTF-8 として
不正なバイト列)はエラーを通知します:

```console
> (rontolisp:url-decode "%2")
Error: url-decode: unterminated percent escape
```

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 を含む)で動作します。
ライブラリは rontolisp 自身で書かれており、使用時にプログラムへコンパイル
されます。[`rontolisp:query-params`](rontolisp-query-params.md) と
[`rontolisp:query-param`](rontolisp-query-param.md) はキーと値のデコードに
この関数を自動的に使います。
