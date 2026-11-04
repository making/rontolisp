# character

`(character designator)`

Returns the character a character designator names: a character is itself, and a string or a symbol whose name has exactly one character is that character. Anything else signals a `type-error`.

```lisp
(list (character "a") (character #\b) (character 'c)) ; => (#\a #\b #\C)
```
