# Interpreter `type-of` double-qualifies a class name from another package

Difficulty: Low

Found in `.todo/372`'s spike (rove prints `(type-of (assertion-reason f))` in
its failure report). Cross-backend divergence:

```lisp
(defpackage #:lib (:use #:cl) (:export #:widget #:make-w))
(in-package #:lib)
(defclass widget () ())
(defclass gadget () ())
(defun make-w () (make-instance 'widget))
(defun make-g () (make-instance 'gadget))
(in-package #:app)
(print (type-of (lib:make-w)))              ; interpreter: APP::LIB:WIDGET    JVM: LIB:WIDGET
(print (type-of (lib::make-g)))             ; interpreter: APP::LIB::GADGET   JVM: LIB::GADGET
(print (eq (type-of (lib:make-w)) 'lib:widget))   ; interpreter: NIL   JVM: T
(print (class-name (class-of (lib:make-w))))      ; LIB:WIDGET on both
```

`type-of` is the prelude defun over `%class-designator` that strips the
`%class-` tag prefix and interns the remainder (`.kb/symbol-runtime-api.md`);
the interpreter's 1-argument `intern` homes an unknown name into the CURRENT
package verbatim (`PackageResolver.internSpelling`), and the remainder is
already a canonical `PKG:NAME` spelling, so it comes back as
`APP::LIB:WIDGET`. The compile paths' `intern` is package-blind and keeps the
spelling. `class-name` takes the other route and is right everywhere.

Fix: an already-qualified canonical spelling is a symbol by that spelling on
the interpreter too (`internSpelling` recognizes a qualifier the registry
knows, or `type-of` uses the registry-backed symbol constructor `class-name`
uses). Pin `(eq (type-of x) 'pkg:class)` on all four backends (per-backend
suites + `ci-spec.yaml`); `.kb/symbol-runtime-api.md` `type-of` bullet.
