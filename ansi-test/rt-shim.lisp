;;; A minimal stand-in for the suite's own RT framework (rt.lsp).
;;;
;;; rt.lsp itself is not loaded: it keeps its test database in structures with
;;; (:conc-name nil), compiles every test through COMPILE, and drives the run from
;;; DO-TESTS -- machinery that measures rt.lsp rather than the chapters. What the
;;; chapters actually need from it is DEFTEST plus the handful of specials the aux
;;; layer reads at macroexpansion time, and that is what this file defines. Each test
;;; runs where it is defined, and prints one result line the driver counts.

(defpackage "REGRESSION-TEST" (:use "COMMON-LISP") (:nicknames "RT" "RTEST"))
(defpackage "CL-TEST" (:use "COMMON-LISP"))
(defpackage "SANDBOX" (:use "COMMON-LISP"))

(in-package "REGRESSION-TEST")

;;; Read by ansi-aux at macroexpansion time (SIGNALS-ERROR consults *compile-tests*
;;; to decide between COMPILE+FUNCALL and EVAL); an unbound one costs every test in
;;; the file that uses the macro.
(defvar *compile-tests* nil)
(defvar *expanded-eval* nil)
(defvar *catch-errors* t)
(defvar *test* nil)
(defvar *do-tests-when-defined* nil)
(defvar *notes* nil)
(defvar *passed-tests* nil)
(defvar *failed-tests* nil)
(defvar *expected-failures* nil)
(defvar *print-circle-on-failure* nil)
(defvar *optimization-settings* '((safety 3)))

(in-package "COMMON-LISP-USER")

(defun my-aref (a &rest args) (apply #'aref a args))

;;; notes.lsp attaches notes to tests; a note only ever decides whether RT skips a
;;; test, which this driver does not do, so recording the name is enough.
(defun defnote (name contents &optional disabled)
  (declare (ignore contents disabled))
  name)

(defun disable-note (name) name)

(defun %ansi-clip (x)
  (let ((s (format nil "~s" x)))
    (if (> (length s) 160) (concatenate 'string (subseq s 0 160) "...") s)))

;;; The name of a DEFTEST may carry properties: (name :notes foo).
(defun %ansi-test-name (name)
  (if (consp name) (car name) name))

;;; RT compares the values a test returned with EQUALP-WITH-CASE, not EQUALP, and the
;;; difference decides results: EQUALP would call 1 and 1.0 equal, and "abc" and "ABC"
;;; equal, so a wrong-type or wrong-case answer would be scored as a pass. This is
;;; rt.lsp's function, minus the rank-0 array case the suite says it does not handle.
(defun %ansi-equal-with-case (x y)
  (cond ((eq x y) t)
        ((consp x) (and (consp y)
                        (%ansi-equal-with-case (car x) (car y))
                        (%ansi-equal-with-case (cdr x) (cdr y))))
        ((stringp x) (and (stringp y) (string= x y)))
        ((vectorp x)
         (and (vectorp y) (eql (length x) (length y))
              (let ((ok t))
                (dotimes (i (length x) ok)
                  (unless (%ansi-equal-with-case (aref x i) (aref y i)) (setq ok nil))))))
        ((arrayp x)
         (and (arrayp y) (equal (array-dimensions x) (array-dimensions y))
              (let ((n (reduce #'* (array-dimensions x))) (ok t))
                (dotimes (i n ok)
                  (unless (%ansi-equal-with-case (row-major-aref x i) (row-major-aref y i))
                    (setq ok nil))))))
        ((pathnamep x) (equal x y))
        (t (eql x y))))

(defun %ansi-run-test (name thunk expected)
  (let ((r (handler-case (funcall thunk)
             (error (c) (list '%ansi-error (format nil "~a" c))))))
    (cond ((and (consp r) (eq (car r) '%ansi-error))
           (format t "ERROR ~a ~a~%" name (cadr r)))
          ((%ansi-equal-with-case r expected) (format t "PASS ~a~%" name))
          (t (format t "FAIL ~a got ~a want ~a~%" name (%ansi-clip r) (%ansi-clip expected))))))

(defmacro deftest (name form &rest expected)
  (list '%ansi-run-test
        (list 'quote (%ansi-test-name name))
        (list 'lambda '() (list 'multiple-value-list form))
        (list 'quote expected)))
