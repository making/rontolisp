# char-ci>?

`(char-ci>? char1 char2 char3 ...)`

Like `char>?`, but compares the characters after `char-foldcase`. At least two arguments are required.

```scheme
(char-ci>? #\c #\B #\a) ; => #t
(char-ci>? #\a #\A) ; => #f
```
