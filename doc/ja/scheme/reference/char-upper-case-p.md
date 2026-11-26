# char-upper-case?

`(char-upper-case? char)`

`char` が Unicode の `Uppercase` 属性を持てば `#t`、そうでなければ `#f` を返します。`ǅ` のようなタイトルケース文字は大文字でも小文字でもありません。小文字を持たない `ℂ` は大文字です。

```scheme
(char-upper-case? #\A) ; => #t
(char-upper-case? #\a) ; => #f
(char-upper-case? #\ǅ) ; => #f
```
