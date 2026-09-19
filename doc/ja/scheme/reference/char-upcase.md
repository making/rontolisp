# char-upcase

`(char-upcase char)`

`char` の Unicode 単純マッピングによる大文字を返し、なければ `char` 自身を返します。大文字が複数の文字になる文字（`ß`）はそのままです。完全なマッピングは `string-upcase` が行います。

```scheme
(char-upcase #\a) ; => #\A
(char-upcase #\ß) ; => #\ß
(char-upcase #\1) ; => #\1
```
