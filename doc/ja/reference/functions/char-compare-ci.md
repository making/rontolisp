# char-lessp char-greaterp char-not-lessp char-not-greaterp char-not-equal

`(char-lessp character &rest characters)` -- `(char-greaterp character &rest characters)` -- `(char-not-lessp character &rest characters)` -- `(char-not-greaterp character &rest characters)` -- `(char-not-equal character &rest characters)`

大文字・小文字を区別**しない**文字比較の一群で、`char<` / `char>` / `char>=` / `char<=` / `char/=` に対応します。各引数は小文字化してからコードポイントを比較します。`char-lessp` は引数が厳密な昇順のとき、`char-greaterp` は厳密な降順のとき、`char-not-lessp` は非増加順のとき、`char-not-greaterp` は非減少順のとき、`char-not-equal` はすべての引数が互いに異なるときに真になります。

```lisp
(list (char-lessp #\a #\B) (char-lessp #\B #\a) (char-greaterp #\b #\A)
      (char-not-equal #\a #\b #\A)) ; => (T NIL T NIL)
```
