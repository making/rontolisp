# make-dispatch-macro-character

`(make-dispatch-macro-character char &optional non-terminating-p (readtable *readtable*))`

Makes `char` a dispatch macro character with an empty dispatch table, and returns `t`; [`set-dispatch-macro-character`](set-dispatch-macro-character.md) then fills it. `non-terminating-p` true lets `char` appear inside a token. Works on all four backends.

Source is read with the current readtable on every backend: the interpreter reads a program and the files it loads form by form, and the compile paths read each form with the readtable a compile-time `eval-when` left. At run time, `read-from-string` and `read` / `read-delimited-list` on a string stream honor it on the interpreter; the compiled backends' run-time readers read the standard syntax ([readtables](../../guides/readtables.md)).

```lisp
(let ((*readtable* (copy-readtable nil)))
  (make-dispatch-macro-character #\!)
  (set-dispatch-macro-character #\! #\v (lambda (s c n) (vector (read s t nil t))))
  (read-from-string "!v42"))
; => #(42)
```
