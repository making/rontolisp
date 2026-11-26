# string-copy

`(string-copy string)` `(string-copy string start)` `(string-copy string start end)`

`string` の新しく確保したコピー、または `start`（含む）から `end`（含まない、既定は文字列の末尾）までの部分のコピーを返します。

```scheme
(string-copy "hello" 1) ; => "ello"
(string-copy "hello" 1 3) ; => "el"
```
