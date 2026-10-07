package am.ik.rontolisp;

/**
 * {@code streamp} and {@code (typep x 'stream)} of a composite stream built inside the
 * same form, shared by the backend suites. Each program builds every composite class for
 * the FIRST time inside the predicate's argument -- directly or through a function -- so
 * the prelude class behind it is not yet loaded when the predicate's call form is
 * reached. sbcl's answers.
 */
public final class CompositeStreampFixture {

	private CompositeStreampFixture() {
	}

	/** {@code streamp} of each composite stream. */
	public static final String STREAMP_PROGRAM = """
			(defun csp-concatenated () (make-concatenated-stream (make-string-input-stream "x")))
			(print (list (streamp (make-two-way-stream (make-string-input-stream "x") (make-string-output-stream)))
			             (streamp (make-echo-stream (make-string-input-stream "x") (make-string-output-stream)))
			             (streamp (make-broadcast-stream (make-string-output-stream)))
			             (streamp (csp-concatenated))))
			""";

	/** {@code (typep x 'stream)} of each composite stream. */
	public static final String TYPEP_PROGRAM = """
			(defun csp-concatenated () (make-concatenated-stream (make-string-input-stream "x")))
			(print (list (typep (make-two-way-stream (make-string-input-stream "x") (make-string-output-stream)) 'stream)
			             (typep (make-echo-stream (make-string-input-stream "x") (make-string-output-stream)) 'stream)
			             (typep (make-broadcast-stream) 'stream)
			             (typep (csp-concatenated) 'stream)))
			""";

	/** What each program prints. */
	public static final String EXPECTED = "(T T T T)";

}
