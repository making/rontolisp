# char>=?

`(char>=? char1 char2 char3 ...)`

Returns `#t` if the characters are non-increasing by code point, otherwise `#f`. At least two arguments are required.

```scheme
(char>=? #\c #\b #\b) ; => #t
(char>=? #\a #\b) ; => #f
```
