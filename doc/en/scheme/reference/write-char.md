# write-char

`(write-char char [port])`

Writes the character `char` (not its `#\` notation) to the current output port. With `port`, it writes there instead; `port` must be an open textual output port.

```scheme
(write-char #\a)
(write-char #\b)
(newline)
```

```
ab
```

```scheme
(let ((p (open-output-string))) (write-char #\a p) (get-output-string p)) ; => "a"
```
