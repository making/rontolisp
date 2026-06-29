# char= char< char<=

`(char= character &rest characters)` -- `(char< character &rest characters)` -- `(char<= character &rest characters)`

文字をコードポイントで比較して `t` または `nil` を返します。3 つとも可変長引数を取り、大文字・小文字を区別します。`char=` はすべての引数が同じ文字のときに真、`char<` は引数がコードポイントの厳密な昇順のときに真、`char<=` は非減少順のときに真になります。

```lisp
(char< #\a #\b #\c) ; => t
```
