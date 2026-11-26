# with-exception-handler

`(with-exception-handler handler thunk)`

Calls `thunk` with no arguments, with `handler` installed, and answers its value. An object raised inside `thunk` is passed to `handler`, which runs with the handlers outside this one in effect. For `raise-continuable` the handler's value is returned to the raise; for anything else the handler must leave by a continuation (`call/cc`) or raise, and returning is a secondary error.

Deviation: for an error a built-in procedure signals (`(+ 1 'a)`), the handler runs after `thunk` has been left -- the `after` thunks of the `dynamic-wind`s inside it have run -- on every backend; R7RS runs it where the error happened. `raise`, `raise-continuable` and `error` call it where they stand.

```scheme
(call/cc (lambda (k) (with-exception-handler (lambda (e) (k (list 'handled e))) (lambda () (raise 'boom))))) ; => (handled boom)
(with-exception-handler (lambda (e) 0) (lambda () (+ 5 (raise-continuable 'use-zero)))) ; => 5
```
