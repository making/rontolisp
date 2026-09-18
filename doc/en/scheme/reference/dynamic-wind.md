# dynamic-wind

`(dynamic-wind before thunk after)`

Calls `before`, then `thunk`, then `after`, all with no arguments, and returns the value of `thunk`. `after` runs however `thunk` is left: normally, by an escaping continuation, by an `error` unwinding the program, or by `exit`. Because continuations cannot be re-entered, `before` runs exactly once.

```scheme
(dynamic-wind (lambda () (display "before ")) (lambda () (display "during ")) (lambda () (display "after")))
(newline)
```

```
before during after
```
