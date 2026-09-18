# procedure?

`(procedure? obj)`

Returns `#t` if `obj` is a procedure -- a built-in, a `lambda`, or a continuation captured by `call/cc` -- and `#f` otherwise.

```scheme
(procedure? car) ; => #t
(procedure? 'car) ; => #f
(procedure? (lambda (x) x)) ; => #t
```
