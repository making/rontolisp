# set-syntax-from-char

`(set-syntax-from-char to-char from-char &optional (to-readtable *readtable*) from-readtable)`

Gives `to-char` in `to-readtable` the syntax `from-char` has in `from-readtable` (default: the standard readtable) -- whitespace, constituent, or a macro character with its function and dispatch table -- and returns `t`. Works on all four backends.

Source is read with the current readtable on every backend: the interpreter reads a program and the files it loads form by form, and the compile paths read each form with the readtable a compile-time `eval-when` left. At run time, `read-from-string` and `read` / `read-delimited-list` on a string stream honor it on the interpreter; the compiled backends' run-time readers read the standard syntax ([readtables](../../guides/readtables.md)).

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-syntax-from-char #\} #\))
  (read-delimited-list #\} (make-string-input-stream "a b}")))
; => (A B)
```
