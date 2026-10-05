package am.ik.rontolisp;

/**
 * {@code find-symbol} / {@code intern} of a name a {@code defpackage} package reaches
 * through {@code :use}, {@code :import-from} or a re-export: the answer is the symbol
 * homed in the package that provides it, with the status CL gives it ({@code :inherited},
 * {@code :internal} for an import, {@code :external} for a re-export), never a symbol
 * homed in the asking package. Literal and computed names, literal and computed package
 * designators (keyword, string, symbol, package value), and the operators as function
 * objects. A shadowed name answers the package's own symbol. Shared by the backend
 * suites, so every backend is held to one expected text (SBCL's output); the
 * {@code ci-spec.yaml} case {@code find-symbol-of-an-inherited-name} pins the same
 * program on the native binary.
 */
public final class InheritedFindSymbolFixture {

	private InheritedFindSymbolFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defpackage :fsi-u1 (:use) (:export #:b1 #:b2))
			(defpackage :fsi-u2 (:use) (:export #:c1 #:d1))
			(defpackage :fsi-all (:use :fsi-u1 :fsi-u2) (:shadow #:c1))
			(defpackage :fsi-cl (:use :cl :fsi-u1))
			(defpackage :fsi-imp (:use) (:import-from :fsi-u1 #:b1))
			(defpackage :fsi-rex (:use :fsi-u1) (:export #:b1))
			(print (multiple-value-list (find-symbol "B1" :fsi-all)))
			(print (multiple-value-list (find-symbol "D1" "FSI-ALL")))
			(print (multiple-value-list (find-symbol "C1" :fsi-all)))
			(print (multiple-value-list (find-symbol "B2" :fsi-cl)))
			(print (multiple-value-list (find-symbol "CAR" :fsi-cl)))
			(print (multiple-value-list (find-symbol "B1" :fsi-imp)))
			(print (multiple-value-list (find-symbol "B1" :fsi-rex)))
			(print (multiple-value-list (intern "B1" :fsi-all)))
			(print (package-name (symbol-package (find-symbol "B1" :fsi-all))))
			(print (eq (find-symbol "B1" :fsi-all) 'fsi-u1:b1))
			(defun fsi-show (n p) (print (multiple-value-list (find-symbol n p))))
			(defun fsi-intern (n p) (print (multiple-value-list (intern n p))))
			(dolist (p (list :fsi-all "FSI-CL" (find-package :fsi-imp) 'fsi-rex))
			  (fsi-show "B1" p)
			  (fsi-intern (string-upcase "b1") p))
			(fsi-show "C1" :fsi-all)
			(fsi-show "D1" :fsi-all)
			(let ((n (string-upcase "b2")))
			  (print (multiple-value-list (find-symbol n :fsi-all)))
			  (print (multiple-value-list (intern n :fsi-cl)))
			  (print (eq (find-symbol n :fsi-cl) 'fsi-u1:b2))
			  (print (package-name (symbol-package (find-symbol n "FSI-CL")))))
			(let ((f #'find-symbol) (g #'intern))
			  (print (multiple-value-list (funcall f (string-upcase "d1") :fsi-all)))
			  (print (multiple-value-list (funcall g "B1" "FSI-IMP"))))
			""";

	/** What {@link #PROGRAM} prints on every backend. */
	public static final String EXPECTED = """
			(FSI-U1:B1 :INHERITED)
			(FSI-U2:D1 :INHERITED)
			(FSI-ALL::C1 :INTERNAL)
			(FSI-U1:B2 :INHERITED)
			(CAR :INHERITED)
			(FSI-U1:B1 :INTERNAL)
			(FSI-U1:B1 :EXTERNAL)
			(FSI-U1:B1 :INHERITED)
			"FSI-U1"
			T
			(FSI-U1:B1 :INHERITED)
			(FSI-U1:B1 :INHERITED)
			(FSI-U1:B1 :INHERITED)
			(FSI-U1:B1 :INHERITED)
			(FSI-U1:B1 :INTERNAL)
			(FSI-U1:B1 :INTERNAL)
			(FSI-U1:B1 :EXTERNAL)
			(FSI-U1:B1 :EXTERNAL)
			(FSI-ALL::C1 :INTERNAL)
			(FSI-U2:D1 :INHERITED)
			(FSI-U1:B2 :INHERITED)
			(FSI-U1:B2 :INHERITED)
			T
			"FSI-U1"
			(FSI-U2:D1 :INHERITED)
			(FSI-U1:B1 :INTERNAL)""";

}
