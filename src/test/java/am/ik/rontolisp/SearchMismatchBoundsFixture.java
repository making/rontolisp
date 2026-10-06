package am.ik.rontolisp;

/**
 * Bad {@code :start1} / {@code :end1} / {@code :start2} / {@code :end2} bounds to
 * {@code search} and {@code mismatch}, shared by the backend suites.
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the `search-and-mismatch-refuse-a-bad-bound` ci-spec
 * case): a negative, non-integer or past-int-range bound, one past the sequence's length
 * and a start past its end are a {@code type-error} -- over a list, a vector and a
 * string, a fill-pointer string, in call position and first class, with a {@code :key}, a
 * {@code :test} or {@code :from-end}. The datum is printed only where it is the bound
 * itself (a range is a cons in sbcl's report and the refused bound here). The last rows
 * are answers in range. sbcl's answers, over sequences that are no literals: sbcl's
 * {@code search} over a literal needle walks a list lazily.</li>
 * <li>{@link #REPORT_PROGRAM}: what that {@code type-error} carries -- the refused bound,
 * its range and {@code subseq}'s report text.</li>
 * </ul>
 * The bounds are read at run time so no backend can fold them.
 */
public final class SearchMismatchBoundsFixture {

	private SearchMismatchBoundsFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defvar *smb-m1* (read-from-string "-1"))
			(defvar *smb-f* (read-from-string "1.5"))
			(defvar *smb-9* (read-from-string "9"))
			(defvar *smb-3* (read-from-string "3"))
			(defvar *smb-2* (read-from-string "2"))
			(defvar *smb-1* (read-from-string "1"))
			(defvar *smb-nil* (read-from-string "nil"))
			(defvar *smb-big* (expt 2 64))
			(defvar *smb-l2* (list 2))
			(defvar *smb-l12* (list 1 2))
			(defvar *smb-l123* (list 1 2 3))
			(defvar *smb-v2* (vector 2))
			(defvar *smb-v123* (vector 1 2 3))
			(defvar *smb-b* (copy-seq "b"))
			(defvar *smb-abc* (copy-seq "abc"))
			(defvar *smb-abd* (copy-seq "abd"))
			(defvar *smb-empty* (copy-seq ""))
			(defun smb-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c)
			      (let ((d (type-error-datum c)))
			        (if (or (consp d) (and (integerp d) (<= 0 d 1000))) :type-error (list :type-error d))))
			    (error () :other-error)))
			(defmacro smb-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(smb-probe (lambda () ,f))) forms))))
			(smb-row (search *smb-l2* *smb-l123* :start1 *smb-9*)
			         (search *smb-l2* *smb-l123* :start2 *smb-3* :end2 *smb-1*)
			         (search *smb-v2* *smb-v123* :end2 *smb-9*)
			         (mismatch *smb-l123* *smb-l123* :start1 *smb-9*))
			(smb-row (mismatch *smb-l123* *smb-l123* :end1 *smb-9*)
			         (mismatch *smb-v123* *smb-v123* :end2 *smb-9*)
			         (search *smb-l2* *smb-l123* :start2 *smb-9*)
			         (search *smb-l2* *smb-l123* :end2 *smb-9*))
			(smb-row (search *smb-b* *smb-abc* :start1 *smb-m1*)
			         (search *smb-b* *smb-abc* :start1 *smb-f*)
			         (search *smb-b* *smb-abc* :end1 *smb-big*)
			         (search *smb-b* *smb-abc* :start2 *smb-nil*))
			(smb-row (mismatch *smb-abc* *smb-abd* :start1 *smb-m1*)
			         (mismatch *smb-abc* *smb-abd* :end2 *smb-f*)
			         (mismatch *smb-l12* *smb-l12* :start2 *smb-3*)
			         (mismatch *smb-l12* *smb-l12* :start1 *smb-2* :end1 *smb-1*))
			(smb-row (funcall #'search *smb-l2* *smb-l123* :start1 *smb-9*)
			         (apply #'mismatch *smb-l123* *smb-l123* (list :end1 *smb-9*))
			         (mismatch *smb-v123* *smb-v123* :start1 *smb-big*)
			         (mismatch *smb-l12* *smb-l12* :start1 *smb-nil*))
			(smb-row (search *smb-b* (make-array 5 :element-type 'character :initial-element #\\b :fill-pointer 3)
			                 :end2 (+ *smb-3* 1))
			         (search *smb-l2* *smb-l123* :from-end t :start2 *smb-m1*)
			         (mismatch *smb-abc* *smb-abc* :key #'char-upcase :end1 *smb-9*)
			         (search *smb-v2* *smb-v123* :start1 *smb-m1* :end2 *smb-9*))
			(smb-row (funcall #'search *smb-b* *smb-abc* :test #'char-equal :start2 *smb-3* :end2 *smb-2*)
			         (mismatch *smb-abc* *smb-v123* :start2 *smb-1* :end2 *smb-f*)
			         (search *smb-empty* *smb-abc* :start2 *smb-9*)
			         (mismatch *smb-empty* *smb-empty* :start1 *smb-1*))
			(print (list (search *smb-l2* *smb-l123* :start2 *smb-3*)
			             (search *smb-l2* *smb-l123* :end2 *smb-3*)
			             (mismatch *smb-l123* *smb-l123* :start1 *smb-3* :start2 *smb-3*)
			             (search *smb-empty* *smb-abc* :start2 *smb-3*)))
			(print (list (mismatch *smb-abc* *smb-abd* :end1 *smb-nil* :end2 *smb-nil*)
			             (search *smb-b* *smb-abc* :start1 *smb-1* :end1 *smb-1*)
			             (mismatch *smb-l123* *smb-v123* :start1 *smb-1* :start2 *smb-1*)
			             (funcall #'search *smb-b* *smb-abc* :start2 *smb-1* :end2 *smb-2*)))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", "(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR :TYPE-ERROR)",
			"((:TYPE-ERROR -1) (:TYPE-ERROR 1.5) (:TYPE-ERROR 18446744073709551616) (:TYPE-ERROR NIL))",
			"((:TYPE-ERROR -1) (:TYPE-ERROR 1.5) :TYPE-ERROR :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR 18446744073709551616) (:TYPE-ERROR NIL))",
			"(:TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR (:TYPE-ERROR -1))",
			"(:TYPE-ERROR (:TYPE-ERROR 1.5) :TYPE-ERROR :TYPE-ERROR)", "(NIL 1 NIL 3)", "(2 0 NIL 1)");

	/** One refusal per line: datum, expected type and report. */
	public static final String REPORT_PROGRAM = """
			(defvar *smr-m1* (read-from-string "-1"))
			(defvar *smr-9* (read-from-string "9"))
			(defvar *smr-4* (read-from-string "4"))
			(defvar *smr-3* (read-from-string "3"))
			(defvar *smr-1* (read-from-string "1"))
			(defvar *smr-nil* nil)
			(defun smr-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(print (smr-probe (lambda () (search "b" "abc" :start2 *smr-9*))))
			(print (smr-probe (lambda () (mismatch (list 1 2 3) (list 1 2 3) :end1 *smr-9*))))
			(print (smr-probe (lambda () (search (vector 2) (vector 1 2 3) :start2 *smr-3* :end2 *smr-1*))))
			(print (smr-probe (lambda () (mismatch "abc" "abd" :start1 *smr-nil*))))
			(print (smr-probe (lambda () (funcall #'search "b" "abc" :start1 *smr-m1*))))
			(print (smr-probe (lambda () (apply #'mismatch (vector 1 2) (list 1 2) (list :end2 *smr-9*)))))
			(print (smr-probe (lambda () (search "b" (make-array 5 :element-type 'character :initial-element #\\b
			                                                    :fill-pointer 3)
			                                     :end2 *smr-4*))))
			(print (smr-probe (lambda () (search (list 1 2) (list 1 2 3) :start1 *smr-3*))))
			""";

	/** What {@link #REPORT_PROGRAM} prints. */
	public static final String REPORT_EXPECTED = String.join("\n",
			"(9 (INTEGER 0 3) \"SUBSEQ: invalid bounds 9, 3 for string of length 3\")",
			"(9 (INTEGER 0 3) \"SUBSEQ: invalid bounds 0, 9 for list of length 3\")",
			"(1 (INTEGER 3 3) \"SUBSEQ: invalid bounds 3, 1 for vector of length 3\")",
			"(NIL (INTEGER 0 3) \"SUBSEQ: invalid bounds NIL, 3 for string of length 3\")",
			"(-1 (INTEGER 0 1) \"SUBSEQ: invalid bounds -1, 1 for string of length 1\")",
			"(9 (INTEGER 0 2) \"SUBSEQ: invalid bounds 0, 9 for list of length 2\")",
			"(4 (INTEGER 0 3) \"SUBSEQ: invalid bounds 0, 4 for string of length 3\")",
			"(3 (INTEGER 0 2) \"SUBSEQ: invalid bounds 3, 2 for list of length 2\")");

}
