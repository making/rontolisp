# d23. An export of an inherited cl symbol mints the package's own symbol

Difficulty: Medium

`(:export #:car)` in a package that uses `cl` should re-export `cl`'s `CAR` (CLHS 11.1.1.2:
the symbol accessible under the name is exported). On all four backends it mints a new
symbol instead, while `'car` read in a package using it still resolves to `cl`'s:

```lisp
(defpackage :rx (:use :cl) (:export #:car))
(defpackage :ru (:use :rx))
(print (eq 'rx:car 'car))                                  ; SBCL T          / all four NIL
(print (multiple-value-list (find-symbol "CAR" :rx)))      ; (CAR :EXTERNAL) / (RX:CAR :EXTERNAL)
(print (multiple-value-list (find-symbol "CAR" :ru)))      ; (CAR :INHERITED) / (RX:CAR :INHERITED)
```

`PackageResolver.resolveDefpackage` records an exported inherited name as an import redirect
only when it "is not a `cl` symbol" (`.kb/packages.md`, "Resolution order and imports"), so a
re-exported standard name has no redirect and `rx:car` spells `RX:CAR`. Decide what the
exception protected before removing it; the compiled rows already carry a standard name a
used package re-exports to a package without `cl` (`.kb/packages.md`, "The standard names at
run time"), which this would start exercising.
