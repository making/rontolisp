# list?

`(list? obj)`

Returns `#t` when `obj` is a proper list -- the empty list, or a chain of pairs ending in it -- otherwise `#f`.

```scheme
(list? '(a b c)) ; => #t
(list? '()) ; => #t
(list? '(1 . 2)) ; => #f
```
