package am.ik.rontolisp;

/**
 * The sequence operators' bound checks shared by the backend suites. The answers are
 * sbcl's.
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the
 * `read-and-write-sequence-signal-type-error-for-a-bad-sequence-or-bound` ci-spec case):
 * for `read-sequence` / `write-sequence`, a dotted-list buffer, a negative, non-integer
 * or symbolic bound, and a range outside the buffer are all `type-error`s -- for a string
 * buffer, a general vector and a list alike.</li>
 * <li>{@link #NIL_START_PROGRAM} (mirrored by the `sequence-operators-refuse-a-nil-start`
 * ci-spec case): a nil `:start` (`:start1`, `:start2`) is no bound, so every sequence
 * operator taking one signals a `type-error` whose datum is NIL -- in call position and
 * through `funcall` -- while a nil `:end` still means the sequence's length.</li>
 * </ul>
 */
public final class SequenceBoundsFixture {

	private SequenceBoundsFixture() {
	}

	/** The program; string streams only, so no file is needed. */
	public static final String PROGRAM = """
			(print (handler-case (with-input-from-string (s "abc") (read-sequence '(a . b) s))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :start -1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :end -1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :start 'x))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :start 1.5))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :end 9))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :start 2 :end 1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence '(a . b) o))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :start -1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :end -1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :start 'x))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :end 1.5))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :end 9))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :start 2 :end 1))
			         (type-error () :type-error) (error () :other-error)))
			""";

