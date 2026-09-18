# peek-char

`(peek-char)`

Returns the next character of the current input port without consuming it, so the following `read-char` returns the same character; the end-of-file object at the end of input. There is no port argument.

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
