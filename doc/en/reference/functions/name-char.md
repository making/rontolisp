# name-char

`(name-char name)`

The character a string designator names, compared with `string-equal`: the names [`char-name`](char-name.md) answers (`"Space"`, `"Newline"`, `"Tab"`, `"Return"`, `"Page"`, `"Backspace"`, `"Null"`, `"Rubout"`), `"Linefeed"` and `"Nul"`, and `"U+"` followed by hex digits. Any other string, a one-character one included, names no character and answers nil.

```lisp
(name-char "space") ; => #\Space
```

```lisp
(name-char "a") ; => NIL
```
