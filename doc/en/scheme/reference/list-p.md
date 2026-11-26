# list?

`(list? obj)`

Returns `#t` when `obj` is a proper list -- the empty list, or a chain of pairs ending in it -- otherwise `#f`, a circular list included.

```scheme
(list? '(a b c)) ; => #t
(list? '()) ; => #t
(list? '(1 . 2)) ; => #f
(define ring (list 1 2))
(set-cdr! (cdr ring) ring)
(list? ring) ; => #f
```
