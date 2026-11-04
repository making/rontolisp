# h97. A package not using CL rejects CL-named symbols in code

Difficulty: High

Measured 2026-11-04, interpreter and JVM (`PackageResolver`, the throw at
`Undefined symbol: NAME (use CL:NAME)`). In ANSI CL such a name is an ordinary new symbol of that
package. The resolver allows it only in quoted data.

```lisp
(defpackage :nocl (:use))
(cl:in-package :nocl)
(cl:print (cl:let ((car 1)) car))              ; SBCL 1
(cl:defun f (second) second)                   ; SBCL fine
(cl:defun second (x) x)                        ; SBCL fine
(cl:print (cl:loop while cl:nil))              ; SBCL NIL: LOOP keywords compare by name
(cl:print (cl:loop for x in '(1) when x collect x))
```

Every line stops with `Undefined symbol: ...`, including `WHILE`, which is not a CL symbol at all.

Consumers:
- McCLIM: `clim-lisp` is `(:use)` with explicit imports, and `clim-internals` reaches CL only through
  it. The load stops with `Undefined symbol: WHILE` and no position, so the form is not located.
  What McCLIM needs after this is unmeasured.
- log4cl: its `log` package is `(:import-from :cl #:in-package)` with no `:use`. After walking past
  `.todo/i03` the load stops with `Undefined symbol: SECOND` and no position. The only `second` in
  the files that load is in `forward-macro` (`src/package.lisp:133`), so confirm this root cause
  after the fix. log4cl stops sento.
- `.todo/h79`'s `asdf` package has the same symptom with a different cause (its use list).

The rejection exists to report a likely typo. Keep the message for the case that really is one,
and let binding positions, `loop` keywords and a package's own definitions through. On the compile
paths the resolver runs before the macro expansion, which is where a LOOP keyword gets its meaning.
Decide that order first; a blanket "it is just a new symbol" makes `(car x)` in such a package an
undefined-function error at the call, which is what ANSI says.
