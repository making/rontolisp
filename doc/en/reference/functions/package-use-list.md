# package-use-list

`(package-use-list package)`

The packages `package` uses, as the keywords [`find-package`](find-package.md) answers (rontolisp has no package objects). The argument is any package designator — a keyword, a string, a symbol, or a package value; an unknown one signals. [`package-used-by-list`](package-used-by-list.md) is the inverse.

The interpreter reads its live registry. The compiled backends have no registry at run time and answer from a table baked in at compile time, so a package a compiled program creates later is invisible there.

```lisp
(defpackage #:uses-cl (:use #:cl))
(package-use-list '#:uses-cl) ; => (:CL)
```
