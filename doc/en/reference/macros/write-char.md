# write-char

`(write-char character &optional stream)`

Writes a single character to standard output (or to the given stream), returning the character. Expands to [`write-string`](../functions/write-string.md) of the one-character string on every backend, so it works wherever string output does — including file and string streams. Classified as a macro (no function value), like `format`.

```lisp
(write-char #\o)
(write-char #\k)
(terpri)
```

```
ok
```
