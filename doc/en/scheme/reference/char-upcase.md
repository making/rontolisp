# char-upcase

`(char-upcase char)`

Returns the uppercase of `char` by the Unicode simple mapping, or `char` itself when it has none. A letter whose uppercase is several characters (`ß`) is unchanged; `string-upcase` gives the full mapping.

```scheme
(char-upcase #\a) ; => #\A
(char-upcase #\ß) ; => #\ß
(char-upcase #\1) ; => #\1
```
