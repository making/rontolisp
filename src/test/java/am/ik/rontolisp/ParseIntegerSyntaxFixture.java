package am.ik.rontolisp;

/**
 * A string that is no integer syntax to {@code parse-integer} without
 * {@code :junk-allowed}, shared by the backend suites.
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the `parse-integer-signals-a-parse-error` ci-spec
 * case): junk, no digit, an empty string and an empty region are a {@code parse-error} --
 * an {@code error}, neither a {@code simple-error} nor a {@code reader-error} -- in call
 * position and first class, caught by {@code handler-case}, {@code handler-bind} and
 * {@code ignore-errors}. The last row is answers with {@code :junk-allowed} and around
 * whitespace.</li>
 * <li>{@link #REPORT_PROGRAM}: what that {@code parse-error} reports.</li>
 * <li>{@link #RESTART_PROGRAM}: a {@code handler-bind} handler runs at the signal point,
 * where the restarts around the call are still established, and before an enclosing
 * {@code handler-case} takes the condition.</li>
 * </ul>
 * The strings are fresh and the bounds read at run time so no backend can fold them.
 */
public final class ParseIntegerSyntaxFixture {

	private ParseIntegerSyntaxFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defvar *pis-1* (read-from-string "1"))
			(defvar *pis-3* (read-from-string "3"))
			(defun pis-probe (thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (simple-error () :simple-error)
			    (parse-error (c) (list :parse-error (typep c 'error) (typep c 'reader-error) (typep c 'stream-error)))
			    (error () :other-error)))
			(defmacro pis-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(pis-probe (lambda () ,f))) forms))))
			(pis-row (parse-integer (copy-seq "12a")) (parse-integer (copy-seq "")) (parse-integer (copy-seq " - "))
			         (parse-integer "x"))
			(pis-row (parse-integer (copy-seq "12 3")) (parse-integer (copy-seq "123") :start *pis-3*)
			         (parse-integer (copy-seq "123") :start *pis-1* :end *pis-1*) (parse-integer (copy-seq "zz") :radix 10))
			(pis-row (funcall #'parse-integer (copy-seq "12a")) (funcall #'parse-integer (copy-seq "  "))
			         (apply #'parse-integer (copy-seq "123") (list :start *pis-3*)) (funcall #'parse-integer (copy-seq "+")))
			(print (block pis-hb
			         (handler-bind ((parse-error (lambda (c) (return-from pis-hb (list :handler-bind (typep c 'parse-error))))))
			           (parse-integer (copy-seq "q")))))
			(print (let ((c (nth-value 1 (ignore-errors (parse-integer (copy-seq "q"))))))
			         (list :ignore-errors (typep c 'parse-error))))
			(pis-row (parse-integer (copy-seq "12a") :junk-allowed t) (parse-integer (copy-seq " - ") :junk-allowed t)
			         (funcall #'parse-integer (copy-seq "") :junk-allowed t) (parse-integer (copy-seq " 12 ")))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n",
			"((:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL))",
			"((:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL))",
			"((:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL) (:PARSE-ERROR T NIL NIL))",
			"(:HANDLER-BIND T)", "(:IGNORE-ERRORS T)", "((12 2) (NIL 2) (NIL 0) (12 4))");

	/** One refusal per line: its report. */
	public static final String REPORT_PROGRAM = """
			(defun pir-probe (thunk)
			  (handler-case (funcall thunk)
			    (parse-error (c) (princ-to-string c))
			    (error (c) (list :not-a-parse-error (type-of c)))))
			(print (pir-probe (lambda () (parse-integer (copy-seq "12a")))))
			(print (pir-probe (lambda () (parse-integer (copy-seq " ")))))
			(print (pir-probe (lambda () (funcall #'parse-integer (copy-seq "1~a")))))
			(print (pir-probe (lambda () (apply #'parse-integer (copy-seq "") nil))))
			""";

	/** What {@link #REPORT_PROGRAM} prints. */
	public static final String REPORT_EXPECTED = String.join("\n", "\"parse-integer: junk in string \\\"12a\\\"\"",
			"\"parse-integer: no integer in string \\\" \\\"\"", "\"parse-integer: junk in string \\\"1~a\\\"\"",
			"\"parse-integer: no integer in string \\\"\\\"\"");

	/** One line per probe. */
	public static final String RESTART_PROGRAM = """
			(defvar *pis-ran* nil)
			(print (handler-bind ((error (lambda (c) (declare (ignore c)) (invoke-restart 'use-value 43))))
			         (restart-case (parse-integer (copy-seq "x")) (use-value (v) v))))
			(print (handler-bind ((parse-error (lambda (c) (invoke-restart 'use-value (list 44 (typep c 'parse-error))))))
			         (restart-case (funcall #'parse-integer (copy-seq "x")) (use-value (v) v))))
			(print (handler-case (handler-bind ((error (lambda (c) (declare (ignore c)) (setq *pis-ran* t))))
			                       (parse-integer (copy-seq "x")))
			         (parse-error (c) (list :handler-case (typep c 'parse-error) *pis-ran*))))
			""";

	/** What {@link #RESTART_PROGRAM} prints (sbcl's answers). */
	public static final String RESTART_EXPECTED = String.join("\n", "43", "(44 T)", "(:HANDLER-CASE T T)");

}
