# peek-char

`(peek-char [port])`

Returns the next character of the current input port without consuming it, so the following `read-char` returns the same character; the end-of-file object at the end of input. With `port`, it reads that port instead; `port` must be an open textual input port.

```stdin
xy
```

```scheme
(write (peek-char))
(write (peek-char))
(write (read-char))
(write (read-char))
(newline)
```

```
#\x#\x#\x#\y
```

```scheme
(let ((p (open-input-string "xy"))) (list (peek-char p) (read-char p) (read-char p))) ; => (#\x #\x #\y)
```
