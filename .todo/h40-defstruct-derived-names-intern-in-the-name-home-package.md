# h40. `defstruct` interns derived names in the struct name's HOME package, not `*package*`

Difficulty: Medium

Found 2026-10-30 probing `optima` (`(optima:match ...)` fails with
`The function OPTIMA::AND-PATTERN-P is undefined`). optima's `pattern.lisp` is
`(in-package :optima)` and defines `(defstruct (and-pattern ...))`, where `AND-PATTERN` is
inherited from `optima.core`. CLHS: the predicate, copier, default constructor and
accessors are interned in the package current when `defstruct` is expanded.

```lisp
(defpackage :pcore (:use :cl) (:export #:ap #:make-ap #:ap-subs))
(defpackage :pmain (:use :cl :pcore)) (in-package :pmain)
(defstruct ap subs)
(list (fboundp 'pmain::ap-p) (fboundp 'pcore::ap-p) (fboundp 'copy-ap))
;; SBCL: (#<FUNCTION AP-P> NIL #<FUNCTION COPY-AP>)   here: (NIL T NIL)
```

Bug-fix protocol: add the failing case (ci-spec) first. Check `:conc-name` and `:include`
prefixes the same way, on all four backends (`.kb/defstruct.md`).
