# read-char

`(read-char)`

Reads the next character from the current input port and returns it, or the end-of-file object when the input is exhausted. There is no port argument: only the current input port (standard input) is supported.

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
