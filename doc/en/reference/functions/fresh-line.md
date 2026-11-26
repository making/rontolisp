# fresh-line

`(fresh-line &optional stream)`

Writes a newline to `stream` (standard output by default) only if it is not already at the start of a line, so it never produces a blank line where the stream is at column zero. Returns `t` when it wrote the newline, nil when it did not. Use it to guarantee subsequent output begins on its own line without risking a double newline.

```lisp
(princ "a")
(fresh-line)
(princ "b")
```

```
a
b
```

A stream starts at the start of a line, whatever it is opened on: a file appended to answers nil to its first `fresh-line` even if the file ends in the middle of one. Writing characters moves it; `write-byte`, reading and `file-position` do not, so a binary stream is always at the start of a line.
