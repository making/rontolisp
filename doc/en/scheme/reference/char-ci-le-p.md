# char-ci<=?

`(char-ci<=? char1 char2 char3 ...)`

Like `char<=?`, but compares the characters after `char-foldcase`. At least two arguments are required.

```scheme
(char-ci<=? #\a #\A #\b) ; => #t
(char-ci<=? #\b #\A) ; => #f
```
