# fresh-line

`(fresh-line)`

Writes a newline to standard output only if output is not already at the start of a fresh line, so it never produces a blank line where the cursor is already at column zero. Returns nil. Use it to guarantee subsequent output begins on its own line without risking a double newline.

```lisp
(princ "a")
(fresh-line)
(princ "b")
```

```
a
b
```
