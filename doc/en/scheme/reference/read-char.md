# read-char

`(read-char [port])`

Reads the next character from the current input port and returns it, or the end-of-file object when the input is exhausted. With `port`, it reads that port instead; `port` must be an open textual input port.

```stdin
ab
```

```scheme
(write (read-char))
(write (read-char))
(newline)
```

```
#\a#\b
```

```scheme
(read-char (open-input-string "xy")) ; => #\x
```
