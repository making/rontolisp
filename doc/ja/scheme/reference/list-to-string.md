# list->string

`(list->string list)`

`list` の文字からなる新しい文字列を返します。文字でない要素はエラーになります。

```scheme
(list->string '(#\a #\b)) ; => "ab"
(list->string '()) ; => ""
```
