# read-line

`(read-line)`

Reads the rest of the current line from the current input port and returns it as a string without the line terminator; the end-of-file object when no characters remain. There is no port argument.

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
