# read-line

`(read-line [port])`

Reads the rest of the current line from the current input port and returns it as a string without the line terminator; the end-of-file object when no characters remain. With `port`, it reads that port instead; `port` must be an open textual input port.

```stdin
first line
second line
```

```scheme
(write (read-line))
(newline)
(write (read-line))
(newline)
(write (eof-object? (read-line)))
(newline)
```

```
"first line"
"second line"
#t
```

```scheme
(read-line (open-input-string "first\nsecond")) ; => "first"
```
