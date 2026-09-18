# pair?

`(pair? obj)`

Returns `#t` when `obj` is a pair, otherwise `#f`. The empty list is not a pair.

```scheme
(pair? '(a . b)) ; => #t
(pair? '()) ; => #f
(pair? 'a) ; => #f
```
