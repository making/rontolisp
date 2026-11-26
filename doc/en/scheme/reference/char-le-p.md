# char<=?

`(char<=? char1 char2 char3 ...)`

Returns `#t` if the characters are non-decreasing by code point, otherwise `#f`. At least two arguments are required.

```scheme
(char<=? #\a #\a #\b) ; => #t
(char<=? #\b #\a) ; => #f
```
