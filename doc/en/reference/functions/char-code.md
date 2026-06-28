# char-code

`(char-code character)`

Returns the integer code point of `character`. For ASCII characters this is the familiar value (for example `#\A` is `65`). It is the inverse of `code-char`.

```lisp
(char-code #\A) ; => 65
```
