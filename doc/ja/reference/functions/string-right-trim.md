# string-right-trim

`(string-right-trim character-bag string)`

`character-bag` に含まれる末尾の文字を後方からのみ取り除いた新しい文字列を返します。左端はそのまま残ります。`character-bag` は、その文字が取り除く対象の集合を構成する文字列で、bag に含まれない最後の文字でトリミングは停止します。

```lisp
(string-right-trim "x" "hixx") ; => "hi"
```
