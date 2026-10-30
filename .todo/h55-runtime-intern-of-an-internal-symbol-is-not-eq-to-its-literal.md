# h55. A runtime `intern`/`find-symbol` of an INTERNAL symbol is not `eq` to its literal on the compile paths

Difficulty: Medium

Found 2026-10-30 while pinning `slot-unbound` (h41): an `(eql 'data)` method in package
`susm` missed `(slot-value obj (intern "DATA" :susm))` on the JVM and both WASM backends.

```lisp
(defpackage :susm (:use :cl) (:export #:pub))
(in-package :susm)
(defvar *a* (list 'data (intern "DATA" :susm) 'pub (intern "PUB" :susm)))
(in-package :cl-user)
(print susm::*a*)
(print (list (eq (first susm::*a*) (second susm::*a*)) (eq (third susm::*a*) (fourth susm::*a*))))
; SBCL, interpreter: (SUSM::DATA SUSM::DATA SUSM:PUB SUSM:PUB) (T T)
; JVM, P1, component: (SUSM::DATA SUSM:DATA SUSM:PUB SUSM:PUB) (NIL T)
```

The compiled runtime builds the single-colon EXTERNAL spelling for every package-qualified
name (`.kb/symbol-runtime-api.md`, "Computed `find-symbol`" and the `_lookup` alias rows),
so an internal symbol built at run time is a different symbol from the one the source spells.
1-arg `intern` in a non-`cl-user` package is package-blind there too (`(symbol-package
(intern "DATA"))` answers `:CL-USER`). Related: `.todo/254`, `.todo/156` (symbol identity).

Fix: build `PKG::NAME` when the package does not export NAME (the compile-time export table
already knows; a computed `export` is `.todo/386`), on all four backends, with a ci-spec case.
