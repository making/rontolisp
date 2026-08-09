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

The interpreter has it as an ordinary runtime function. On the JVM and both WASM
backends only the calls the compiler can **fold to a literal** survive: an
argument that is a string literal, or a reference to a top-level
`defparameter` bound to one, is merged at compile time (this is what resolves
`(uiop:merge-pathnames* *data-directory* "UnicodeData.txt")` in a bundled
library). A call whose arguments are only known at run time signals an
undefined-function error there.
