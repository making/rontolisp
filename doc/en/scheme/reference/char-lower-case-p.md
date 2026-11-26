# char-lower-case?

`(char-lower-case? char)`

Returns `#t` if `char` has the Unicode `Lowercase` property, otherwise `#f`. `ß`, which has no single-character uppercase, is lower case.

```scheme
(char-lower-case? #\a) ; => #t
(char-lower-case? #\ß) ; => #t
(char-lower-case? #\A) ; => #f
```
