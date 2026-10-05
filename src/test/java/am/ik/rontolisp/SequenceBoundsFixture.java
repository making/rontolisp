package am.ik.rontolisp;

/**
 * The sequence operators' bound checks shared by the backend suites. The answers are
 * sbcl's, except {@link #BOUND_REPORT_PROGRAM}'s.
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
 * <li>{@link #BAD_BOUND_PROGRAM} (mirrored by the `sequence-operators-refuse-a-bad-bound`
 * ci-spec case): a negative, non-integer or out-of-range bound and a start past its end
 * are a `type-error` for the count / remove / substitute family, `remove-duplicates`,
 * `fill`, `replace` and the position / find family, before any designator runs or any
 * element is written, while every range inside the sequence still answers.</li>
 * <li>{@link #BOUND_REPORT_PROGRAM}: what that `type-error` carries -- the refused bound,
 * its range and {@code subseq}'s report, the same on every backend.</li>
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

	/**
	 * A bad bound read at run time, for every sequence operator taking one, in call
	 * position and first class. A probe prints the datum of a negative or non-integer
	 * bound, which sbcl reports as given; an out-of-range one prints the class alone,
	 * since sbcl's datum there is the pair of bounds where every backend here names the
	 * refused bound ({@link #BOUND_REPORT_PROGRAM}). Then the ranges inside a sequence
	 * that still answer, the argument order of a bounded {@code count} (the item before
	 * the sequence), and a refused call that has written nothing.
	 */
	public static final String BAD_BOUND_PROGRAM = """
			(defvar *sbb-m1* (read-from-string "-1"))
			(defvar *sbb-f* (read-from-string "1.5"))
			(defvar *sbb-9* (read-from-string "9"))
			(defvar *sbb-3* (read-from-string "3"))
			(defvar *sbb-1* (read-from-string "1"))
			(defvar *sbb-5* (read-from-string "5"))
			(defvar *sbb-big* (expt 2 64))
			(defun sbb-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c)
			      (let ((d (type-error-datum c)))
			        (if (or (consp d) (and (integerp d) (<= 0 d 1000))) :type-error (list :type-error d))))
			    (error () :other-error)))
			(defmacro sbb-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(sbb-probe (lambda () ,f))) forms))))
			(sbb-row (count 2 (list 1 2 3 2 1) :start *sbb-m1*)
			         (count 2 (list 1 2 3 2 1) :end *sbb-m1*)
			         (count 2 (list 1 2 3 2 1) :start *sbb-f*)
			         (count 2 (list 1 2 3 2 1) :end *sbb-f*))
			(sbb-row (count 2 (list 1 2 3 2 1) :start *sbb-9*)
			         (count 2 (list 1 2 3 2 1) :end *sbb-9*)
			         (count 2 (list 1 2 3 2 1) :start *sbb-3* :end *sbb-1*)
			         (count 2 (list 1 2 3 2 1) :start *sbb-big*))
			(sbb-row (count 2 (vector 1 2 3 2 1) :end *sbb-9*)
			         (count #\\a "abcab" :start *sbb-9*)
			         (count-if #'evenp (list 1 2 3 2 1) :start *sbb-m1*)
			         (count-if-not #'evenp (vector 1 2 3 2 1) :end *sbb-9*))
			(sbb-row (count 2 (list 1 2 3 2 1) :end *sbb-9* :from-end t)
			         (count 2 (vector 1 2 3 2 1) :start *sbb-3* :end *sbb-1* :from-end t)
			         (remove 2 (list 1 2 3 2 1) :start *sbb-m1*)
			         (remove 2 (list 1 2 3 2 1) :end *sbb-9*))
			(sbb-row (remove 2 (vector 1 2 3 2 1) :start *sbb-9*)
			         (remove #\\a "abcab" :end *sbb-9*)
			         (remove-if #'evenp (list 1 2 3 2 1) :start *sbb-f*)
			         (remove-if-not #'evenp (list 1 2 3 2 1) :end *sbb-m1*))
			(sbb-row (remove 2 (list 1 2 3 2 1) :start *sbb-9* :from-end t)
			         (remove 2 (list 1 2 3 2 1) :start *sbb-3* :end *sbb-1* :count 1)
			         (delete 2 (list 1 2 3 2 1) :start *sbb-m1*)
			         (delete-if #'evenp (vector 1 2 3 2 1) :end *sbb-9*))
			(sbb-row (delete-if-not #'evenp (list 1 2 3 2 1) :start *sbb-9*)
			         (substitute 0 2 (list 1 2 3 2 1) :start *sbb-m1*)
			         (substitute 0 2 (vector 1 2 3 2 1) :end *sbb-9*)
			         (substitute-if 0 #'evenp (list 1 2 3 2 1) :start *sbb-3* :end *sbb-1*))
			(sbb-row (substitute-if-not 0 #'evenp (list 1 2 3 2 1) :start *sbb-f*)
			         (nsubstitute 0 2 (list 1 2 3 2 1) :start *sbb-m1*)
			         (nsubstitute-if 0 #'evenp (vector 1 2 3 2 1) :end *sbb-9*)
			         (nsubstitute-if-not 0 #'evenp (list 1 2 3 2 1) :start *sbb-9* :from-end t))
			(sbb-row (remove-duplicates (list 1 2 3 2 1) :start *sbb-f*)
			         (remove-duplicates (list 1 2 3 2 1) :start *sbb-m1*)
			         (remove-duplicates (vector 1 2 3 2 1) :end *sbb-9*)
			         (delete-duplicates (list 1 2 3 2 1) :start *sbb-3* :end *sbb-1* :from-end t))
			(sbb-row (fill (list 1 2 3) 0 :start *sbb-m1*)
			         (fill (list 1 2 3) 0 :start *sbb-9*)
			         (fill (vector 1 2 3) 0 :end *sbb-9*)
			         (fill (make-string 3 :initial-element #\\a) #\\z :start *sbb-3* :end *sbb-1*))
			(sbb-row (fill (vector 1 2 3) 0 :start *sbb-f*)
			         (replace (list 1 2 3) (list 7 8) :start1 *sbb-9*)
			         (replace (vector 1 2 3) (vector 7 8) :end2 *sbb-9*)
			         (replace (list 1 2 3) (list 7 8) :start1 *sbb-m1*))
			(sbb-row (replace (make-string 3 :initial-element #\\a) "xy" :start2 *sbb-3* :end2 *sbb-1*)
			         (replace (vector 1 2 3) (list 7 8) :end1 *sbb-9*)
			         (position 2 (list 1 2 3 2 1) :start *sbb-m1*)
			         (position 2 (vector 1 2 3 2 1) :end *sbb-9*))
			(sbb-row (position 2 (list 1 2 3 2 1) :start *sbb-3* :end *sbb-1*)
			         (position #\\a "abcab" :start *sbb-9*)
			         (position-if #'evenp (list 1 2 3 2 1) :start *sbb-f*)
			         (position-if-not #'evenp (vector 1 2 3 2 1) :end *sbb-m1*))
			(sbb-row (find 2 (vector 1 2 3 2 1) :start *sbb-9*)
			         (find-if #'evenp (list 1 2 3 2 1) :start *sbb-3* :end *sbb-1*)
			         (find-if-not #'evenp "abc" :end *sbb-9* :key #'char-code)
			         (position 7 (list 1 2 3 2 1) :end *sbb-9*))
			(sbb-row (funcall #'count 2 (list 1 2 3 2 1) :start *sbb-9*)
			         (funcall #'count-if #'evenp (vector 1 2 3 2 1) :end *sbb-9*)
			         (apply #'remove 2 (list 1 2 3 2 1) (list :start *sbb-m1*))
			         (funcall #'remove-if #'evenp (list 1 2 3 2 1) :start *sbb-3* :end *sbb-1*))
			(sbb-row (funcall #'delete 2 (vector 1 2 3 2 1) :end *sbb-9*)
			         (funcall #'substitute 0 2 (list 1 2 3 2 1) :start *sbb-f*)
			         (funcall #'nsubstitute 0 2 (list 1 2 3 2 1) :start *sbb-9*)
			         (funcall #'remove-duplicates (list 1 2 3 2 1) :end *sbb-9*))
			(sbb-row (funcall #'fill (list 1 2 3) 0 :start *sbb-9*)
			         (funcall #'replace (list 1 2 3) (list 7 8) :end2 *sbb-9*)
			         (funcall #'position 2 (vector 1 2 3 2 1) :start *sbb-9*)
			         (funcall #'find-if #'evenp (list 1 2 3 2 1) :start *sbb-m1*))
			(sbb-row (count 2 (list 1 2 3 2 1) :start *sbb-5*)
			         (count 2 (list 1 2 3 2 1) :end *sbb-5*)
			         (count 2 (vector 1 2 3 2 1) :start *sbb-5* :end *sbb-5*)
			         (count 2 (list) :start 0 :end 0))
			(sbb-row (remove 2 (list 1 2 3 2 1) :start *sbb-1* :end *sbb-3*)
			         (substitute 0 2 (vector 1 2 3 2 1) :start *sbb-3* :from-end t)
			         (fill (list 1 2 3) 0 :start *sbb-1*)
			         (replace (list 1 2 3) (list 7 8 9) :start1 *sbb-1* :end2 *sbb-1*))
			(sbb-row (position 2 (list 1 2 3 2 1) :start *sbb-3*)
			         (find #\\c "abcab" :end *sbb-5*)
			         (remove-duplicates (list 1 2 3 2 1) :start *sbb-1* :end *sbb-5*)
			         (funcall #'count 2 (make-array 5 :initial-contents '(2 2 2 2 2) :fill-pointer 3) :end *sbb-3*))
			(sbb-row (count 2 (make-array 5 :initial-contents '(2 2 2 2 2) :fill-pointer 3) :end *sbb-5*)
			         (fill (make-array 5 :initial-element 1 :fill-pointer 2) 0 :end *sbb-3*)
			         (position 2 (make-array 5 :initial-element 2 :fill-pointer 2) :start *sbb-3*)
			         (reduce #'+ (list 1 2 3) :start *sbb-9*))
			(defvar *sbb-log* nil)
			(defun sbb-note (tag value) (push tag *sbb-log*) value)
			(print (list (count (sbb-note :item 2) (sbb-note :seq (list 1 2 2)) :start 1)
			             (count-if (sbb-note :pred #'evenp) (sbb-note :seq (vector 1 2)) :end 2)
			             (count (sbb-note :item 2) (sbb-note :seq (list 2 1)) :from-end t)
			             (reverse *sbb-log*)))
			(defvar *sbb-cells* (list 1 2 3))
			(defvar *sbb-vec* (vector 1 2 3))
			(print (list (sbb-probe (lambda () (nsubstitute 0 2 *sbb-cells* :end *sbb-9*)))
			             (sbb-probe (lambda () (fill *sbb-vec* 0 :start *sbb-1* :end *sbb-9*)))
			             (sbb-probe (lambda () (replace *sbb-cells* (list 7 8 9) :start1 *sbb-1* :end2 *sbb-9*)))
			             *sbb-cells* *sbb-vec*))
			""";

	/** What {@link #BAD_BOUND_PROGRAM} prints (sbcl's answers). */
	public static final String BAD_BOUND_EXPECTED = String.join("\n",
			"((:TYPE-ERROR -1) (:TYPE-ERROR -1) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5))",
			"(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR 18446744073709551616))",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR 1.5) (:TYPE-ERROR -1))",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR)",
			"(:TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR)",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR)",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR)",
			"((:TYPE-ERROR -1) :TYPE-ERROR :TYPE-ERROR :TYPE-ERROR)",
			"((:TYPE-ERROR 1.5) :TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1))",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR 1.5) (:TYPE-ERROR -1))",
			"(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1) :TYPE-ERROR)",
			"(:TYPE-ERROR (:TYPE-ERROR 1.5) :TYPE-ERROR :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1))", "(0 2 0 0)",
			"((1 3 2 1) #(1 2 3 0 1) (1 0 0) (1 7 3))", "(3 #\\c (1 3 2 1) 3)",
			"(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR :TYPE-ERROR)", "(2 1 1 (:ITEM :SEQ :PRED :SEQ :ITEM :SEQ))",
			"(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR (1 2 3) #(1 2 3))");

	/**
	 * What a bad bound's {@code type-error} carries: its datum is the first bound outside
	 * its range -- the start outside {@code [0, length]}, else the end outside
	 * {@code [start, length]} -- its expected type that range, and its report
	 * {@code subseq}'s, naming the bounds as given and the sequence as it was passed (a
	 * fill-pointer vector by its fill pointer, a character vector as a string). A list
	 * shorter than a {@code position} / {@code find} bound is refused as every other
	 * operator refuses it, where sbcl's walk answers {@code nil} or the element it found
	 * first.
	 */
	public static final String BOUND_REPORT_PROGRAM = """
			(defvar *sbr-m1* (read-from-string "-1"))
			(defvar *sbr-f* (read-from-string "1.5"))
			(defvar *sbr-9* (read-from-string "9"))
			(defvar *sbr-4* (read-from-string "4"))
			(defvar *sbr-3* (read-from-string "3"))
			(defvar *sbr-2* (read-from-string "2"))
			(defvar *sbr-1* (read-from-string "1"))
			(defun sbr-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(print (sbr-probe (lambda () (count 2 (list 1 2 3) :start *sbr-9*))))
			(print (sbr-probe (lambda () (count 2 (vector 1 2 3) :start *sbr-2* :end *sbr-1*))))
			(print (sbr-probe (lambda () (remove #\\a "abc" :end *sbr-9*))))
			(print (sbr-probe (lambda () (substitute 0 2 (list 1 2 3) :start *sbr-f* :from-end t))))
			(print (sbr-probe (lambda () (fill (list 1 2 3) 0 :start *sbr-m1*))))
			(print (sbr-probe (lambda () (replace (vector 1 2 3) (list 7 8) :start2 *sbr-3*))))
			(print (sbr-probe (lambda () (count 2 (make-array 5 :initial-element 2 :fill-pointer 3) :end *sbr-4*))))
			(print (sbr-probe (lambda () (count #\\a (make-string 3 :initial-element #\\a) :end *sbr-4*))))
			(print (sbr-probe (lambda () (funcall #'remove-duplicates (vector 1 2 1) :end (expt 2 64)))))
			(print (sbr-probe (lambda () (funcall #'count-if #'evenp (vector 1 2 3) :start *sbr-1* :end 0))))
			(print (sbr-probe (lambda () (position #\\a "abc" :start *sbr-4*))))
			(print (sbr-probe (lambda () (position 2 (list 1 2 3) :start *sbr-9*))))
			(print (sbr-probe (lambda () (find 2 (list 1 2 3) :end *sbr-9*))))
			(print (sbr-probe (lambda () (funcall #'position-if #'evenp (list 1 2 3) :start *sbr-4*))))
			""";

	/** What {@link #BOUND_REPORT_PROGRAM} prints, one refusal per line. */
	public static final String BOUND_REPORT_EXPECTED = String.join("\n", report("9", "0 3", "9, 3", "list", 3),
			report("1", "2 3", "2, 1", "vector", 3), report("9", "0 3", "0, 9", "string", 3),
			report("1.5", "0 3", "1.5, 3", "list", 3), report("-1", "0 3", "-1, 3", "list", 3),
			report("3", "0 2", "3, 2", "list", 2), report("4", "0 3", "0, 4", "vector", 3),
			report("4", "0 3", "0, 4", "string", 3),
			report("18446744073709551616", "0 3", "0, 18446744073709551616", "vector", 3),
			report("0", "1 3", "1, 0", "vector", 3), report("4", "0 3", "4, 3", "string", 3),
			report("9", "0 3", "9, 3", "list", 3), report("9", "0 3", "0, 9", "list", 3),
			report("4", "0 3", "4, 3", "list", 3));

	// (DATUM (INTEGER LO HI) "SUBSEQ: invalid bounds S, E for KIND of length N")
	private static String report(String datum, String range, String bounds, String kind, int length) {
		return "(" + datum + " (INTEGER " + range + ") \"SUBSEQ: invalid bounds " + bounds + " for " + kind
				+ " of length " + length + "\")";
	}

}
