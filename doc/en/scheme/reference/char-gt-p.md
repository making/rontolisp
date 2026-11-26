# char>?

`(char>? char1 char2 char3 ...)`

Returns `#t` if the characters are strictly decreasing by code point, otherwise `#f`. At least two arguments are required.

```scheme
(char>? #\b #\a) ; => #t
(char>? #\a #\a) ; => #f
```
