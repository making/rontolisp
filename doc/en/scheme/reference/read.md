# read

`(read [port])`

Reads the next datum from the current input port and returns it -- a symbol, number, string, character, list, vector, or a quoted form -- or the end-of-file object at the end of input. With `port`, it reads that port instead; `port` must be an open textual input port. The same syntax the reader refuses in source is refused here: `[`/`]`/`{`/`}`. `|...|` identifiers and `+inf.0`/`-inf.0`/`+nan.0` are read. A `#u8(...)` bytevector is read. An incomplete or malformed datum raises an error that `read-error?` answers `#t` for.

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

```scheme
(read (open-input-string "(a . b) c")) ; => (a . b)
(read (open-input-string "|a b| +inf.0")) ; => |a b|
```
