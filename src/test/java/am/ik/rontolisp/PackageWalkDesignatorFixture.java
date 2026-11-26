package am.ik.rontolisp;

/**
 * A symbol a package walk produces -- {@code do-external-symbols}, {@code apropos-list}
 * -- calls the function it names, though the program never spells that name as a value:
 * an exported defun only ever called directly, one taken as a value, and one taken as a
 * value only inside a function nothing calls. The compile paths' name registry answers
 * the names the baked package universes carry, not only the literals the program emits.
 * Shared by the backend suites, so every backend is held to one expected text.
 */
public final class PackageWalkDesignatorFixture {

	private PackageWalkDesignatorFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defpackage :pwd (:use :cl) (:export #:pwd-call-only #:pwd-valued #:pwd-dead-valued))
			(in-package :pwd)
			(defun pwd-call-only (x) (list :call-only x))
			(defun pwd-valued (x) (list :valued x))
			(defun pwd-dead-valued (x) (list :dead-valued x))
			(defun pwd-nobody () #'pwd-dead-valued)
			(defparameter *pwd-keep* (list #'pwd-valued))
			(print (pwd-call-only 0))
			(let ((names nil))
			  (do-external-symbols (s :pwd) (push s names))
			  (dolist (s (sort names #'string< :key #'symbol-name))
			    (print (funcall s 1))))
			(print (mapcar (lambda (s) (funcall s 2)) (apropos-list "CALL-ONLY" :pwd)))
			(print (mapcar (lambda (s) (funcall s 3)) (apropos-list "DEAD" :pwd)))
			""";

	/** What {@link #PROGRAM} prints on every backend. */
	public static final String EXPECTED = """
			(:CALL-ONLY 0)
			(:CALL-ONLY 1)
			(:DEAD-VALUED 1)
			(:VALUED 1)
			((:CALL-ONLY 2))
			((:DEAD-VALUED 3))""";

}
