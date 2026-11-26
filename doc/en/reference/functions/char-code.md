# char-code

`(char-code character)`

Returns the integer code point of `character`. For ASCII characters this is the familiar value (for example `#\A` is `65`). It is the inverse of `code-char`. A non-character signals a `type-error` naming the operator (`CHAR-CODE: The value 1 is not of type CHARACTER`).

```lisp
(char-code #\A) ; => 65
```
