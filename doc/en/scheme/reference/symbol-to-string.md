# symbol->string

`(symbol->string symbol)`

Returns the name of `symbol` as a string, with its case kept: identifiers are case-sensitive. The string is a fresh copy, so mutating it does not rename the symbol.

```scheme
(symbol->string 'flying-fish) ; => "flying-fish"
(symbol->string 'ABC) ; => "ABC"
```
