# call-with-values

`(call-with-values producer consumer)`

Calls `producer` with no arguments and passes the values it returns as the arguments of `consumer`, returning what `consumer` returns.

```scheme
(call-with-values (lambda () (values 1 2)) cons) ; => (1 . 2)
(call-with-values (lambda () (values 1 2 3)) +) ; => 6
```
