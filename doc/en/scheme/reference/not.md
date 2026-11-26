# not

`(not obj)`

Returns `#t` when `obj` is `#f`, and `#f` for every other value. The empty list `'()` and `0` are true.

```scheme
(not #f) ; => #t
(not '()) ; => #f
(not 0) ; => #f
```
