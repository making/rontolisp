# char= char< char<=

`(char= character &rest characters)` -- `(char< character &rest characters)` -- `(char<= character &rest characters)`

Compare characters by their code points and return `t` or `nil`. All three are variadic and case-sensitive: `char=` is true when every argument is the same character, `char<` is true when the arguments are in strictly increasing code-point order, and `char<=` is true when they are in non-decreasing order.

```lisp
(char< #\a #\b #\c) ; => t
```
