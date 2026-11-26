package am.ik.rontolisp;

/**
 * Bad {@code :start} / {@code :end} bounds to {@code parse-integer}, and the order its
 * arguments are evaluated in, shared by the backend suites.
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the `parse-integer-refuses-a-bad-bound` ci-spec
 * case): a negative, non-integer (a nil start included) or past-int-range bound, one past
 * the string's length (a fill pointer's) and a start past its end are a
 * {@code type-error} -- over a fresh string, a literal and a fill-pointer string, with
 * {@code :junk-allowed} or {@code :radix}, in call position and first class. The datum is
 * printed only where it is the bound itself (a range is a cons in sbcl's report and the
 * refused bound here). The last rows are answers in range, a nil {@code :end} being the
 * length.</li>
 * <li>{@link #REPORT_PROGRAM}: what that {@code type-error} carries -- the refused bound,
 * its range and {@code subseq}'s report text.</li>
 * <li>{@link #ORDER_PROGRAM}: every argument is evaluated once, in the order the call
 * spells it, before a bound is checked; a repeated keyword's first value is the one used.
 * A first-class call indexes a supplementary character as one and skips the whitespace a
 * call does.</li>
 * </ul>
 * The bounds are read at run time so no backend can fold them.
 */
public final class ParseIntegerBoundsFixture {

