# utf8->string

`(utf8->string bytevector)` `(utf8->string bytevector start)` `(utf8->string bytevector start end)`

Decodes the bytes of `bytevector` from `start` (inclusive, default 0) to `end` (exclusive, default the end) as UTF-8 and returns the string. A byte that begins no valid UTF-8 sequence, or a sequence the range cuts short, decodes to the character with that byte's code (`#u8(255 65)` is `"ÿA"`) rather than being an error.

```scheme
(utf8->string #u8(65 66 67 227 129 130)) ; => "ABCあ"
(utf8->string #u8(65 66 67 68) 1 3) ; => "BC"
```
