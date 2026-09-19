# file-error-pathname

`(file-error-pathname condition)`

The pathname a `file-error` condition carries -- the designator the failed operation was given, as given (rontolisp makes nothing absolute). [`open`](open.md), [`delete-file`](delete-file.md), [`rename-file`](rename-file.md) and [`truename`](truename.md) signal `file-error` on all four backends.

```lisp
(handler-case (delete-file "fep-missing/notes.txt")
  (file-error (e) (namestring (file-error-pathname e)))) ; => "fep-missing/notes.txt"
```
