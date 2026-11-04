# i29. A quoted #S literal under a package does not find its structure

Difficulty: Low

Measured 2026-11-04, interpreter and JVM, in any package but `cl-user`:

```lisp
(defpackage :p (:use :cl))
(in-package :p)
(defstruct pt x)
(print (pt-x '#S(pt :x 4)))   ; SBCL 4
```

`error: #S(PT ...): PT is not a defined structure type`. The unquoted `#S(pt :x 4)` works:
`PackageResolver.resolveForm` resolves a literal's type name (`resolveStructLiteralType`), and
`resolveQuotedData` leaves a literal alone.
