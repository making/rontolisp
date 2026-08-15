# `%mop-make-instance` of an interned, non-exported class name fails on the compile paths

Difficulty: Low

Part of `.todo/372` (rove, row 14); the `.todo/254` spelling family.

```lisp
(defpackage #:mp (:use #:cl))
(in-package #:mp)
(defclass spec-rep () ((stream :initarg :stream :accessor rep-stream)))
(make-instance (intern (format nil "~A-~A" :spec '#:rep) (find-package :mp)) :stream s)
;; interpreter: an instance
;; JVM/WASM:    %MOP-MAKE-INSTANCE: not an instantiable class: SPEC-REP
```

The compile-path `intern` builds the canonical spelling with the single-colon
EXTERNAL qualifier (`MP:SPEC-REP`, the documented deviation of
`.kb/symbol-runtime-api.md`) while the class is registered under
`MP::SPEC-REP`; `%mop-make-instance` matches by spelling and misses. Rove's
`make-reporter` is exactly this call -- `(make-instance (intern (format nil
"~A-~A" style '#:reporter) package) :stream stream)` -- and works only because
rove EXPORTS `spec-reporter`/`dot-reporter`; a user reporter class in an
un-exporting package, or any library that names a class this way, hits it.

Fix at the lookup: `%mop-make-instance` (and `find-class`/`%find-class`,
`change-class`'s runtime arm if any) resolves a designator by package + base
name, `:` and `::` alike, before the unique-base-name fallback `ClosRegistry.findClass`
already has. The interned symbol's own spelling is `.todo/254`'s business.

Acceptance: the shape above on all four backends (per-backend suites +
`ci-spec.yaml`); `.kb/clos.md` `%mop-make-instance` paragraph.
