# read-delimited-list

`(read-delimited-list char &optional stream recursive-p)`

Reads datums from `stream` (standard input when omitted) up to `char`, which it consumes, and returns them as a list. `char` is usually a terminating macro character, so a token ends before it. Works on all four backends; on the compiled backends a token ends only at a standard terminating character or whitespace.

Source is read with the current readtable on every backend: the interpreter reads a program and the files it loads form by form, and the compile paths read each form with the readtable a compile-time `eval-when` left. At run time, `read-from-string` and `read` / `read-delimited-list` on a string stream honor it on the interpreter; the compiled backends' run-time readers read the standard syntax ([readtables](../../guides/readtables.md)).

```lisp
(read-delimited-list #\) (make-string-input-stream "1 (2) x)")) ; => (1 (2) X)
```
