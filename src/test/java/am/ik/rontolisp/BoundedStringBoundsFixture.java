package am.ik.rontolisp;

/**
 * The bounded string operators' bounds refusal, shared by the backend suites:
 * {@code write-string}, {@code write-line}, {@code string-upcase},
 * {@code string-downcase} and {@code string-capitalize} with a {@code :start} /
 * {@code :end} outside the string refuse as a {@code type-error} with the text
 * {@code subseq} gives the same range ({@link SubseqBoundsFixture}) on every backend,
 * through a direct call and through {@code funcall}. The bounds are computed at run time
 * so no backend can fold them.
 */
public final class BoundedStringBoundsFixture {

	private BoundedStringBoundsFixture() {
	}

	/** The program: one row per operator, one cell per refused range. */
	public static final String PROGRAM = """
			(defun bsb-probe (thunk)
			  (handler-case (progn (funcall thunk) :ok)
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(defvar *bsb-lo* 3)
			(defvar *bsb-hi* 9)
			(defvar *bsb-neg* -1)
			(defvar *bsb-one* 1)
			(defun bsb-row (op)
			  (list (bsb-probe (lambda () (funcall op "hello" *bsb-lo* *bsb-one*)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-one* *bsb-hi*)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-neg* nil)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-hi* nil)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-one* 3)))))
			(dolist (op (list (lambda (s i e) (with-output-to-string (o) (write-string s o :start i :end e)))
			                  (lambda (s i e) (with-output-to-string (o) (write-line s o :start i :end e)))
			                  (lambda (s i e) (string-upcase s :start i :end e))
			                  (lambda (s i e) (string-downcase s :start i :end e))
			                  (lambda (s i e) (string-capitalize s :start i :end e))
			                  (lambda (s i e) (with-output-to-string (o) (funcall #'write-string s o :start i :end e)))
			                  (lambda (s i e) (with-output-to-string (o) (funcall #'write-line s o :start i :end e)))
			                  (lambda (s i e) (funcall #'string-upcase s :start i :end e))))
			  (print (bsb-row op)))
			""";

	private static final String RANGE_3_1 = "(1 (INTEGER 3 5) \"SUBSEQ: invalid bounds 3, 1 for string of length 5\")";

	private static final String RANGE_1_9 = "(9 (INTEGER 1 5) \"SUBSEQ: invalid bounds 1, 9 for string of length 5\")";

	private static final String RANGE_M1 = "(-1 (INTEGER 0 5) \"SUBSEQ: invalid bounds -1, 5 for string of length 5\")";

	private static final String RANGE_9 = "(9 (INTEGER 0 5) \"SUBSEQ: invalid bounds 9, 5 for string of length 5\")";

	private static String row() {
		return "(" + RANGE_3_1 + " " + RANGE_1_9 + " " + RANGE_M1 + " " + RANGE_9 + " :OK)";
	}

	/** What {@link #PROGRAM} prints, one row per operator. */
	public static final String EXPECTED = String.join("\n", row(), row(), row(), row(), row(), row(), row(), row());

}
