# flush-output-port

`(flush-output-port [port])`

Writes out anything the output port `port` (the current output port by default) has buffered. A string or bytevector port has nothing to write out.

```scheme
(display "before")
(flush-output-port)
(newline)
```

```
before
```
