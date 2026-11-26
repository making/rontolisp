# promise?

`(promise? obj)`

Answers `#t` if `obj` is a promise made by `delay`, `delay-force`, `make-promise` or `cons-stream`, and `#f` otherwise. A promise is not a procedure.

```scheme
(promise? (delay 1)) ; => #t
(promise? 5) ; => #f
(procedure? (delay 1)) ; => #f
```
