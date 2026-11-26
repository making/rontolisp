# write-simple

`(write-simple obj [port])`

Like `write`, but writes shared structure out each time it occurs. It is the same as `write` on data without cycles; it does not detect cycles. With `port`, it writes there instead; `port` must be an open textual output port.

```scheme
(define x (list 1 2))
(write-simple (list x x))
(newline)
```

```
((1 2) (1 2))
```

```scheme
(let ((p (open-output-string)) (x (list 1 2))) (write-simple (list x x) p) (get-output-string p)) ; => "((1 2) (1 2))"
```
