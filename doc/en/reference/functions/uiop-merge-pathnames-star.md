# uiop:merge-pathnames*

`(uiop:merge-pathnames* specified &optional defaults)`

Merges the `specified` path onto `defaults` and returns the result -- UIOP's
defaults-aware variant of `merge-pathnames`, and the one portable libraries call
to build a path relative to a data directory. Both arguments take either
spelling (a pathname or a namestring); the result is a pathname.

```lisp
(uiop:merge-pathnames* "b.txt" "/tmp/")   ; => #P"/tmp/b.txt"
```

Omitting `defaults` merges against `""` (the namestring designating the working
directory), which leaves `specified` unchanged -- the same answer
`uiop::get-pathname-defaults` gives.

## Backend support

Works on all four backends: it is a Lisp-source definition over
[`merge-pathnames`](merge-pathnames.md), compiled into the program when used.
The compile paths additionally **fold to a literal** every call whose arguments
they can resolve at compile time -- a string literal, or a reference to a
top-level `defparameter` bound to one -- which is what makes
`(uiop:merge-pathnames* *data-directory* "UnicodeData.txt")` in a bundled library
cost nothing at run time.
