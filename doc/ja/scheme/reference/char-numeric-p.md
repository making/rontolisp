# char-numeric?

`(char-numeric? char)`

`char` がいずれかの文字体系の 10 進数字（Unicode 一般カテゴリ `Nd`）なら `#t`、そうでなければ `#f` を返します。分数やローマ数字は含みません。

```scheme
(char-numeric? #\7) ; => #t
(char-numeric? #\٤) ; => #t
(char-numeric? #\x) ; => #f
```
