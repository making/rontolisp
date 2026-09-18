# eof-object?

`(eof-object? obj)`

Returns `#t` if `obj` is the end-of-file object, `#f` otherwise.

```scheme
(eof-object? (eof-object)) ; => #t
(eof-object? #f) ; => #f
```
