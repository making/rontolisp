# error-object?

`(error-object? obj)`

Answers `#t` when `obj` is an error object: what `error` raises, or the condition of an error a built-in procedure signals. Any other raised object answers `#f`.

```scheme
(guard (e (#t (error-object? e))) (error "bad")) ; => #t
(guard (e (#t (error-object? e))) (raise 'oops)) ; => #f
(guard (e (#t (error-object? e))) (+ 1 (car '(a)))) ; => #t
```