	/** What {@link #PROGRAM} prints, one value per line (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR",
			":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR",
			":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR");

	/**
	 * A nil {@code :start} read at run time, for every sequence operator taking one --
	 * refused before the walk, so an empty sequence refuses it too. Each row prints four
	 * probes; the last three rows are answers (a nil {@code :end}, an absent
	 * {@code :start} through the first-class wrappers).
	 */
	public static final String NIL_START_PROGRAM = """
			(defvar *sbn-nil* nil)
			(defun sbn-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list :type-error (type-error-datum c)))
			    (error () :other-error)))
			(defmacro sbn-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(sbn-probe (lambda () ,f))) forms))))
			(sbn-row (count 2 (list 1 2 3) :start *sbn-nil*)
			         (count-if #'evenp (vector 1 2 3) :start *sbn-nil*)
			         (count-if-not #'evenp "abc" :start *sbn-nil* :key #'char-code)
			         (count 2 (list 1 2 3) :start *sbn-nil* :from-end t))
			(sbn-row (remove 2 (list 1 2 3) :start *sbn-nil*)
			         (remove-if #'evenp (vector 1 2 3) :start *sbn-nil*)
			         (remove-if-not #'evenp (list 1 2 3) :start *sbn-nil* :count 1)
			         (delete 2 (list 1 2 3) :start *sbn-nil*))
			(sbn-row (delete-if #'evenp (vector 1 2 3) :start *sbn-nil*)
			         (substitute 9 2 (list 1 2 3) :start *sbn-nil*)
			         (substitute-if 9 #'evenp (vector 1 2 3) :start *sbn-nil*)
			         (nsubstitute 9 2 (list 1 2 3) :start *sbn-nil*))
			(sbn-row (nsubstitute-if-not 9 #'oddp (list 1 2 3) :start *sbn-nil* :from-end t)
			         (remove-duplicates (list 1 2 1) :start *sbn-nil*)
			         (delete-duplicates (vector 1 2 1) :start *sbn-nil* :from-end t)
			         (fill (list 1 2 3) 0 :start *sbn-nil*))
			(sbn-row (fill (vector 1 2 3) 0 :start *sbn-nil*)
			         (fill (make-string 3 :initial-element #\\a) #\\b :start *sbn-nil*)
			         (replace (list 1 2 3) (list 7 8) :start1 *sbn-nil*)
			         (replace (vector 1 2 3) (list 7 8) :start2 *sbn-nil*))
			(sbn-row (replace (make-string 3 :initial-element #\\a) "xy" :start1 *sbn-nil*)
			         (funcall #'count 2 (list 1 2 3) :start *sbn-nil*)
			         (funcall #'count-if #'evenp (list 1 2 3) :start *sbn-nil*)
			         (funcall #'remove 2 (list 1 2 3) :start *sbn-nil*))
			(sbn-row (funcall #'delete-if-not #'evenp (list 1 2 3) :start *sbn-nil*)
			         (funcall #'substitute 9 2 (list 1 2 3) :start *sbn-nil*)
			         (funcall #'nsubstitute-if 9 #'evenp (list 1 2 3) :start *sbn-nil*)
			         (apply #'remove-duplicates (list 1 2 1) (list :start *sbn-nil*)))
			(sbn-row (funcall #'position 2 (list 1 2 3) :start *sbn-nil*)
			         (funcall #'position-if #'evenp (list 1 2 3) :start *sbn-nil*)
			         (funcall #'position-if-not #'oddp (vector 1 2 3) :start *sbn-nil*)
			         (funcall #'find 2 (list 1 2 3) :start *sbn-nil*))
			(sbn-row (funcall #'find-if #'evenp (list 1 2 3) :start *sbn-nil*)
			         (funcall #'find-if-not #'oddp "abc" :start *sbn-nil* :key #'char-code)
			         (funcall #'reduce #'+ (list 1 2 3) :start *sbn-nil*)
			         (funcall #'fill (list 1 2 3) 0 :start *sbn-nil*))
			(sbn-row (funcall #'replace (list 1 2 3) (list 7 8) :start1 *sbn-nil*)
			         (funcall #'replace (list 1 2 3) (list 7 8) :start2 *sbn-nil*)
			         (with-input-from-string (s "abc") (funcall #'read-sequence (make-string 3) s :start *sbn-nil*))
			         (with-output-to-string (o) (funcall #'write-sequence "abc" o :start *sbn-nil*)))
			(sbn-row (count 2 (list) :start *sbn-nil*)
			         (remove 2 (vector) :start *sbn-nil* :from-end t)
			         (fill (list) 0 :start *sbn-nil*)
			         (funcall #'count 2 (list) :start *sbn-nil*))
			(sbn-row (count 2 (list 1 2 3 2) :end *sbn-nil*)
			         (remove 2 (list 1 2 3 2) :start 1 :end *sbn-nil*)
			         (fill (list 1 2 3) 0 :end *sbn-nil*)
			         (replace (list 1 2 3) (list 7 8) :end1 *sbn-nil* :end2 *sbn-nil*))
			(sbn-row (funcall #'count 2 (list 1 2 3 2) :end *sbn-nil*)
			         (funcall #'position 2 (list 1 2 3 2) :end *sbn-nil* :from-end t)
			         (funcall #'find 2 (list 1 2 3) :end 3)
			         (funcall #'find-if #'evenp (list 1 2 3 4) :from-end t))
			(sbn-row (funcall #'reduce #'+ (list 1 2 3) :end *sbn-nil*)
			         (funcall #'fill (list 1 2 3) 0 :start 1 :end *sbn-nil*)
			         (funcall #'replace (list 1 2 3) (list 7 8) :end1 *sbn-nil*)
			         (with-input-from-string (s "abc") (funcall #'read-sequence (make-string 3) s :end *sbn-nil*)))
			""";

	private static final String REFUSED_ROW = "((:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL))";

	/** What {@link #NIL_START_PROGRAM} prints (sbcl's answers). */
	public static final String NIL_START_EXPECTED = String.join("\n", REFUSED_ROW, REFUSED_ROW, REFUSED_ROW,
			REFUSED_ROW, REFUSED_ROW, REFUSED_ROW, REFUSED_ROW, REFUSED_ROW, REFUSED_ROW, REFUSED_ROW, REFUSED_ROW,
			"(2 (1 3) (0 0 0) (7 8 3))", "(2 3 2 4)", "(6 (1 0 0) (7 8 3) 3)");

}
