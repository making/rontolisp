# char-lower-case?

`(char-lower-case? char)`

`char` が Unicode の `Lowercase` 属性を持てば `#t`、そうでなければ `#f` を返します。1 文字の大文字を持たない `ß` も小文字です。

```scheme
(char-lower-case? #\a) ; => #t
(char-lower-case? #\ß) ; => #t
(char-lower-case? #\A) ; => #f
```
