# read-preserving-whitespace

`(read-preserving-whitespace &optional stream eof-error-p eof-value recursive-p)`

The same as [`read`](read.md), except that a whitespace character ending the datum stays in the stream, for a caller that needs to know what followed it.

```lisp
(with-input-from-string (s "12 34")
  (list (read-preserving-whitespace s) (read-char s) (read s)))
; => (12 #\Space 34)
```
