# terpri

`(terpri &optional stream)`

Writes a single newline unconditionally and returns nil. With no argument it writes to standard output; with the optional stream argument (a file stream or a `with-output-to-string` string stream) it writes to that stream. The name is short for "terminate print"; use it to end a line built up with `princ` or `prin1`.

```lisp
(princ "a")
(terpri)
(princ "b")
```

```
a
b
```
