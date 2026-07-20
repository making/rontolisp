# read-from-string

`(read-from-string string)`

Parses and returns one datum from the given string. It reuses the same reader as `read`, so on the compiled backends it accepts the same value kinds (the WASM reader being the narrowest). The optional `eof-error-p`/`eof-value` and the `:start`/`:end` keyword arguments are not supported -- only the single string argument is accepted. Works in all three backends and is usable as a first-class value (`#'read-from-string`).

```lisp
(read-from-string "(+ 1 2)") ; => (+ 1 2)
```

The result is the parsed list `(+ 1 2)` as data, not its evaluation; pass it to `eval` if you want the value `3`.

Symbols are read with the reader's [upcase premise](../../guides/reader-case.md), identically on every backend: your symbols read upcased and standard names fold to their canonical spelling.

```lisp
(read-from-string "foo") ; => FOO
```
