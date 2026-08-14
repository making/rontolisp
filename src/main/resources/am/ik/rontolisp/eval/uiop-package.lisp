;;;; uiop/package -- the symbol and package surgery family.
;;;; Canonical shape; see .kb/uiop.md.
;;;;
;;;; Only the two LOOKUP primitives are here. They are not this sub-package's own
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
;; not own still answers a symbol where the interpreter answers nil (.todo/254),
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
