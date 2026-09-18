# error

`(error message obj ...)`

Raises an error object carrying the string `message` and the irritants `obj ...`; `error-object-message` and `error-object-irritants` take them apart, and `guard` and `with-exception-handler` catch it like any raised object. Uncaught, it ends the program, printing `message` followed by the irritants as `write` shows them (`(error "bad thing:" 42 "str")` reports `bad thing: 42 "str"`), and the process exits with status 1. The `after` thunks of the enclosing `dynamic-wind`s run first.

```scheme
(guard (e (#t (error-object-message e))) (error "division by zero:" 1)) ; => "division by zero:"
(guard (e (#t (error-object-irritants e))) (error "bad thing:" 42 "str")) ; => (42 "str")
```
