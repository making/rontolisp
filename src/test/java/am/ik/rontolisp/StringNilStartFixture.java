package am.ik.rontolisp;

/**
 * A nil {@code :start} ({@code :start1}, {@code :start2}) to the string operators, shared
 * by the backend suites and mirrored by the `string-operators-refuse-a-nil-start` ci-spec
 * case: it is no bound, so {@code string=} / {@code string-equal}, the {@code string<}
 * family and {@code nstring-upcase} / {@code -downcase} / {@code -capitalize} signal a
 * {@code type-error} whose datum is NIL -- in call position and first class, over empty
 * strings too -- while a nil {@code :end} still means the string's length. The last rows
 * are answers: the {@code nstring-*} window and a nil {@code :end} on each surface. The
 * bounds are read at run time so no backend can fold them.
 */
public final class StringNilStartFixture {

	private StringNilStartFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defvar *snn-nil* nil)
			(defun snn-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list :type-error (type-error-datum c)))
			    (error () :other-error)))
			(defmacro snn-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(snn-probe (lambda () ,f))) forms))))
			(snn-row (string= "abc" "abc" :start1 *snn-nil*)
			         (string= "abc" "abc" :start2 *snn-nil*)
			         (string-equal "abc" "ABC" :start1 *snn-nil*)
			         (string-equal "abc" "ABC" :start2 *snn-nil* :end2 2))
			(snn-row (string< "abc" "abd" :start1 *snn-nil*)
			         (string< "abc" "abd" :start2 *snn-nil*)
			         (string/= "abc" "abd" :start1 *snn-nil*)
			         (string-lessp "abc" "ABD" :start2 *snn-nil*))
			(snn-row (string-not-greaterp "abc" "abd" :start1 *snn-nil*)
			         (string>= "abd" "abc" :start2 *snn-nil*)
			         (funcall #'string= "abc" "abc" :start1 *snn-nil*)
			         (funcall #'string-equal "abc" "ABC" :start2 *snn-nil*))
			(snn-row (funcall #'string< "abc" "abd" :start2 *snn-nil*)
			         (apply #'string-greaterp "abd" "ABC" (list :start1 *snn-nil*))
			         (nstring-upcase (copy-seq "abc") :start *snn-nil*)
			         (nstring-downcase (copy-seq "ABC") :start *snn-nil* :end 2))
			(snn-row (nstring-capitalize (copy-seq "abc def") :start *snn-nil*)
			         (funcall #'nstring-upcase (copy-seq "abc") :start *snn-nil*)
			         (string= "" "" :start1 *snn-nil*)
			         (string< "" "" :start2 *snn-nil*))
			(snn-row (string= "abc" "abc" :end1 *snn-nil*)
			         (string< "abc" "abd" :end2 *snn-nil*)
			         (funcall #'string-equal "abc" "ABC" :end1 *snn-nil* :end2 *snn-nil*)
			         (apply #'string= "xabc" "abc" (list :start1 1 :end1 *snn-nil*)))
			(snn-row (nstring-upcase (copy-seq "abcdef") :start 1 :end 3)
			         (nstring-downcase (copy-seq "ABCDEF") :start 1 :end *snn-nil*)
			         (nstring-capitalize (copy-seq "abc def") :start 4)
			         (funcall #'nstring-upcase (copy-seq "abc")))
			(snn-row (let ((s (copy-seq "abcdef"))) (nstring-upcase s :start 2) s)
			         (funcall #'nstring-downcase (copy-seq "ABCDEF") :end 2)
			         (string-not-equal "abc" "xbc" :start1 1 :start2 1 :end1 *snn-nil*)
			         (string> "abd" "abc" :end1 *snn-nil*))
			""";

	private static final String REFUSED_ROW = "((:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL))";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", REFUSED_ROW, REFUSED_ROW, REFUSED_ROW, REFUSED_ROW,
			REFUSED_ROW, "(T 2 T T)", "(\"aBCdef\" \"Abcdef\" \"abc Def\" \"ABC\")", "(\"abCDEF\" \"abCDEF\" NIL 2)");

}
