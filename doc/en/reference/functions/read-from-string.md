# read-from-string

`(read-from-string string)`

Parses and returns one datum from the given string. It reuses the same reader as [`read`](read.md), so on the compiled backends it accepts the same frontend-parity syntax (`#S(...)`, `#(...)`, `#\a`, ratios, radix integers, ... -- with `#.`, `#+`/`#-` and reader labels signaling; the WASM reader's numbers carry integers of any magnitude but no float exponents), and `(read-from-string (prin1-to-string x))` round-trips. The interpreter evaluates a `#.` datum in place (binding `*read-eval*` to `nil` makes it signal, per the standard). An input holding no datum signals `end-of-file`; a malformed datum signals `reader-error`. Both carry a string-input stream over the offending text (`stream-error-stream`); on the compiled backends the same failures signal a catchable `simple-error`. The optional `eof-error-p`/`eof-value` and the `:start`/`:end` keyword arguments are not supported -- only the single string argument is accepted. Works in all three backends and is usable as a first-class value (`#'read-from-string`).

```lisp
(read-from-string "(+ 1 2)") ; => (+ 1 2)
```

The result is the parsed list `(+ 1 2)` as data, not its evaluation; pass it to `eval` if you want the value `3`.

Symbols are read with the reader's [upcasing](../../guides/reader-case.md), identically on every backend: your symbols and the standard names alike read upcased (there is no fold to a lowercase spelling).

```lisp
(read-from-string "foo") ; => FOO
```


## The stop index, and `*read-suppress*`

Like Common Lisp's, `read-from-string` answers a SECOND value: the index of the first character it did not read. A whitespace terminator is consumed with the datum it terminates -- a list or a character literal exactly like a token -- and a terminating macro character is given back, so `"abc  def"` stops at 4 and `"(1 2) x"` at 6 (past the space after the `)`). Ask for it with a [multiple-value](../macros/multiple-value-bind.md) consumer; a caller that wants only the datum pays nothing for it. The function object `#'read-from-string` answers the same two values.

```lisp
(multiple-value-list (read-from-string "abc")) ; => (ABC 3)
(multiple-value-list (read-from-string "abc  def")) ; => (ABC 4)
(nth-value 1 (read-from-string "(1 2) x")) ; => 6
```

Binding `*read-suppress*` to true makes the reader consume exactly the characters a real read would and answer `nil`, suppressing every error the datum would otherwise signal -- an unknown package, a bogus character name, an out-of-range digit. This is what a `#+`/`#-` guard does to the form it skips, and it is the interpreter's behavior: the runtime reader of compiled output has no suppressed mode.

```lisp
(let ((*read-suppress* t)) (read-from-string "nonexistent-package::foo")) ; => NIL
(let ((*read-suppress* t)) (multiple-value-list (read-from-string "123.45"))) ; => (NIL 6)
```
