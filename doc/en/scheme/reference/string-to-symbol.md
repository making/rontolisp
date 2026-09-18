# string->symbol

`(string->symbol string)`

Returns the symbol whose name is `string`, case kept; the same name always gives the same (`eq?`) symbol.

Deviation: there are no `|...|` identifiers, so a symbol whose name is not a plain identifier, such as `(string->symbol "hello world")`, is written without bars: `hello world`.

```scheme
(string->symbol "abc") ; => abc
(eq? 'abc (string->symbol "abc")) ; => #t
```
