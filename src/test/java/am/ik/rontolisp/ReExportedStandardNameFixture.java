package am.ik.rontolisp;

/**
 * A package that uses {@code cl} and exports a standard name re-exports {@code cl}'s
 * symbol (CLHS 11.1.1.2): {@code (:export #:car)} -- or a runtime {@code export} of
 * {@code 'cons} -- makes {@code pkg:car} the bare {@code CAR}, a package using it
 * inherits that symbol with or without {@code cl}, and the lookups,
 * {@code symbol-package} and the package walks answer it. An exported-only standard name
 * ({@code boole}) is re-exported the same way. Shared by the backend suites, so every
 * backend is held to one expected text (SBCL's output); the {@code ci-spec.yaml} case
 * {@code export-of-an-inherited-standard-name} pins the same program on the native
 * binary.
 */
public final class ReExportedStandardNameFixture {

	private ReExportedStandardNameFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defpackage :rsn-x (:use :cl) (:export #:car #:list #:print #:boole #:rsn-own))
			(export 'cons :rsn-x)
			(defpackage :rsn-u (:use :rsn-x))
			(print (list (eq 'rsn-x:car 'car) (eq 'rsn-x::list 'list) (eq 'rsn-x:boole 'boole) (eq 'rsn-x:cons 'cons)))
			(print (multiple-value-list (find-symbol "CAR" :rsn-x)))
			(print (multiple-value-list (find-symbol "CAR" :rsn-u)))
			(print (multiple-value-list (find-symbol "BOOLE" :rsn-u)))
			(print (multiple-value-list (find-symbol (string-upcase "car") :rsn-x)))
			(print (multiple-value-list (find-symbol (string-upcase "list") :rsn-u)))
			(print (multiple-value-list (find-symbol (string-upcase "boole") :rsn-x)))
			(print (eq (symbol-package 'rsn-x:car) (symbol-package 'cons)))
			(print (rsn-x:car (rsn-x:list 1 2)))
			(print (funcall (find-symbol "LIST" :rsn-u) 3 4))
			(let ((names '()))
			  (do-external-symbols (s :rsn-x) (push s names))
			  (print (sort names #'string< :key #'symbol-name)))
			(print (find 'car (let ((l '())) (do-symbols (s :rsn-u) (push s l)) l)))
			(print (multiple-value-list (find-symbol "RSN-OWN" :rsn-u)))
			(cl:in-package :rsn-u)
			(print (car (list 5 6)))
			(print (cons 7 (cl:if (cl:eq 'car 'cl:car) 1 0)))
			(cl:in-package :cl-user)
			""";

	/** What {@link #PROGRAM} prints on every backend. */
	public static final String EXPECTED = """
			(T T T T)
			(CAR :EXTERNAL)
			(CAR :INHERITED)
			(BOOLE :INHERITED)
			(CAR :EXTERNAL)
			(LIST :INHERITED)
			(BOOLE :EXTERNAL)
			T
			1
			(3 4)
			(BOOLE CAR CONS LIST PRINT RSN-X:RSN-OWN)
			CAR
			(RSN-X:RSN-OWN :INHERITED)
			5
			(7 . 1)""";

}
