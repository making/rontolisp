# char-whitespace?

`(char-whitespace? char)`

Returns `#t` if `char` has the Unicode `White_Space` property, otherwise `#f`: the ASCII space, tab, line feed, vertical tab, form feed and carriage return, U+0085, the no-break space, and the Unicode space and line and paragraph separators. Gauche answers `#f` for U+0085.

```scheme
(char-whitespace? #\space) ; => #t
(char-whitespace? #\tab) ; => #t
(char-whitespace? #\a) ; => #f
```
