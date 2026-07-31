# uiop:first-char

`(uiop:first-char s)`

文字列 `s` の最初の文字を返します。`s` が空文字列、または文字列でない場合は `nil` を
返します。UIOP の一行定義を上流からそのまま持ってきたもので、「最初の文字はあるか」の
判定とアクセスが、`length` によるガードと `char` の 2 つではなく 1 回の呼び出しで
済みます。

```lisp
(list (uiop:first-char "hello") (uiop:first-char ""))   ; => (#\h NIL)
```

鏡像は [`uiop:last-char`](uiop-last-char.md) です。quri の `render-uri` は
パスの先頭にスラッシュが必要かを判断するために、この 2 つと
[`uiop:emptyp`](uiop-emptyp.md) を呼びます。

## バックエンドサポート

4 つすべてのバックエンドで動作します: rontolisp 自身で書かれたプレリュード定義であり、
使用時にプログラムへ組み込まれてコンパイルされます。
