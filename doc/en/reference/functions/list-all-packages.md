# list-all-packages

`(list-all-packages)`

Every registered package, as the keywords [`find-package`](find-package.md) answers (rontolisp has no package objects). The list covers the built-in packages, the `keyword` pseudo-package and every [`defpackage`](../special-forms/defpackage.md) in the program.

The interpreter reads its live registry. The compiled backends have no registry at run time and answer from a table baked in at compile time, so a package a compiled program creates later is invisible there.

```lisp
(defpackage #:listed (:use #:cl))
(car (member :listed (list-all-packages))) ; => :LISTED
```
