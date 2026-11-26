package am.ik.rontolisp;

/**
 * Bad {@code :start1} / {@code :end1} / {@code :start2} / {@code :end2} bounds to the
 * string comparisons, shared by the backend suites.
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the `string-comparisons-refuse-a-bad-bound` ci-spec
 * case): a negative, non-integer or past-int-range bound, one past the string's length
 * and a start past its end are a {@code type-error} -- {@code string=} /
 * {@code string-equal} and the {@code string<} family, in call position and first class,
 * over a symbol or character designator and a fill-pointer string too, string1's range
 * before string2's. The datum is printed only where it is the bound itself (a range is a
 * cons in sbcl's report and the refused bound here). The last rows are answers in range.
 * sbcl's answers, over strings that are no literals: sbcl's {@code string<} /
 * {@code string>} / {@code string<=} over two literal strings check no range.</li>
 * <li>{@link #REPORT_PROGRAM}: what that {@code type-error} carries -- the refused bound,
 * its range and {@code subseq}'s report text.</li>
 * <li>{@link #ORDER_PROGRAM} (mirrored by the same ci-spec case): every argument is
 * evaluated once, in the order the call spells them, and the first of a repeated keyword
 * is the one used -- {@code string=} / {@code string-equal}, called and first class.</li>
 * </ul>
 * The bounds are read at run time so no backend can fold them.
 */
public final class StringComparisonBoundsFixture {