	private ParseIntegerBoundsFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defvar *pib-m1* (read-from-string "-1"))
			(defvar *pib-f* (read-from-string "1.5"))
			(defvar *pib-9* (read-from-string "9"))
			(defvar *pib-4* (read-from-string "4"))
			(defvar *pib-3* (read-from-string "3"))
			(defvar *pib-2* (read-from-string "2"))
			(defvar *pib-1* (read-from-string "1"))
			(defvar *pib-nil* (read-from-string "nil"))
			(defvar *pib-big* (expt 2 64))
			(defvar *pib-s* (copy-seq "123"))
			(defvar *pib-fp* (make-array 5 :element-type 'character :initial-contents "12345" :fill-pointer 3))
			(defun pib-probe (thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (type-error (c)
			      (let ((d (type-error-datum c)))
			        (if (or (consp d) (and (integerp d) (<= 0 d 1000))) :type-error (list :type-error d))))
			    (error () :other-error)))
			(defmacro pib-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(pib-probe (lambda () ,f))) forms))))
			(pib-row (parse-integer *pib-s* :start *pib-9*)
			         (parse-integer *pib-s* :end *pib-9*)
			         (parse-integer *pib-s* :start *pib-m1*)
			         (parse-integer *pib-s* :start *pib-3* :end *pib-1*))
			(pib-row (parse-integer *pib-s* :start *pib-f*)
			         (parse-integer *pib-s* :end *pib-f*)
			         (parse-integer *pib-s* :end *pib-m1*)
			         (parse-integer *pib-s* :start *pib-nil*))
			(pib-row (parse-integer *pib-s* :start *pib-big*)
			         (parse-integer *pib-s* :end *pib-4*)
			         (parse-integer *pib-s* :start *pib-9* :junk-allowed t)
			         (parse-integer *pib-s* :start *pib-m1* :junk-allowed t :radix 16))
			(pib-row (parse-integer "123" :start *pib-m1*)
			         (parse-integer "123" :end *pib-9*)
			         (parse-integer *pib-fp* :end *pib-4*)
			         (parse-integer *pib-s* :start *pib-2* :end *pib-1* :junk-allowed t))
			(pib-row (funcall #'parse-integer *pib-s* :start *pib-9*)
			         (funcall #'parse-integer *pib-s* :start *pib-m1*)
			         (apply #'parse-integer *pib-s* (list :end *pib-9*))
			         (funcall #'parse-integer *pib-s* :start *pib-nil*))
			(pib-row (funcall #'parse-integer *pib-fp* :end *pib-4*)
			         (funcall #'parse-integer *pib-s* :end *pib-f* :junk-allowed t)
			         (apply #'parse-integer "123" (list :start *pib-3* :end *pib-2*))
			         (funcall #'parse-integer *pib-s* :end *pib-big*))
			(pib-row (parse-integer *pib-s* :end *pib-nil*)
			         (parse-integer *pib-s* :start *pib-3* :junk-allowed t)
			         (parse-integer *pib-s* :start *pib-1*)
			         (parse-integer *pib-s* :start *pib-1* :end *pib-1* :junk-allowed t))
			(pib-row (parse-integer *pib-fp*)
			         (funcall #'parse-integer *pib-s* :start *pib-1*)
			         (funcall #'parse-integer *pib-s* :end *pib-nil*)
			         (funcall #'parse-integer *pib-fp* :start *pib-3* :junk-allowed t))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", "(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR)",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR -1) (:TYPE-ERROR NIL))",
			"((:TYPE-ERROR 18446744073709551616) :TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1))",
			"((:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR :TYPE-ERROR)",
			"(:TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR (:TYPE-ERROR NIL))",
			"(:TYPE-ERROR (:TYPE-ERROR 1.5) :TYPE-ERROR (:TYPE-ERROR 18446744073709551616))",
			"((123 3) (NIL 3) (23 3) (NIL 1))", "((123 3) (23 3) (123 3) (NIL 3))");

	/** One refusal per line: datum, expected type and report. */
	public static final String REPORT_PROGRAM = """
			(defvar *pir-m1* (read-from-string "-1"))
			(defvar *pir-9* (read-from-string "9"))
			(defvar *pir-4* (read-from-string "4"))
			(defvar *pir-3* (read-from-string "3"))
			(defvar *pir-1* (read-from-string "1"))
			(defvar *pir-nil* nil)
			(defun pir-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(print (pir-probe (lambda () (parse-integer (copy-seq "123") :start *pir-9*))))
			(print (pir-probe (lambda () (parse-integer "123" :start *pir-m1* :junk-allowed t))))
			(print (pir-probe (lambda () (parse-integer (copy-seq "123") :start *pir-3* :end *pir-1*))))
			(print (pir-probe (lambda () (parse-integer (copy-seq "123") :start *pir-nil*))))
			(print (pir-probe (lambda () (funcall #'parse-integer (copy-seq "123") :end *pir-9*))))
			(print (pir-probe (lambda () (apply #'parse-integer "12" (list :start *pir-m1*)))))
			(print (pir-probe (lambda () (parse-integer (make-array 5 :element-type 'character :initial-element #\\1
			                                                        :fill-pointer 3)
			                                            :end *pir-4*))))
			""";

	/** What {@link #REPORT_PROGRAM} prints. */
	public static final String REPORT_EXPECTED = String.join("\n",
			"(9 (INTEGER 0 3) \"SUBSEQ: invalid bounds 9, 3 for string of length 3\")",
			"(-1 (INTEGER 0 3) \"SUBSEQ: invalid bounds -1, 3 for string of length 3\")",
			"(1 (INTEGER 3 3) \"SUBSEQ: invalid bounds 3, 1 for string of length 3\")",
			"(NIL (INTEGER 0 3) \"SUBSEQ: invalid bounds NIL, 3 for string of length 3\")",
			"(9 (INTEGER 0 3) \"SUBSEQ: invalid bounds 0, 9 for string of length 3\")",
			"(-1 (INTEGER 0 2) \"SUBSEQ: invalid bounds -1, 2 for string of length 2\")",
			"(4 (INTEGER 0 3) \"SUBSEQ: invalid bounds 0, 4 for string of length 3\")");

	/** Each line: the values (or {@code :error}) and the tags logged, in order. */
	public static final String ORDER_PROGRAM = """
			(defvar *pio-1* (read-from-string "1"))
			(defvar *pio-e* (format nil "~a12" (code-char 128512)))
			(defvar *pio-log* nil)
			(defun pio-n (tag v) (push tag *pio-log*) v)
			(defun pio-probe (thunk)
			  (setq *pio-log* nil)
			  (let ((r (handler-case (multiple-value-list (funcall thunk))
			             (error () :error))))
			    (list r (reverse *pio-log*))))
			(print (pio-probe (lambda () (parse-integer (pio-n :s "12345") :end (pio-n :e 4) :start (pio-n :st 1)
			                                            :radix (pio-n :r 10)))))
			(print (pio-probe (lambda () (parse-integer (pio-n :s "12345") :start (pio-n :a 1) :start (pio-n :b 2)))))
			(print (pio-probe (lambda () (parse-integer (pio-n :s "12345") :junk-allowed (pio-n :j t) :start (pio-n :a 1)))))
			(print (pio-probe (lambda () (funcall #'parse-integer (pio-n :s "12345") :end (pio-n :e 4) :start (pio-n :st 1)
			                                      :start (pio-n :b 2)))))
			(print (pio-probe (lambda () (parse-integer (pio-n :s "12345") :end (pio-n :e 9) :start (pio-n :st 1)))))
			(print (pio-probe (lambda () (parse-integer *pio-e* :start *pio-1*))))
			(print (pio-probe (lambda () (funcall #'parse-integer *pio-e* :start *pio-1*))))
			(print (pio-probe (lambda () (funcall #'parse-integer (format nil "12~a" (code-char 11))))))
			(print (pio-probe (lambda () (funcall #'parse-integer (format nil "~a12~a" (code-char 12) (code-char 13))))))
			""";

	/** What {@link #ORDER_PROGRAM} prints (sbcl's answers). */
	public static final String ORDER_EXPECTED = String.join("\n", "((234 4) (:S :E :ST :R))", "((2345 5) (:S :A :B))",
			"((2345 5) (:S :J :A))", "((234 4) (:S :E :ST :B))", "(:ERROR (:S :E :ST))", "((12 3) NIL)", "((12 3) NIL)",
			"(:ERROR NIL)", "((12 4) NIL)");

}
