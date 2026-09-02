# read

`(read &optional stream eof-error-p eof-value recursive-p)`

Reads and parses a single S-expression. With no argument it reads from standard input; given a stream opened by `open`, `with-open-file` or `with-input-from-string` it reads from that stream. It consumes **exactly the characters of one datum** and leaves the stream positioned after them, so a second datum on the same line survives, a datum may span lines, and `read` mixes with `read-line` and `read-char` on the same stream. Whitespace and comments before the datum are skipped, and one whitespace character after it is consumed (the standard allows this); a terminating character such as `)` is left in the stream. At end of input `read` returns `eof-value` (`nil` by default) unless `eof-error-p` is non-nil, in which case it signals `end-of-file`. An incomplete datum signals. The compiled backends emit a runtime reader with frontend parity: lists (dotted pairs included), `'`, `#'`, strings, symbols, numbers (ratios and `#x`/`#o`/`#b` radix integers included), `#\` character literals, `#(...)`/`#nA(...)` arrays, `#*` bit vectors, `#f(`/`#d(` packed float arrays, `#S(...)` structure literals and `#|...|#` block comments. `#.`, `#+`/`#-` and `#n=`/`#n#` need an evaluator or the feature set at read time, so the compiled reader signals a catchable error on them (the interpreter still resolves them; binding `*read-eval*` to `nil` there makes `#.` signal instead, per the standard). The WASM reader's numbers are narrower (64-bit integers, decimal floats without exponents, static error messages) -- see [Compiled read/load Limitations](../../guides/read-load-limitations.md).

```lisp
(with-input-from-string (s "1 2 (a b)")
  (list (read s) (read s) (read s)))
; => (1 2 (A B))
```

With no stream argument, `read` parses one datum from standard input: reading `(+ 1 2)` there yields the list `(+ 1 2)`. At end of input `read` returns `nil`. Symbols read at run time follow the reader's [upcasing](../../guides/reader-case.md) -- your symbols and the standard names alike upcase (there is no fold) -- identically on every backend.
