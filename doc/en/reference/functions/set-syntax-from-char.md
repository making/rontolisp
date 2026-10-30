# set-syntax-from-char

`(set-syntax-from-char to-char from-char &optional (to-readtable *readtable*) from-readtable)`

Gives `to-char` in `to-readtable` the syntax `from-char` has in `from-readtable` (default: the standard readtable) -- whitespace, constituent, or a macro character with its function and dispatch table -- and returns `t`. Works on all four backends.

Only the interpreter's reader reads with a readtable so far: a program and the files it loads, read form by form, `read-from-string`, and `read` / `read-delimited-list` on a string stream. The compile paths and the compiled backends' run-time readers read the standard syntax ([readtables](../../guides/readtables.md)).

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-syntax-from-char #\} #\))
  (read-delimited-list #\} (make-string-input-stream "a b}")))
; => (A B)
```
