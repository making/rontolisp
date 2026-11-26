# char= char< char<= char> char>= char/= char-equal

`(char= character &rest characters)` -- `(char< character &rest characters)` -- `(char<= character &rest characters)` -- `(char> character &rest characters)` -- `(char>= character &rest characters)` -- `(char/= character &rest characters)` -- `(char-equal character &rest characters)`

Compare characters by their code points and return `t` or `nil`. All are variadic: `char=` is true when every argument is the same character, `char<`/`char<=` when the arguments are in strictly increasing / non-decreasing order, `char>`/`char>=` when they are in strictly decreasing / non-increasing order, `char/=` when ALL arguments are pairwise distinct, and `char-equal` is the case-insensitive `char=`. Every argument is checked, the lone one of a one-argument call included: a non-character signals a `type-error`.

```lisp
(char< #\a #\b #\c) ; => T
```
