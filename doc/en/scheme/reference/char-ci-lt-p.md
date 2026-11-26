# char-ci<?

`(char-ci<? char1 char2 char3 ...)`

Like `char<?`, but compares the characters after `char-foldcase`, so a letter sorts by its lowercase. At least two arguments are required.

```scheme
(char-ci<? #\a #\B #\c) ; => #t
(char-ci<? #\Z #\a) ; => #f
```
