# boolean?

`(boolean? obj)`

Returns `#t` when `obj` is `#t` or `#f`. The empty list is not a boolean.

```scheme
(boolean? #f) ; => #t
(boolean? '()) ; => #f
```
