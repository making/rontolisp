# string?

`(string? obj)`

`obj` が文字列なら `#t`、そうでなければ `#f` を返します。

```scheme
(string? "abc") ; => #t
(string? #\a) ; => #f
```
