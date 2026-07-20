# read

`(read &optional stream)`

Reads and parses a single S-expression. With no argument it reads from standard input; given a stream opened by `open` or `with-open-file` it reads from that stream. Blank and comment-only lines are skipped, the datum must fit on a single line, and EOF returns `nil`. Works in all three backends: the interpreter uses the full Lisp reader, the JVM emits a reader with full JDK parity, and the WASM reader is narrower (31-bit integers, decimal floats, no big integers or exponents).

```console
(print (read))
```

Reading the line `(+ 1 2)` from standard input parses it into the list `(+ 1 2)`, which `print` then echoes back. At end of input `read` returns `nil`. Symbols read at run time follow the reader's [upcasing](../../guides/reader-case.md) -- your symbols and the standard names alike upcase (there is no fold) -- identically on every backend.
