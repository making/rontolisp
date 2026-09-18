# call-with-current-continuation

`(call-with-current-continuation proc)`

The long name of `call/cc`: calls `proc` with the current continuation, which is escape-only -- callable once, while the call is still running, with no re-entry. The usual use is an early exit from a loop.

```scheme
(call-with-current-continuation (lambda (return) (for-each (lambda (x) (if (negative? x) (return x))) '(1 -2 3)) 'none)) ; => -2
```
