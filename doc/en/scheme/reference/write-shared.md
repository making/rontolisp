# write-shared

`(write-shared obj)`

Like `write`, but every pair or vector that occurs more than once in `obj` is written with a datum label, not only those on a cycle. There is no port argument.

```scheme
(define x (list 1 2))
(write-shared (list x x))
(newline)
```

```
(#0=(1 2) #0#)
```
