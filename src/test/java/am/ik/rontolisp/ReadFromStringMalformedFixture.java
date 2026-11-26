package am.ik.rontolisp;

/**
 * A one-argument {@code read-from-string} over text that holds no complete datum, shared
 * by the backend suites.
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the `read-from-string-refuses-malformed-text` ci-spec
 * case): a {@code )} that closes nothing is a {@code reader-error}; text holding no datum
 * (empty, whitespace, comments) and text that ends inside a datum (a list, a string, a
 * quote, a dotted tail, a vector, a block comment, a character literal, an array) are an
 * {@code end-of-file}, and a dotted list with nothing before the dot or more than one
 * object after it is a {@code reader-error} -- in call position, first class, under
 * {@code handler-case}, {@code handler-bind} and {@code ignore-errors}. The last row is
 * text whose first datum is complete, whatever follows it.</li>
 * <li>{@link #RESTART_PROGRAM}: a {@code handler-bind} handler runs at the signal point,
 * where the restarts around the call are still established.</li>
 * <li>{@link #REPORT_PROGRAM}: what the two conditions report.</li>
 * </ul>
 * The strings are fresh at run time so no backend can fold them.
 */
public final class ReadFromStringMalformedFixture {

	private ReadFromStringMalformedFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defun rfm-probe (thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (end-of-file (c) (list :eof (typep c 'stream-error) (typep c 'parse-error)))
			    (reader-error (c) (list :reader-error (typep c 'parse-error) (typep c 'stream-error)))
			    (error (c) (list :other-error (type-of c)))))
			(defmacro rfm-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(rfm-probe (lambda () ,f))) forms))))
			(rfm-row (read-from-string ")") (read-from-string (copy-seq " ) a")) (read-from-string (copy-seq "')"))
			         (read-from-string (copy-seq "(a ')")))
			(rfm-row (read-from-string "") (read-from-string (copy-seq "   ")) (read-from-string (copy-seq "; c"))
			         (read-from-string (copy-seq "#| x |#")))
			(rfm-row (read-from-string (copy-seq "(a b")) (read-from-string (copy-seq "\\"ab"))
			         (read-from-string (copy-seq "'")) (read-from-string (copy-seq "(a . b")))
			(rfm-row (read-from-string (copy-seq "#(1 2")) (read-from-string (copy-seq "#|x"))
			         (read-from-string (copy-seq "#\\\\")) (read-from-string (copy-seq "#2A((1 2)")))
			(rfm-row (funcall #'read-from-string (copy-seq ")")) (funcall #'read-from-string (copy-seq "(a"))
			         (apply #'read-from-string (list (copy-seq ""))) (values (read-from-string (copy-seq "(a . )"))))
			(print (block rfm-hb
			         (handler-bind ((reader-error (lambda (c) (return-from rfm-hb (list :handler-bind (typep c 'reader-error))))))
			           (read-from-string (copy-seq ")")))))
			(print (let ((c (nth-value 1 (ignore-errors (read-from-string (copy-seq "(a"))))))
			         (list :ignore-errors (typep c 'end-of-file))))
			(rfm-row (read-from-string (copy-seq "( . a)")) (read-from-string (copy-seq "(a . b c)"))
			         (read-from-string (copy-seq "(. a)")) (read-from-string (copy-seq "(a . b . c)")))
			(rfm-row (read-from-string (copy-seq "(1 ( . a))")) (read-from-string (copy-seq "'(a . b c)"))
			         (read-from-string (copy-seq "(a . b c")) (read-from-string (copy-seq "#( . a)")))
			(rfm-row (read-from-string (copy-seq "(a . b)")) (read-from-string (copy-seq "(a b . c)"))
			         (read-from-string (copy-seq "(a .b)")) (read-from-string (copy-seq "(a . (b) )")))
			(rfm-row (read-from-string (copy-seq "a)")) (read-from-string (copy-seq "(a) )"))
			         (read-from-string (copy-seq "#| c |# x")) (read-from-string (copy-seq " \\"a\\\\\\\\\\" ")))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n",
			"((:READER-ERROR T T) (:READER-ERROR T T) (:READER-ERROR T T) (:READER-ERROR T T))",
			"((:EOF T NIL) (:EOF T NIL) (:EOF T NIL) (:EOF T NIL))",
			"((:EOF T NIL) (:EOF T NIL) (:EOF T NIL) (:EOF T NIL))",
			"((:EOF T NIL) (:EOF T NIL) (:EOF T NIL) (:EOF T NIL))",
			"((:READER-ERROR T T) (:EOF T NIL) (:EOF T NIL) (:READER-ERROR T T))", "(:HANDLER-BIND T)",
			"(:IGNORE-ERRORS T)", "((:READER-ERROR T T) (:READER-ERROR T T) (:READER-ERROR T T) (:READER-ERROR T T))",
			"((:READER-ERROR T T) (:READER-ERROR T T) (:READER-ERROR T T) (:READER-ERROR T T))",
			"(((A . B) 7) ((A B . C) 9) ((A .B) 6) ((A B) 10))", "((A 1) ((A) 4) (X 9) (\"a\\\\\" 7))");

	/** One line per probe. */
	public static final String RESTART_PROGRAM = """
			(defvar *rfm-ran* nil)
			(print (handler-bind ((end-of-file (lambda (c) (declare (ignore c)) (invoke-restart 'use-value 43))))
			         (restart-case (read-from-string (copy-seq "(a")) (use-value (v) v))))
			(print (handler-bind ((reader-error (lambda (c) (invoke-restart 'use-value (list 44 (typep c 'reader-error))))))
			         (restart-case (funcall #'read-from-string (copy-seq ")")) (use-value (v) v))))
			(print (handler-case (handler-bind ((error (lambda (c) (declare (ignore c)) (setq *rfm-ran* t))))
			                       (read-from-string (copy-seq "")))
			         (end-of-file (c) (list :handler-case (typep c 'end-of-file) *rfm-ran*))))
			""";

	/** What {@link #RESTART_PROGRAM} prints (sbcl's answers). */
	public static final String RESTART_EXPECTED = String.join("\n", "43", "(44 T)", "(:HANDLER-CASE T T)");

	/** One refusal per line: its report. */
	public static final String REPORT_PROGRAM = """
			(defun rfr-probe (thunk) (handler-case (funcall thunk) (error (c) (princ-to-string c))))
			(print (rfr-probe (lambda () (read-from-string (copy-seq ")")))))
			(print (rfr-probe (lambda () (read-from-string (copy-seq "(a")))))
			(print (rfr-probe (lambda () (funcall #'read-from-string (copy-seq "")))))
			(print (rfr-probe (lambda () (read-from-string (copy-seq "( . a)")))))
			(print (rfr-probe (lambda () (read-from-string (copy-seq "(a . b c)")))))
			""";

	/** What {@link #REPORT_PROGRAM} prints. */
	public static final String REPORT_EXPECTED = String.join("\n", "\"Unexpected ')'\"", "\"end of file\"",
			"\"end of file\"", "\"Nothing appears before '.' in list\"",
			"\"More than one object follows '.' in list\"");

}
