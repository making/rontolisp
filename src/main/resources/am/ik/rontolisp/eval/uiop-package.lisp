;;;; uiop/package -- the symbol and package surgery family.
;;;; Canonical shape; see .kb/uiop.md.
;;;;
;;;; Only the LOOKUP primitives are here. They are not this sub-package's own
;;;; item: uiop/utility's find-standard-case-symbol, coerce-class and
;;;; symbol-test-to-feature-expression are all written over find-symbol*, so
;;;; routing them around it would leave three copies of "look a name up in a
;;;; package, error or not" instead of the one upstream has.

(defun uiop/package:find-package* (%fp-designator &optional (%fp-error t))
  (let ((%fp-package (find-package %fp-designator)))
    (cond (%fp-package %fp-package)
          (%fp-error (error "No package named ~S" (string %fp-designator)))
          (t nil))))

;; Two values, as CL's find-symbol: the symbol and its status. The status the
;; compiled backends report is the one .kb/symbol-runtime-api.md describes -- a
;; compiled find-symbol BUILDS the qualified spelling, so a name the package does
;; not own still answers a symbol where the interpreter answers nil,
;; and with :error true that difference is the difference between returning and
;; signalling.
(defun uiop/package:find-symbol*
    (%fs-name %fs-designator &optional (%fs-error t))
  (let ((%fs-package (uiop/package:find-package* %fs-designator %fs-error)))
    (if (null %fs-package)
        (values nil nil)
        (multiple-value-bind (%fs-symbol %fs-status)
            (find-symbol (string %fs-name) %fs-package)
          (cond (%fs-status (values %fs-symbol %fs-status))
                (%fs-error (error "There is no symbol ~S in package ~S" %fs-name
                                  (package-name %fs-package)))
                (t (values nil nil)))))))

;; Upstream's own shape, and here for the same reason file-exists-p carries one:
;; symbol-call in CALL position is folded away (expandUiopStubCall lowers it to a
;; runtime intern + funcall), so this definition is what a FIRST-CLASS
;; #'uiop:symbol-call resolves to -- the (apply #'uiop:symbol-call '#:pkg '#:name
;; args) backend dispatch dexador writes. The interpreter keeps its Java built-in,
;; which resolves first and never lets this one load.
(defun uiop/package:symbol-call (%sc-package %sc-name &rest %sc-args)
  (apply (uiop/package:find-symbol* %sc-name %sc-package) %sc-args))
