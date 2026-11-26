# char<?

`(char<? char1 char2 char3 ...)`

Returns `#t` if the characters are strictly increasing by code point, otherwise `#f`. At least two arguments are required. Uppercase letters sort before lowercase ones.

```scheme
(char<? #\a #\b #\c) ; => #t
(char<? #\a #\A) ; => #f
```
