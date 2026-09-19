# char-downcase

`(char-downcase char)`

`char` の Unicode 単純マッピングによる小文字を返し、なければ `char` 自身を返します。

```scheme
(char-downcase #\A) ; => #\a
(char-downcase #\Σ) ; => #\σ
```
