# list-copy

`(list-copy obj)`

Returns a newly allocated copy of the list `obj`. Only the spine is copied: the elements are shared, so mutating the copy with `set-car!` leaves the original alone.

```scheme
(list-copy '(1 2 3)) ; => (1 2 3)
(define lst (list 1 2 3))
(define cp (list-copy lst))
(set-car! cp 99)
lst ; => (1 2 3)
```
