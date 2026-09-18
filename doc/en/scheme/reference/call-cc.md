# call/cc

`(call/cc proc)`

Calls `proc` with the current continuation as its one argument. Calling the continuation with a value returns that value from the `call/cc` call. The continuation is escape-only: it can be called once, while its `call/cc` is still running. Re-entering it later is not supported, so generators and coroutines cannot be built on it. `call-with-current-continuation` is the same procedure.

```scheme
(call/cc (lambda (k) (+ 1 (k 42)))) ; => 42
(call/cc (lambda (k) 5)) ; => 5
```
