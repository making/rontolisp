# char= char< char<= char> char>= char/= char-equal

`(char= character &rest characters)` -- `(char< character &rest characters)` -- `(char<= character &rest characters)` -- `(char> character &rest characters)` -- `(char>= character &rest characters)` -- `(char/= character &rest characters)` -- `(char-equal character &rest characters)`

文字をコードポイントで比較して `t` または `nil` を返します。すべて可変長引数を取ります。`char=` はすべての引数が同じ文字のとき、`char<`/`char<=` は厳密な昇順 / 非減少順のとき、`char>`/`char>=` は厳密な降順 / 非増加順のとき、`char/=` はすべての引数が互いに異なるときに真になり、`char-equal` は大文字・小文字を区別しない `char=` です。

```lisp
(char< #\a #\b #\c) ; => T
```
