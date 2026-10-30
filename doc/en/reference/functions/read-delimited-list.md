# read-delimited-list

`(read-delimited-list char &optional stream recursive-p)`

Reads datums from `stream` (standard input when omitted) up to `char`, which it consumes, and returns them as a list. `char` is usually a terminating macro character, so a token ends before it. Works on all four backends; on the compiled backends a token ends only at a standard terminating character or whitespace.

Only the interpreter's reader reads with a readtable so far: a program and the files it loads, read form by form, `read-from-string`, and `read` / `read-delimited-list` on a string stream. The compile paths and the compiled backends' run-time readers read the standard syntax ([readtables](../../guides/readtables.md)).

```lisp
(read-delimited-list #\) (make-string-input-stream "1 (2) x)")) ; => (1 (2) X)
```
