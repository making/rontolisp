# error-object-irritants

`(error-object-irritants error-object)`

Answers the irritants of an error object as a list: the arguments `error` was given after the message. The error of a built-in procedure has none. Anything that is not an error object is an error.

```scheme
(guard (e (#t (error-object-irritants e))) (error "bad thing:" 1 2)) ; => (1 2)
(guard (e (#t (error-object-irritants e))) (error "only a message")) ; => ()
```
