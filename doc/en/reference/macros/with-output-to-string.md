# with-output-to-string

`(with-output-to-string (stream) body...)`

Binds `stream` to a string output stream, evaluates the body forms, and returns everything written to the stream as a string. `princ`, `prin1`, `print`, `terpri`, `write-line` and `write-string` accept the stream as their optional stream argument, and `format` accepts it as the destination; each call appends to the stream. Works in all three backends.

```lisp
(with-output-to-string (s)
  (princ "1 + 2 = " s)
  (princ (+ 1 2) s)) ; => "1 + 2 = 3"
```
