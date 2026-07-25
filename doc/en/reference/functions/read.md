# read

`(read &optional stream)`

Reads and parses a single S-expression. With no argument it reads from standard input; given a stream opened by `open` or `with-open-file` it reads from that stream. Blank and comment-only lines are skipped, the datum must fit on a single line, and EOF returns `nil`. The compiled backends emit a runtime reader with frontend parity: lists (dotted pairs included), `'`, `#'`, strings, symbols, numbers (ratios and `#x`/`#o`/`#b` radix integers included), `#\` character literals, `#(...)`/`#nA(...)` arrays, `#*` bit vectors, `#f(`/`#d(` packed float arrays, `#S(...)` structure literals and `#|...|#` block comments. `#.`, `#+`/`#-` and `#n=`/`#n#` need an evaluator or the feature set at read time, so the compiled reader signals a catchable error on them (the interpreter still resolves them). The WASM reader's numbers are narrower (31-bit integers, decimal floats without exponents, static error messages) -- see [Compiled read/load Limitations](../../guides/read-load-limitations.md).

```console
(print (read))
```

Reading the line `(+ 1 2)` from standard input parses it into the list `(+ 1 2)`, which `print` then echoes back. At end of input `read` returns `nil`. Symbols read at run time follow the reader's [upcasing](../../guides/reader-case.md) -- your symbols and the standard names alike upcase (there is no fold) -- identically on every backend.
