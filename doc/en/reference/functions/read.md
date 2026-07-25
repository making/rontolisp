# read

`(read &optional stream)`

Reads and parses a single S-expression. With no argument it reads from standard input; given a stream opened by `open` or `with-open-file` it reads from that stream. Blank and comment-only lines are skipped, the datum must fit on a single line, and EOF returns `nil`. Works in all three backends, but not with the same syntax: the interpreter uses the full Lisp reader, while the compiled backends emit a NARROWER one that understands lists (dotted pairs included), `'`, `#'`, strings, symbols and numbers -- and nothing else. A `#`-dispatch form the interpreter reads (`#S(...)`, `#(...)`, `#\a`, `#nA(...)`, `#x10`) is not understood there: it reads as an ordinary symbol and the rest of the text is read as whatever follows it. Within that subset the JVM reader has full JDK parity and the WASM reader is narrower still (31-bit integers, decimal floats, no big integers or exponents).

```console
(print (read))
```

Reading the line `(+ 1 2)` from standard input parses it into the list `(+ 1 2)`, which `print` then echoes back. At end of input `read` returns `nil`. Symbols read at run time follow the reader's [upcasing](../../guides/reader-case.md) -- your symbols and the standard names alike upcase (there is no fold) -- identically on every backend.
