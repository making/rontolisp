package am.ik.rontolisp;

/**
 * A built-in reached through its function value -- {@code apply}, {@code funcall} of a
 * designator, a mapping function, a compiled {@code eval} -- with a count its call shape
 * rules out, shared by the backend suites: the report is the one its direct call makes
 * ({@code compiler/BuiltinCallArity}), where the interpreter's Java body spelled its own
 * range ({@code FLOOR expects 1 to 2 arguments}) and the compiled wrapper's
 * {@code &optional} surplus check said {@code Function}. A program's own function keeps
 * {@code Function}.
 */
public final class BuiltinFunctionValueCountFixture {

	private BuiltinFunctionValueCountFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defun fv-report (thunk)
			  (handler-case (funcall thunk) (program-error (e) (princ-to-string e))))
			(defun fv-own (a &optional b) (list a b))
			(print (fv-report (lambda () (apply #'floor 7 '(2 3)))))
			(print (fv-report (lambda () (funcall #'floor))))
			(print (fv-report (lambda () (apply #'gethash 1 '()))))
			(print (fv-report (lambda () (apply #'gethash 1 2 3 '(4)))))
			(print (fv-report (lambda () (funcall 'subseq "abc" 1 2 3))))
			(print (fv-report (lambda () (mapcar #'char-upcase '(#\\a) '(#\\b)))))
			(print (fv-report (lambda () (eval '(funcall #'floor 7 2 3)))))
			(print (fv-report (lambda () (apply #'fv-own 1 '(2 3)))))
			(print (list (apply #'floor 7 '(2)) (funcall 'subseq "abc" 1 2) (fv-own 1)))
			""";

	/** What {@link #PROGRAM} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "\"FLOOR expects at most 2 arguments, got 3\"",
			"\"FLOOR expects at least 1 argument, got 0\"", "\"GETHASH expects at least 2 arguments, got 1\"",
			"\"GETHASH expects at most 3 arguments, got 4\"", "\"SUBSEQ expects at most 3 arguments, got 4\"",
			"\"CHAR-UPCASE expects 1 argument, got 2\"", "\"FLOOR expects at most 2 arguments, got 3\"",
			"\"Function expects at most 2 arguments, got 3\"", "(3 \"b\" (1 NIL))");

}
