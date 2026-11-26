# char?

`(char? obj)`

`obj` が文字なら `#t`、そうでなければ `#f` を返します。1 文字の文字列は文字ではありません。

```scheme
(char? #\a) ; => #t
(char? "a") ; => #f
```
