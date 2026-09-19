# char-alphabetic?

`(char-alphabetic? char)`

Returns `#t` if `char` has the Unicode `Alphabetic` property, otherwise `#f`: every letter of every script, and also letter numbers such as `Ⅰ` and the combining vowel signs of the Indic scripts.

```scheme
(char-alphabetic? #\a) ; => #t
(char-alphabetic? #\λ) ; => #t
(char-alphabetic? #\1) ; => #f
```
