# i30. A cl symbol prints bare where the package reads that name as another symbol

Difficulty: Medium

Measured 2026-11-04, interpreter; every backend prints the same from what `.kb/pretty-printer.md` bakes:

```lisp
(defpackage :q (:use))
(defpackage :s (:use :cl) (:shadow #:car))
(cl:in-package :q)
(cl:print (cl:list 'car 'cl:car cl:nil))   ; SBCL (CAR COMMON-LISP:CAR COMMON-LISP:NIL)
(cl:in-package :s)
(print (list 'car 'cl:car))                ; SBCL (CAR COMMON-LISP:CAR)
```

rontolisp prints `(CAR CAR NIL)` and `(CAR CAR)`, so two different symbols print alike and neither reads
back as the other. `PackageResolver.printsBare` answers true for any name without a qualifier. Since
`.todo/h97` a package using no `cl` reaches this for every standard name. The fix has to reach the compile
paths' `SymbolPrintTable`, which holds qualified names only, and it needs a decision on the package name
to print: `(package-name :cl)` here is `CL`, SBCL's is `COMMON-LISP`.
