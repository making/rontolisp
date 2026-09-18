# string->list

`(string->list string)` `(string->list string start)` `(string->list string start end)`

`string` の文字、または `start`（含む）から `end`（含まない、既定は文字列の末尾）までの部分の文字のリストを返します。

```scheme
(string->list "abc") ; => (#\a #\b #\c)
(string->list "abcde" 1 3) ; => (#\b #\c)
```
