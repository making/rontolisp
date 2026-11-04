# i28. define-symbol-macro under a package does not expand on the compile paths

Difficulty: Medium

Measured 2026-11-04, in any package but `cl-user`:

```lisp
(defpackage :p (:use :cl))
(in-package :p)
(define-symbol-macro sm 5)
(print sm)
```

The interpreter prints 5. The JVM refuses with `Cannot compile symbol reference: P::SM`, P1 and the
component with `Cannot compile symbol: P::SM`.

Unverified cause: `UserMacroExpander` keeps a form its walk did not change in its original spelling, so
`substituteGlobalSymbolMacros` meets `SM` where the definition registered `P::SM`.
