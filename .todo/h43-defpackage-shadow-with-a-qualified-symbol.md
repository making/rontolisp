# h43. `(:shadow cl:let)` does not shadow: a symbol designator is taken as the symbol itself

Difficulty: Low

Found 2026-10-30 probing `fset`. Its dependency `misc-extensions` (`src/defs.lisp:11`) writes
`(:shadow cl:let cl:cond)` and then defines its own `let`/`cond` macros:
`DEFMACRO cannot redefine the standard operator LET`.

```lisp
(defpackage :pp (:use :cl) (:shadow cl:let))
(in-package :pp)
(defmacro let (b &body body) `(cl:let ,b (list :mine (progn ,@body))))
(let ((x 1)) x)   ; SBCL: (:MINE 1)    with (:shadow #:let) it already works here
```

`:shadow` takes string designators, so only the NAME of `cl:let` counts. Check
`:shadowing-import-from`, `:export`, `:intern` and `shadow`/`export` the same way
(`.kb/packages.md`). Add the failing case first.
