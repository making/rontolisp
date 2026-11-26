# error-object-message

`(error-object-message error-object)`

Answers the message of an error object: the first argument `error` was given. For the error of a built-in procedure it is the whole report, and the message text is not part of R7RS -- it may differ between backends. Anything that is not an error object is an error.

```scheme
(guard (e (#t (error-object-message e))) (error "bad thing:" 1 2)) ; => "bad thing:"
```
