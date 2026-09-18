# error

`(error message obj ...)`

Signals an error with the string `message` and the irritants `obj ...`. There is no `guard` or `with-exception-handler` yet, so the error cannot be caught: it ends the program, printing `message` followed by the irritants as `write` shows them (`(error "bad thing:" 42 "str")` reports `bad thing: 42 "str"`), and the process exits with status 1. The `after` thunks of the enclosing `dynamic-wind`s run first.

```scheme
(define (safe-div a b)
  (if (= b 0)
      (error "division by zero:" a)
      (/ a b)))
(display (safe-div 10 4))
(newline)
```

```
5/2
```
