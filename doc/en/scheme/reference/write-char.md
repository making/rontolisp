# write-char

`(write-char char)`

Writes the character `char` (not its `#\` notation) to the current output port. There is no port argument.

```scheme
(write-char #\a)
(write-char #\b)
(newline)
```

```
ab
```
