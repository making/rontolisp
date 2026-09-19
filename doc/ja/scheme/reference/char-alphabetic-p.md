# char-alphabetic?

`(char-alphabetic? char)`

`char` が Unicode の `Alphabetic` 属性を持てば `#t`、そうでなければ `#f` を返します。あらゆる文字体系の文字に加えて、`Ⅰ` のような文字数字や、インド系文字の結合母音記号も含みます。

```scheme
(char-alphabetic? #\a) ; => #t
(char-alphabetic? #\λ) ; => #t
(char-alphabetic? #\1) ; => #f
```
