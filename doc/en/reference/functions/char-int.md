# char-int

`(char-int character)`

Returns a non-negative integer encoding `character`. With no implementation-defined attributes beyond the code point, `char-int` answers the same value `char-code` does. A non-character signals a `type-error` naming the operator (`CHAR-INT: The value 1 is not of type CHARACTER`).

```lisp
(char-int #\A) ; => 65
```
