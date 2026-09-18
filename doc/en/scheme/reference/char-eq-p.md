# char=?

`(char=? char1 char2 char3 ...)`

Returns `#t` if all the characters are the same, otherwise `#f`. At least two arguments are required; the comparison is case-sensitive.

```scheme
(char=? #\a #\a #\a) ; => #t
(char=? #\a #\A) ; => #f
```
