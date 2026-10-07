package am.ik.rontolisp;

/**
 * Every standard function the compiled backends lower only in call position, taken as a
 * function value: as {@code #'name}, as a quoted designator ({@code (funcall 'name ...)},
 * {@code apply}) and as {@code (symbol-function 'name)} -- including
 * {@code #'symbol-function} and {@code #'fdefinition} themselves, whose value resolves a
 * name at run time -- plus a count each value's call shape rules out. The JVM, P1 and the
 * component refused every such program ({@code Cannot compile: ARRAYP as a function
 * value}); {@code (symbol-function 'format)} of a wrapper injected only for a
 * {@code #'format} reference compiled to {@code The function FORMAT is undefined}. The
 * expected text is the interpreter's, SBCL's apart from rontolisp's wrong-count reports
 * and {@code make-random-state}'s NIL ({@code .kb/random.md}). Shared by the backend
 * suites, so every backend is held to one expected text.
 */
public final class StandardFunctionValueFixture {

	private StandardFunctionValueFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defun sfv-f () 1)
			(defun sfv-g () 2)
			(defun sfv-report (thunk)
			  (handler-case (funcall thunk) (program-error (c) (princ-to-string c))))
			(print (list (funcall (funcall #'symbol-function 'sfv-f))
			             (funcall (funcall #'fdefinition 'sfv-f))
			             (funcall (funcall 'symbol-function 'sfv-f))
			             (mapcar (lambda (f) (funcall f)) (mapcar #'fdefinition '(sfv-f sfv-g)))
			             (handler-case (funcall #'symbol-function 'sfv-nope)
			               (undefined-function (c) (cell-error-name c)))
			             (funcall (symbol-function 'format) nil "~a" 1)
			             (funcall (fdefinition 'format) nil "~a" 2)))
			(print (list (mapcar #'arrayp (list 1 "a" #(1)))
			             (funcall 'arrayp 1)
			             (funcall (symbol-function 'arrayp) #(1))
			             (funcall #'array-dimensions (make-array '(2 3)))
			             (apply 'array-dimensions (list (make-array 4)))
			             (funcall #'row-major-aref (make-array '(2 2) :initial-contents '((1 2) (3 4))) 3)
			             (mapcar #'rationalp (list 1 1/2 1.0))
			             (funcall 'rationalp 1/3)))
			(let ((h (make-hash-table :test 'equal)))
			  (print (list (funcall #'hash-table-test h)
			               (integerp (funcall #'hash-table-size h))
			               (funcall 'hash-table-rehash-size h)
			               (funcall #'hash-table-rehash-threshold h))))
			(let ((out (funcall #'make-string-output-stream)))
			  (write-string "out" out)
			  (print (list (funcall #'get-output-stream-string out)
			               (funcall 'open-stream-p out)
			               (funcall #'close out)
			               (open-stream-p out)
			               (funcall #'close (make-string-output-stream) :abort t)
			               (read-line (funcall #'make-string-input-stream "abcdef" 1 3))
			               (read-line (funcall 'make-string-input-stream "xyz"))
			               (with-output-to-string (*standard-output*)
			                 (write-string "syn" (funcall #'make-synonym-stream '*standard-output*))))))
			(print (list (funcall #'eval '(+ 1 2))
			             (integerp (funcall #'get-universal-time))
			             (integerp (funcall #'get-internal-real-time))
			             (integerp (funcall 'get-internal-run-time))
			             (funcall #'make-random-state)
			             (funcall #'load "sfv-no-such-file.lisp" :if-does-not-exist nil)))
			(defun sfv-gone () 3)
			(print (list (funcall #'fmakunbound 'sfv-gone) (fboundp 'sfv-gone)))
			(defpackage :sfv-pkg (:use :cl))
			(let ((sym (intern "SFV-X" :sfv-pkg)))
			  (print (list (funcall #'export sym :sfv-pkg)
			               (funcall 'unexport sym :sfv-pkg)
			               (funcall #'use-package :sfv-pkg)
			               (funcall #'unuse-package :sfv-pkg)
			               (funcall #'import sym))))
			(print (list (sfv-report (lambda () (funcall #'arrayp)))
			             (sfv-report (lambda () (funcall #'export 'sfv-z :cl-user 3)))
			             (sfv-report (lambda () (funcall #'close (make-string-output-stream) 1)))
			             (sfv-report (lambda () (funcall #'close (make-string-output-stream) :abort t 1)))
			             (sfv-report (lambda () (funcall #'make-string-input-stream)))
			             (sfv-report (lambda () (funcall #'make-synonym-stream)))))
			""";

	/**
	 * A synonym stream over a COMPUTED symbol, in call position: its lowering reads the
	 * variable through a {@code symbol-value} it synthesizes after the gates ran, and P1
	 * and the component trapped where the eval mirror it reads was left out.
	 */
	public static final String COMPUTED_SYNONYM = """
			(print (with-output-to-string (*standard-output*)
			         (write-string "syn" (make-synonym-stream (car (list '*standard-output*))))))
			""";

	/** What {@link #COMPUTED_SYNONYM} prints. */
	public static final String COMPUTED_SYNONYM_EXPECTED = "\"syn\"";

	/** What {@link #PROGRAM} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(1 1 1 (1 2) SFV-NOPE \"1\" \"2\")",
			"((NIL T T) NIL T (2 3) (4) 4 (T T NIL) T)", "(EQUAL T 1.5 1.0)",
			"(\"out\" T T NIL T \"bc\" \"xyz\" \"syn\")", "(3 T T T NIL NIL)", "(SFV-GONE NIL)", "(T T T T T)",
			"(\"ARRAYP expects 1 argument, got 0\" \"EXPORT expects at most 2 arguments, got 3\""
					+ " \"CLOSE expects 1 or 3 arguments, got 2\" \"CLOSE expects 1 or 3 arguments, got 4\""
					+ " \"MAKE-STRING-INPUT-STREAM expects at least 1 argument, got 0\""
					+ " \"MAKE-SYNONYM-STREAM expects 1 argument, got 0\")");

}
