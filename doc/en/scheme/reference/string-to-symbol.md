# string->symbol

`(string->symbol string)`

Returns the symbol whose name is `string`, case kept; the same name always gives the same (`eq?`) symbol.

`write` puts a symbol whose name would not read back as an identifier between vertical lines, `display` does not.

```scheme
(string->symbol "abc") ; => abc
(eq? 'abc (string->symbol "abc")) ; => #t
(string->symbol "hello world") ; => |hello world|
```
