# null?

`(null? obj)`

Returns `#t` when `obj` is the empty list, otherwise `#f`.

```scheme
(null? '()) ; => #t
(null? '(1)) ; => #f
(null? 'a) ; => #f
```
