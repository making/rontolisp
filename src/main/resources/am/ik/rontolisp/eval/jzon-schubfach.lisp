;; A leaf-module shim replacing com.inuoe.jzon's schubfach.lisp: the same
;; package and exported contract (write-float / write-double: value + character
;; output stream), rendering through rontolisp's native float printer instead
;; of the original's u64/u128 Schubfach algorithm and its #.-generated tables.
;; The printed text is rontolisp's cross-backend-identical float shape, NOT
;; schubfach's shortest-round-trip string (e.g. exponents print as 1.5E22).
;; rontolisp has one float representation, so write-float and write-double
;; render identically. Written in canonical shape (no in-package); the
;; defpackage form registers the package for the following jzon.lisp.

(defpackage #:com.inuoe.jzon/schubfach
  (:use #:cl)
  (:export #:write-float #:write-double))

(defun com.inuoe.jzon/schubfach:write-double (x stream)
  (check-type stream stream)
  (write-string (%princ-piece x) stream))

(defun com.inuoe.jzon/schubfach:write-float (x stream)
  (check-type stream stream)
  (write-string (%princ-piece x) stream))
