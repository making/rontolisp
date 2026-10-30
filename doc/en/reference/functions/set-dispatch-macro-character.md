# set-dispatch-macro-character

`(set-dispatch-macro-character disp-char sub-char function &optional (readtable *readtable*))`

Makes `function` the reader function of `sub-char` (case-insensitive) under the dispatch macro character `disp-char`, and returns `t`. When the reader meets `disp-char`, an optional decimal argument and `sub-char`, it calls `(function stream sub-char argument)` -- `argument` is `nil` without digits -- and the value the function returns is the datum. A decimal digit as `sub-char` signals. Works on all four backends.

Source is read with the current readtable on every backend: the interpreter reads a program and the files it loads form by form, and the compile paths read each form with the readtable a compile-time `eval-when` left. At run time, `read-from-string` and `read` / `read-delimited-list` on a string stream honor it on the interpreter; the compiled backends' run-time readers read the standard syntax ([readtables](../../guides/readtables.md)).

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-dispatch-macro-character #\# #\? (lambda (s c n) (list :q n (read s t nil t))))
  (read-from-string "#2?foo"))
; => (:Q 2 FOO)
```

Two dispatch syntaxes are built into the standard readtable: `#N@(...)` (ironclad's s-box literal, read as a vector of the given element width) and `#L(...)` (iterate's numbered-argument lambda: `#L(list !2 !3)` reads as `#'(lambda (!1 !2 !3) (list !2 !3))`, the arity being the highest `!n` the body mentions unless `#nL` spells it out).
