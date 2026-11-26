# newline

`(newline [port])`

Writes an end of line to the current output port. With `port`, it writes there instead; `port` must be an open textual output port.

```scheme
(display "one")
(newline)
(display "two")
(newline)
```

```
one
two
```

```scheme
(let ((p (open-output-string))) (display "a" p) (newline p) (get-output-string p)) ; => "a\n"
```
