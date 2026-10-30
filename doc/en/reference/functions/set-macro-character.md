# set-macro-character

`(set-macro-character char function &optional non-terminating-p (readtable *readtable*))`

Makes `char` a macro character whose reader calls `(function stream char)` and returns `t`. The function's value is the datum; a function returning no values (`(values)`) makes `char` start a comment. `non-terminating-p` true lets `char` appear inside a token. Installing a standard macro function -- `(get-macro-character #\))` under `#\]` -- makes the character read as that standard character does. Works on all four backends.

Only the interpreter's reader reads with a readtable so far: a program and the files it loads, read form by form, `read-from-string`, and `read` / `read-delimited-list` on a string stream. The compile paths and the compiled backends' run-time readers read the standard syntax ([readtables](../../guides/readtables.md)).

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-macro-character #\] (get-macro-character #\)))
  (set-macro-character #\[ (lambda (s c) (cons 'vector (read-delimited-list #\] s t))))
  (read-from-string "[1 2 (+ 1 2)]"))
; => (VECTOR 1 2 (+ 1 2))
```
