# char-upper-case?

`(char-upper-case? char)`

Returns `#t` if `char` has the Unicode `Uppercase` property, otherwise `#f`. A titlecase letter such as `ǅ` is neither upper nor lower case; `ℂ`, which has no lowercase, is upper case.

```scheme
(char-upper-case? #\A) ; => #t
(char-upper-case? #\a) ; => #f
(char-upper-case? #\ǅ) ; => #f
```
