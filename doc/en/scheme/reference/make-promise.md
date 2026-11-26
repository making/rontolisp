# make-promise

`(make-promise obj)`

Answers a promise already forced to `obj`. If `obj` is itself a promise, it is answered unchanged.

```scheme
(force (make-promise 42)) ; => 42
(promise? (make-promise 7)) ; => #t
(force (make-promise (make-promise 1))) ; => 1
```
