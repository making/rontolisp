package am.ik.rontolisp;

/**
 * {@code find-symbol} / {@code intern} / {@code symbol-package} of a STANDARD name the
 * compile paths cannot fold: a computed name in a package that uses {@code cl}
 * ({@code cl-user} included), a computed package designator, the one-argument form whose
 * status a multiple-value consumer reads, and {@code symbol-package} of a bare symbol.
 * The answer is the {@code cl} symbol with CL's status, never one homed in the asking
 * package; a shadowing or imported symbol still answers first, and the car/cdr
 * compositions are standard names too. Shared by the backend suites, so every backend is
 * held to one expected text (SBCL's output); the {@code ci-spec.yaml} case
 * {@code find-symbol-of-a-computed-standard-name} pins the same program on the native
 * binary.
 */
public final class StandardNameLookupFixture {

	private StandardNameLookupFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defpackage :stn-a (:use :cl))
			(defpackage :stn-sh (:use :cl) (:shadow #:list) (:export #:list))
			(defpackage :stn-imp (:use) (:import-from :cl #:car))
			(defun stn-show (n p) (print (multiple-value-list (find-symbol n p))))
			(defun stn-intern (n p) (print (multiple-value-list (intern n p))))
			(let ((car-name (string-upcase "car")) (list-name (string-upcase "list")))
			  (print (multiple-value-list (find-symbol car-name :stn-a)))
			  (print (multiple-value-list (find-symbol car-name "STN-A")))
			  (print (multiple-value-list (intern car-name :stn-a)))
			  (print (multiple-value-list (find-symbol car-name :cl-user)))
			  (print (multiple-value-list (find-symbol car-name :cl)))
			  (print (multiple-value-list (find-symbol (string-upcase "cadr") :stn-a)))
			  (print (multiple-value-list (find-symbol list-name :stn-sh)))
			  (print (multiple-value-list (find-symbol car-name :stn-imp)))
			  (print (multiple-value-list (find-symbol car-name)))
			  (print (multiple-value-list (intern list-name)))
			  (print (funcall (intern list-name :stn-a) 1 2))
			  (print (eq (find-symbol car-name :stn-a) 'car)))
			(dolist (p (list :stn-a "STN-SH" (find-package :cl-user) "CL"))
			  (stn-show (string-upcase "list") p)
			  (stn-intern (string-upcase "car") p))
			(stn-show (string-upcase "car") 'stn-imp)
			(print (multiple-value-list (find-symbol "CADR" :stn-a)))
			(print (multiple-value-list (find-symbol (string 'cdddr) :stn-a)))
			(print (eq (symbol-package 'car) (find-package :cl)))
			(print (eq (symbol-package (read-from-string "mapcan")) (find-package :cl)))
			(print (eq (symbol-package (intern (string-upcase "cdddr"))) (find-package :cl)))
			(print (eq (symbol-package nil) (find-package :cl)))
			(print (eq (symbol-package 'stn-own) (find-package :cl-user)))
			(print (eq (symbol-package 'stn-sh:list) (find-package :stn-sh)))
			(print (eq (symbol-package :car) (find-package :keyword)))
			(print (let ((f #'find-symbol)) (multiple-value-list (funcall f (string-upcase "cons") :stn-a))))
			""";

	/** What {@link #PROGRAM} prints on every backend. */
	public static final String EXPECTED = """
			(CAR :INHERITED)
			(CAR :INHERITED)
			(CAR :INHERITED)
			(CAR :INHERITED)
			(CAR :EXTERNAL)
			(CADR :INHERITED)
			(STN-SH:LIST :EXTERNAL)
			(CAR :INTERNAL)
			(CAR :INHERITED)
			(LIST :INHERITED)
			(1 2)
			T
			(LIST :INHERITED)
			(CAR :INHERITED)
			(STN-SH:LIST :EXTERNAL)
			(CAR :INHERITED)
			(LIST :INHERITED)
			(CAR :INHERITED)
			(LIST :EXTERNAL)
			(CAR :EXTERNAL)
			(CAR :INTERNAL)
			(CADR :INHERITED)
			(CDDDR :INHERITED)
			T
			T
			T
			T
			T
			T
			T
			(CONS :INHERITED)""";

}
