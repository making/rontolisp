# string-left-trim

`(string-left-trim character-bag string)`

`character-bag` に含まれる先頭の文字を前方からのみ取り除いた新しい文字列を返します。右端はそのまま残ります。`character-bag` は文字列または文字のリストで、その要素が取り除く対象の集合を構成し、bag に含まれない最初の文字でトリミングは停止します。

```lisp
(string-left-trim "x" "xxhi") ; => "hi"
```
