# char-lessp char-greaterp char-not-lessp char-not-greaterp char-not-equal

`(char-lessp character &rest characters)` -- `(char-greaterp character &rest characters)` -- `(char-not-lessp character &rest characters)` -- `(char-not-greaterp character &rest characters)` -- `(char-not-equal character &rest characters)`

The case-INSENSITIVE character ordering family, the counterparts of `char<` / `char>` / `char>=` / `char<=` / `char/=`: each argument is downcased before its code point is compared. `char-lessp` is true when the arguments are in strictly increasing order, `char-greaterp` in strictly decreasing order, `char-not-lessp` in non-increasing order, `char-not-greaterp` in non-decreasing order, and `char-not-equal` when ALL arguments are pairwise distinct.

```lisp
(list (char-lessp #\a #\B) (char-lessp #\B #\a) (char-greaterp #\b #\A)
      (char-not-equal #\a #\b #\A)) ; => (T NIL T NIL)
```
