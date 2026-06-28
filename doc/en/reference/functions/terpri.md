# terpri

`(terpri)`

Writes a single newline to standard output unconditionally and returns nil. The name is short for "terminate print"; use it to end a line built up with `princ` or `prin1`.

```lisp
(princ "a")
(terpri)
(princ "b")
```

```
a
b
```
