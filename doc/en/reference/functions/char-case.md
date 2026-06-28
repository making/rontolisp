# char-upcase char-downcase

`(char-upcase character)` -- `(char-downcase character)`

Return the upper- or lower-case form of a single character; a character that has no case (such as a digit or punctuation) is returned unchanged. `char-upcase` maps lowercase letters to uppercase and `char-downcase` does the reverse. The WASM backend folds case using ASCII rules only.

```lisp
(char-upcase #\a) ; => #\A
```
