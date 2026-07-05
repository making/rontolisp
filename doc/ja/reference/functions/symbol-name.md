# symbol-name

`(symbol-name symbol)`

シンボルの名前を文字列として返します。rontolisp のシンボルは大文字小文字を保存する(名前は書いたとおりに読み戻される、通常は小文字)ため、Common Lisp と違い結果は大文字化**されません**: `(symbol-name 'foo)` は `"FOO"` ではなく `"foo"` です。格納された名前がそのまま — `princ` が印字するテキストと同じ形で — 返されるので、キーワードは先頭の `:` を、[`gensym`](gensym.md)/[`make-symbol`](make-symbol.md) の結果は `#:` プレフィックスを保ちます。

コンパイルバックエンド(JVM/WASM)では `symbol-name` は `princ-to-string` の機構を共有するため、シンボル以外の引数はエラーにならずその表示テキストを返します(インタプリタはエラーを通知します)。

```lisp
(symbol-name 'foo) ; => "foo"
```

```lisp
(symbol-name :bar) ; => ":bar"
```

```lisp
(intern (symbol-name 'round-trip)) ; => round-trip
```
