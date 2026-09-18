# read

`(read)`

Reads the next datum from the current input port and returns it -- a symbol, number, string, character, list, vector, or a quoted form -- or the end-of-file object at the end of input. There is no port argument. The same syntax the reader refuses in source is refused here: `|...|` identifiers, `+inf.0`/`+nan.0`, bytevectors, and `[`/`]`/`{`/`}`. An incomplete datum is an error.

```stdin
(1 "two" #\3) foo
```

```scheme
(write (read))
(newline)
(write (read))
(newline)
(write (read))
(newline)
```

```
(1 "two" #\3)
foo
#<eof>
```
