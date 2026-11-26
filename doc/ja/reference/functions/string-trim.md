# string-trim

`(string-trim character-bag string)`

`character-bag` に含まれる先頭および末尾の文字をすべて取り除いた新しい文字列を返します。内部の文字はそのまま残ります。`character-bag` は文字の任意のシーケンス (文字列・リスト・ベクタ) で、その要素が取り除く対象の集合を構成します。トリミングは、各端で bag に含まれない最初の文字に達した時点で停止します。

`string` は [文字列指定子](string.md) なので、シンボルや文字を渡すとその名前がトリムされます。`(string-trim "*" '*foo*)` は `"FOO"` を返します。`character-bag` は指定子ではなくシーケンスなので、そこに文字を 1 つ渡すのは 1 文字の bag ではなくエラーです。

```lisp
(string-trim " " "  hi  ") ; => "hi"
```

```lisp
(string-trim (list #\Space #\Tab) "  hi  ") ; => "hi"
```