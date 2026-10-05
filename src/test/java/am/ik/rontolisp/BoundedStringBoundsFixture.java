package am.ik.rontolisp;

/**
 * The bounded string operators' bounds refusal, shared by the backend suites:
 * {@code write-string}, {@code write-line}, {@code string-upcase},
 * {@code string-downcase} and {@code string-capitalize} with a {@code :start} /
 * {@code :end} outside the string refuse as a {@code type-error} with the text
 * {@code subseq} gives the same range ({@link SubseqBoundsFixture}) on every backend,
 * through a direct call and through {@code funcall}; a bound that is no integer is
 * refused the same way, and so is an integer past the int range (it never reads as its
 * low bits). The bounds are computed at run time so no backend can fold them. The program
 * defines a Gray stream class, so the compile paths carry the Gray write-line dispatch
 * the {@code write-line} sites then route through (a program without one lowers them
 * directly).
 */
public final class BoundedStringBoundsFixture {

	private BoundedStringBoundsFixture() {
	}

	/** The program: one row per operator, one cell per refused range. */
	public static final String PROGRAM = """
			(defclass bsb-gray (rontolisp:fundamental-character-output-stream) ())
			(defmethod rontolisp:stream-write-string ((s bsb-gray) str) str)
			(defun bsb-probe (thunk)
			  (handler-case (progn (funcall thunk) :ok)
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(defvar *bsb-lo* 3)
			(defvar *bsb-hi* 9)
			(defvar *bsb-neg* -1)
			(defvar *bsb-nil* nil)
			(defvar *bsb-one* 1)
			(defvar *bsb-str* "a")
			(defvar *bsb-flt* 1.5)
			(defvar *bsb-2-32* (expt 2 32))
			(defvar *bsb-2-62* (expt 2 62))
			(defun bsb-row (op)
			  (list (bsb-probe (lambda () (funcall op "hello" *bsb-lo* *bsb-one*)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-one* *bsb-hi*)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-neg* nil)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-hi* nil)))
			        (bsb-probe (lambda () (funcall op "hello" 0 *bsb-neg*)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-str* nil)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-one* *bsb-flt*)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-one* 3)))
			        (bsb-probe (lambda () (funcall op "hello" 0 *bsb-2-32*)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-2-62* nil)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-nil* nil)))
			        (bsb-probe (lambda () (funcall op "hello" *bsb-nil* 3)))))
			(dolist (op (list (lambda (s i e) (with-output-to-string (o) (write-string s o :start i :end e)))
			                  (lambda (s i e) (with-output-to-string (o) (write-line s o :start i :end e)))
			                  (lambda (s i e) (string-upcase s :start i :end e))
			                  (lambda (s i e) (string-downcase s :start i :end e))
			                  (lambda (s i e) (string-capitalize s :start i :end e))
			                  (lambda (s i e) (with-output-to-string (o) (funcall #'write-string s o :start i :end e)))
			                  (lambda (s i e) (with-output-to-string (o) (funcall #'write-line s o :start i :end e)))
			                  (lambda (s i e) (funcall #'string-upcase s :start i :end e))
			                  (lambda (s i e) (funcall #'string-downcase s :start i :end e))
			                  (lambda (s i e) (funcall #'string-capitalize s :start i :end e))))
			  (print (bsb-row op)))
			""";

	private static final String RANGE_3_1 = "(1 (INTEGER 3 5) \"SUBSEQ: invalid bounds 3, 1 for string of length 5\")";

	private static final String RANGE_1_9 = "(9 (INTEGER 1 5) \"SUBSEQ: invalid bounds 1, 9 for string of length 5\")";

	private static final String RANGE_M1 = "(-1 (INTEGER 0 5) \"SUBSEQ: invalid bounds -1, 5 for string of length 5\")";

	private static final String RANGE_9 = "(9 (INTEGER 0 5) \"SUBSEQ: invalid bounds 9, 5 for string of length 5\")";

	private static final String RANGE_0_M1 = "(-1 (INTEGER 0 5) \"SUBSEQ: invalid bounds 0, -1 for string of length 5\")";

	private static final String START_A = "(\"a\" (INTEGER 0 5) \"SUBSEQ: invalid bounds \\\"a\\\", 5 for string of length 5\")";

	private static final String END_FLOAT = "(1.5 (INTEGER 1 5) \"SUBSEQ: invalid bounds 1, 1.5 for string of length 5\")";

	private static final String END_2_32 = "(4294967296 (INTEGER 0 5) \"SUBSEQ: invalid bounds 0, 4294967296 for string of length 5\")";

	private static final String START_2_62 = "(4611686018427387904 (INTEGER 0 5) \"SUBSEQ: invalid bounds 4611686018427387904, 5 for string of length 5\")";

	// A nil :start is no bound at all (only a nil :end means the string's length), so it
	// is
	// refused whether or not an :end follows.
	private static final String START_NIL = "(NIL (INTEGER 0 5) \"SUBSEQ: invalid bounds NIL, 5 for string of length 5\")";

	private static final String START_NIL_END_3 = "(NIL (INTEGER 0 5) \"SUBSEQ: invalid bounds NIL, 3 for string of length 5\")";

	private static String row() {
		return "(" + RANGE_3_1 + " " + RANGE_1_9 + " " + RANGE_M1 + " " + RANGE_9 + " " + RANGE_0_M1 + " " + START_A
				+ " " + END_FLOAT + " :OK " + END_2_32 + " " + START_2_62 + " " + START_NIL + " " + START_NIL_END_3
				+ ")";
	}

	/** What {@link #PROGRAM} prints, one row per operator. */
	public static final String EXPECTED = String.join("\n", row(), row(), row(), row(), row(), row(), row(), row(),
			row(), row());

}
