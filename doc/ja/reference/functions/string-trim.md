# string-trim

`(string-trim character-bag string)`

`character-bag` に含まれる先頭および末尾の文字をすべて取り除いた新しい文字列を返します。内部の文字はそのまま残ります。`character-bag` は文字列または文字のリストで、その要素が取り除く対象の集合を構成します。トリミングは、各端で bag に含まれない最初の文字に達した時点で停止します。

```lisp
(string-trim " " "  hi  ") ; => "hi"
```

```lisp
(string-trim (list #\Space #\Tab) "  hi  ") ; => "hi"
```