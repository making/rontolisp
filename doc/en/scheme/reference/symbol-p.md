# symbol?

`(symbol? obj)`

Returns `#t` when `obj` is a symbol, otherwise `#f`. The empty list and the booleans are not symbols.

```scheme
(symbol? 'foo) ; => #t
(symbol? "foo") ; => #f
(symbol? '()) ; => #f
```
