# list-copy

`(list-copy obj)`

Returns a newly allocated copy of the list `obj`; a dotted list keeps its final `cdr`, and an `obj` that is not a pair is returned as it is. Only the spine is copied: the elements are shared, so mutating the copy with `set-car!` leaves the original alone.

```scheme
(list-copy '(1 2 3)) ; => (1 2 3)
(list-copy '(1 2 . 3)) ; => (1 2 . 3)
(define lst (list 1 2 3))
(define cp (list-copy lst))
(set-car! cp 99)
lst ; => (1 2 3)
```
