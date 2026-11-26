# string->utf8

`(string->utf8 string)` `(string->utf8 string start)` `(string->utf8 string start end)`

`string` の `start`（含む、既定は 0）から `end`（含まない、既定は末尾）までの文字を UTF-8 に符号化したバイトベクタを返します。

```scheme
(string->utf8 "λx") ; => #u8(206 187 120)
(string->utf8 "abcd" 1 3) ; => #u8(98 99)
```
