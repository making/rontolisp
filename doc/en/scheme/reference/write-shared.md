# write-shared

`(write-shared obj [port])`

Like `write`, but every pair or vector that occurs more than once in `obj` is written with a datum label, not only those on a cycle. With `port`, it writes there instead; `port` must be an open textual output port.

```scheme
(define x (list 1 2))
(write-shared (list x x))
(newline)
```

```
(#0=(1 2) #0#)
```

```scheme
(let ((p (open-output-string)) (x (list 1 2))) (write-shared (list x x) p) (get-output-string p)) ; => "(#0=(1 2) #0#)"
```
