# string-upcase

`(string-upcase string)`

Returns a new string: `string` by the Unicode full uppercase mapping, so the result can be longer than the argument (`ß` becomes `SS`).

```scheme
(string-upcase "Hello") ; => "HELLO"
(string-upcase "straße") ; => "STRASSE"
```
