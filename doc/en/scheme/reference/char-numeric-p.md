# char-numeric?

`(char-numeric? char)`

Returns `#t` if `char` is a decimal digit (Unicode general category `Nd`) of any script, otherwise `#f`. A fraction or a Roman numeral is not one.

```scheme
(char-numeric? #\7) ; => #t
(char-numeric? #\٤) ; => #t
(char-numeric? #\x) ; => #f
```
