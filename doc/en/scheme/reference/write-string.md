# write-string

`(write-string string)`

Writes the characters of `string` (without quotes) to the current output port. There is no port argument, and the optional `start`/`end` arguments of R7RS are not accepted.

```scheme
(write-string "hello")
(newline)
```

```
hello
```
