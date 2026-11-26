;;;; A synthetic :class :package-inferred-system, in the shape ningle and rove have:
;;;; no :components at all, one sub-system named in :depends-on, and the rest of the
;;;; graph reachable only through the component files' own defpackage forms.

(defsystem "package-inferred-demo"
  :class :package-inferred-system
  :version "0.1.0"
  :license "MIT"
  :description "Fixture for the package-inferred-system half of the ASDF subset."
  :depends-on ("package-inferred-demo/main"))

;; The package a plain system defines does not have to be named after it: this is the
;; map ningle.asd needs so that (:import-from #:lack.request ...) asks for lack-request.
(register-system-packages "package-inferred-demo-tag" '(#:pkg.inferred.tag))
