# write-simple

`(write-simple obj)`

Like `write`, but writes shared structure out each time it occurs. It is the same as `write` on data without cycles; it does not detect cycles. There is no port argument.

```scheme
(define x (list 1 2))
(write-simple (list x x))
(newline)
```

```
((1 2) (1 2))
```