	private StringComparisonBoundsFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defvar *scb-m1* (read-from-string "-1"))
			(defvar *scb-f* (read-from-string "1.5"))
			(defvar *scb-9* (read-from-string "9"))
			(defvar *scb-3* (read-from-string "3"))
			(defvar *scb-2* (read-from-string "2"))
			(defvar *scb-1* (read-from-string "1"))
			(defvar *scb-big* (expt 2 64))
			(defvar *scb-abc* (copy-seq "abc"))
			(defvar *scb-abd* (copy-seq "abd"))
			(defvar *scb-cap* (copy-seq "ABC"))
			(defvar *scb-empty* (copy-seq ""))
			(defun scb-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c)
			      (let ((d (type-error-datum c)))
			        (if (or (consp d) (and (integerp d) (<= 0 d 1000))) :type-error (list :type-error d))))
			    (error () :other-error)))
			(defmacro scb-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(scb-probe (lambda () ,f))) forms))))
			(scb-row (string= *scb-abc* *scb-abc* :start1 *scb-m1*)
			         (string= *scb-abc* *scb-abc* :end1 *scb-9*)
			         (string= *scb-abc* *scb-abc* :start1 *scb-2* :end1 *scb-1*)
			         (string= *scb-abc* *scb-abc* :start1 *scb-f*))
			(scb-row (string= *scb-abc* *scb-abc* :start2 *scb-9*)
			         (string-equal *scb-abc* *scb-cap* :end2 *scb-m1*)
			         (string-equal *scb-abc* *scb-cap* :start1 *scb-big*)
			         (string-equal *scb-abc* *scb-cap* :start2 *scb-3* :end2 *scb-1*))
			(scb-row (string< *scb-abc* *scb-abd* :start1 *scb-m1*)
			         (string< *scb-abc* *scb-abd* :start1 *scb-2* :end1 *scb-1*)
			         (string< *scb-abc* *scb-abd* :end1 *scb-9*)
			         (string< *scb-abc* *scb-abd* :end2 *scb-9*))
			(scb-row (string> *scb-abd* *scb-abc* :start2 *scb-9*)
			         (string<= *scb-abc* *scb-abd* :start1 *scb-f*)
			         (string>= *scb-abd* *scb-abc* :end2 *scb-m1*)
			         (string/= *scb-abc* *scb-abd* :start1 *scb-9*))
			(scb-row (string-lessp *scb-abc* *scb-cap* :start1 *scb-m1*)
			         (string-greaterp *scb-abd* *scb-cap* :end1 *scb-9*)
			         (string-not-greaterp *scb-abc* *scb-cap* :start2 *scb-3* :end2 *scb-1*)
			         (string-not-lessp *scb-abd* *scb-cap* :start1 *scb-big*))
			(scb-row (string-not-equal *scb-abc* *scb-abd* :start1 *scb-9*)
			         (funcall #'string< *scb-abc* *scb-abd* :start2 *scb-m1*)
			         (apply #'string-lessp *scb-abc* *scb-cap* (list :end1 *scb-9*))
			         (funcall #'string= *scb-abc* *scb-abc* :end1 *scb-9*))
			(scb-row (funcall #'string-equal *scb-abc* *scb-cap* :start2 *scb-2* :end2 *scb-1*)
			         (string< *scb-empty* *scb-empty* :start1 *scb-1*)
			         (string= 'abc *scb-cap* :end1 *scb-9*)
			         (string< #\\a *scb-abd* :start1 *scb-m1*))
			(scb-row (string= *scb-abc* *scb-abc* :start1 *scb-m1* :end2 *scb-9*)
			         (string= *scb-abc* *scb-abc* :start1 *scb-f* :end2 *scb-m1*)
			         (string< *scb-abc* *scb-abd* :start1 *scb-m1* :start2 *scb-f*)
			         (string-not-equal *scb-abc* *scb-abd* :end1 *scb-big* :end2 *scb-m1*))
			(scb-row (string< (make-array 5 :element-type 'character :initial-element #\\a :fill-pointer 3) *scb-abd*
			                  :end1 (+ *scb-3* 1))
			         (apply #'string/= *scb-abc* *scb-abd* (list :start2 *scb-f*))
			         (funcall #'string-not-equal *scb-abc* *scb-abd* :end2 *scb-big*)
			         (string> *scb-abd* *scb-abc* :start1 *scb-1* :end1 *scb-1* :start2 *scb-m1*))
			(print (list (string< *scb-abc* *scb-abd* :start1 *scb-3*)
			             (string>= *scb-abc* *scb-abd* :start1 *scb-3*)
			             (string= *scb-abc* "xabc" :start2 *scb-1*)
			             (string-lessp *scb-abc* *scb-abd* :start1 *scb-1* :end1 *scb-1* :start2 *scb-3*)))
			(print (list (string<= *scb-empty* *scb-empty* :start1 0 :end1 0)
			             (funcall #'string/= *scb-abc* *scb-abd* :end1 *scb-3* :end2 *scb-3*)
			             (string-equal "ab" "AB" :start1 *scb-2* :start2 *scb-2*)
			             (string< (make-array 5 :element-type 'character :initial-element #\\a :fill-pointer 3) *scb-abd*
			                      :end1 *scb-3*)))
			(print (list (string= 'abc "BC" :start1 *scb-1*)
			             (funcall #'string-equal "bc" 'abc :start2 *scb-1*)
			             (string= #\\a "xa" :start2 *scb-1*)
			             (string<= 'abc 'abd :end1 *scb-2* :end2 *scb-2*)))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n",
			"((:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR 1.5))",
			"(:TYPE-ERROR (:TYPE-ERROR -1) (:TYPE-ERROR 18446744073709551616) :TYPE-ERROR)",
			"((:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR :TYPE-ERROR)",
			"(:TYPE-ERROR (:TYPE-ERROR 1.5) (:TYPE-ERROR -1) :TYPE-ERROR)",
			"((:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR 18446744073709551616))",
			"(:TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1))",
			"((:TYPE-ERROR -1) (:TYPE-ERROR 1.5) (:TYPE-ERROR -1) (:TYPE-ERROR 18446744073709551616))",
			"(:TYPE-ERROR (:TYPE-ERROR 1.5) (:TYPE-ERROR 18446744073709551616) (:TYPE-ERROR -1))", "(3 NIL T NIL)",
			"(0 2 T 1)", "(T T T 2)");

	/** One refusal per line: datum, expected type and report. */
	public static final String REPORT_PROGRAM = """
			(defvar *scr-m1* (read-from-string "-1"))
			(defvar *scr-9* (read-from-string "9"))
			(defvar *scr-4* (read-from-string "4"))
			(defvar *scr-2* (read-from-string "2"))
			(defvar *scr-1* (read-from-string "1"))
			(defvar *scr-nil* nil)
			(defun scr-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(print (scr-probe (lambda () (string< "abc" "abd" :end1 *scr-9*))))
			(print (scr-probe (lambda () (string= "abc" "abc" :start1 *scr-2* :end1 *scr-1*))))
			(print (scr-probe (lambda () (funcall #'string-equal "abc" "ABC" :start2 *scr-m1*))))
			(print (scr-probe (lambda () (string-lessp "abc" "abd" :start2 *scr-nil*))))
			(print (scr-probe (lambda () (string> 'abc "AB" :end2 *scr-4*))))
			(print (scr-probe (lambda () (string= #\\a "a" :start1 *scr-2*))))
			(print (scr-probe (lambda () (string< (make-array 5 :element-type 'character :initial-element #\\a
			                                                  :fill-pointer 3)
			                                      "b" :end1 *scr-4*))))
			(print (scr-probe (lambda () (apply #'string/= "abc" "abd" (list :start1 *scr-4*)))))
			""";

	/** What {@link #REPORT_PROGRAM} prints. */
	public static final String REPORT_EXPECTED = String.join("\n",
			"(9 (INTEGER 0 3) \"SUBSEQ: invalid bounds 0, 9 for string of length 3\")",
			"(1 (INTEGER 2 3) \"SUBSEQ: invalid bounds 2, 1 for string of length 3\")",
			"(-1 (INTEGER 0 3) \"SUBSEQ: invalid bounds -1, 3 for string of length 3\")",
			"(NIL (INTEGER 0 3) \"SUBSEQ: invalid bounds NIL, 3 for string of length 3\")",
			"(4 (INTEGER 0 2) \"SUBSEQ: invalid bounds 0, 4 for string of length 2\")",
			"(2 (INTEGER 0 1) \"SUBSEQ: invalid bounds 2, 1 for string of length 1\")",
			"(4 (INTEGER 0 3) \"SUBSEQ: invalid bounds 0, 4 for string of length 3\")",
			"(4 (INTEGER 0 3) \"SUBSEQ: invalid bounds 4, 3 for string of length 3\")");

	/** Each line: the answer (or {@code :error}) and the tags logged, in order. */
	public static final String ORDER_PROGRAM = """
			(defvar *sco-log* nil)
			(defun sco-n (tag v) (push tag *sco-log*) v)
			(defun sco-probe (thunk)
			  (setq *sco-log* nil)
			  (let ((r (handler-case (funcall thunk)
			             (error () :error))))
			    (list r (reverse *sco-log*))))
			(print (sco-probe (lambda () (string= (sco-n :s1 "abc") (sco-n :s2 "abc") :end1 (sco-n :e1 3) :start1 (sco-n :st1 0)))))
			(print (sco-probe (lambda () (string= "abc" "abc" :start1 (sco-n :a 1) :start1 (sco-n :b 0)))))
			(print (sco-probe (lambda () (string= "abc" "abc" :start1 1 :start1 0))))
			(print (sco-probe (lambda () (string= "abc" "bc" :start1 1 :start1 0))))
			(print (sco-probe (lambda () (string-equal (sco-n :s1 "ABC") (sco-n :s2 "xabc") :end2 (sco-n :e2 4) :start2 (sco-n :st2 1)
			                                           :end1 (sco-n :e1 3)))))
			(print (sco-probe (lambda () (string-equal "abc" "ABC" :end1 (sco-n :a 2) :end1 (sco-n :b 3)))))
			(print (sco-probe (lambda () (string= (sco-n :s1 "xabcx") (sco-n :s2 "yabcy") :end2 (sco-n :e2 4) :start1 (sco-n :st1 1)
			                                      :start2 (sco-n :st2 1) :end1 (sco-n :e1 4)))))
			(print (sco-probe (lambda () (string= 'abc (sco-n :s2 "xABC") :start2 (sco-n :st2 1) :end1 (sco-n :e1 3)))))
			(print (sco-probe (lambda () (funcall #'string= (sco-n :s1 "xabc") (sco-n :s2 "abc") :start2 (sco-n :st2 0) :start1 (sco-n :st1 1)))))
			(print (sco-probe (lambda () (funcall #'string-equal (sco-n :s1 "abc") (sco-n :s2 "ABC") :end1 (sco-n :a 2) :end1 (sco-n :b 3)))))
			(print (sco-probe (lambda () (string= (sco-n :s1 "abc") (sco-n :s2 "abc") :end1 (sco-n :e1 9) :start1 (sco-n :st1 4)))))
			""";

	/** What {@link #ORDER_PROGRAM} prints (sbcl's answers). */
	public static final String ORDER_EXPECTED = String.join("\n", "(T (:S1 :S2 :E1 :ST1))", "(NIL (:A :B))",
			"(NIL NIL)", "(T NIL)", "(T (:S1 :S2 :E2 :ST2 :E1))", "(NIL (:A :B))", "(T (:S1 :S2 :E2 :ST1 :ST2 :E1))",
			"(T (:S2 :ST2 :E1))", "(T (:S1 :S2 :ST2 :ST1))", "(NIL (:S1 :S2 :A :B))", "(:ERROR (:S1 :S2 :E1 :ST1))");

}
