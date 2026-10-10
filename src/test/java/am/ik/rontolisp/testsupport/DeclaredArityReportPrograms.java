package am.ik.rontolisp.testsupport;

/**
 * A function whose body declares its own wrong-count report
 * ({@code (declare (%arity-report prefix suffix))}, {@code DeclaredArityReport}, what the
 * Clojure front end spells the oracle's {@code ArityException} with) refuses in its
 * words, pinned identically on the interpreter, the JVM and both WASM backends: a defun,
 * a {@code &rest} defun and a lambda, through {@code funcall} (a dispatch miss), a
 * literal and a computed {@code apply} (the count guard) and a direct call -- while a
 * built-in still names its operator and an undeclaring lambda says {@code Function}.
 */
public final class DeclaredArityReportPrograms {

	private DeclaredArityReportPrograms() {
	}

	/** The definitions; {@code (ar-all)} answers {@link #ANSWER}. */
	public static final String PROGRAM = """
			(defun ar-f (x) (declare (rontolisp::%arity-report "Wrong (" ") ar-f")) x)
			(defun ar-g (x &rest r) (declare (rontolisp::%arity-report "G<" ">")) (list x r))
			(defparameter *ar-h* (lambda (x) (declare (rontolisp::%arity-report "H[" "]")) x))
			(defun ar-try (thunk) (handler-case (funcall thunk) (program-error (c) (princ-to-string c))))
			(defun ar-all ()
			  (list (ar-try (lambda () (funcall #'ar-f))) (ar-try (lambda () (apply #'ar-f '(1 2 3))))
			        (ar-try (lambda () (let ((h #'ar-f)) (apply h '(1 2))))) (ar-try (lambda () (ar-f 1 2)))
			        (ar-try (lambda () (funcall #'ar-g))) (ar-try (lambda () (funcall *ar-h* 1 2)))
			        (ar-try (lambda () (funcall #'cons 1))) (ar-try (lambda () (funcall (lambda (x) x))))
			        (ar-f 5) (funcall #'ar-g 1 2) (funcall *ar-h* 3)))
			""";

	/** What {@code (ar-all)} answers after {@link #PROGRAM}, printed. */
	public static final String ANSWER = "(\"Wrong (0) ar-f\" \"Wrong (3) ar-f\" \"Wrong (2) ar-f\" \"Wrong (2) ar-f\""
			+ " \"G<0>\" \"H[2]\" \"CONS expects 2 arguments, got 1\" \"Function expects 1 argument, got 0\""
			+ " 5 (1 (2)) 3)";

}
