# char?

`(char? obj)`

Returns `#t` if `obj` is a character, otherwise `#f`. A one-character string is not a character.

```scheme
(char? #\a) ; => #t
(char? "a") ; => #f
```
