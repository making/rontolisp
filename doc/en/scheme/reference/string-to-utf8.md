# string->utf8

`(string->utf8 string)` `(string->utf8 string start)` `(string->utf8 string start end)`

Encodes the characters of `string` from `start` (inclusive, default 0) to `end` (exclusive, default the end) as UTF-8 and returns the bytevector.

```scheme
(string->utf8 "λx") ; => #u8(206 187 120)
(string->utf8 "abcd" 1 3) ; => #u8(98 99)
```
