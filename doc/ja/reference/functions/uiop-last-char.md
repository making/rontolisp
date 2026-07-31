# uiop:last-char

`(uiop:last-char s)`

文字列 `s` の最後の文字を返します。`s` が空文字列、または文字列でない場合は `nil` を
返します — [`uiop:first-char`](uiop-first-char.md) の鏡像であり、上流の UIOP から
そのまま持ってきたものです。

```lisp
(list (uiop:last-char "hello") (uiop:last-char ""))   ; => (#\o NIL)
```

## バックエンドサポート

4 つすべてのバックエンドで動作します: rontolisp 自身で書かれたプレリュード定義であり、
使用時にプログラムへ組み込まれてコンパイルされます。
