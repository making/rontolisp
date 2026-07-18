;; A leaf-module shim replacing com.inuoe.jzon's ratio-to-double.lisp: the same
;; package and exported contract (ratio-to-double: rational -> double-float),
;; delegating to rontolisp's native coerce instead of the original's ported
;; CCL bit-assembly (which needs float-features bit reinterpretation and
;; unbounded integer shifts). Written in canonical shape (no in-package); the
;; defpackage form registers the package for the following jzon.lisp.

(defpackage #:com.inuoe.jzon/ratio-to-double
  (:use #:cl)
  (:export #:ratio-to-double))

(defun com.inuoe.jzon/ratio-to-double:ratio-to-double (number)
  (coerce number 'double-float))
