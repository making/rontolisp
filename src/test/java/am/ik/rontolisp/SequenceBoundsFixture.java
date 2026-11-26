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
 * <li>{@link #GRAY_BOUNDS_PROGRAM} (mirrored by the `gray-stream-bounds-are-checked`
 * ci-spec case): the same refusals for the bounds of `write-line` / `write-string` on a
 * Gray stream instance, which reach the user's method as checked integers.</li>
 * <li>{@link #BAD_BOUND_PROGRAM} (mirrored by the `sequence-operators-refuse-a-bad-bound`
 * ci-spec case): a negative, non-integer or out-of-range bound and a start past its end
 * are a `type-error` for the count / remove / substitute family, `remove-duplicates`,
 * `fill`, `replace` and the position / find family, before any designator runs or any
 * element is written, while every range inside the sequence still answers.</li>
 * <li>{@link #BAD_COUNT_PROGRAM}: a `:count` that is neither an integer nor nil is a
 * `type-error` over the value for the remove / delete / substitute families, in call
 * position and first class, before the bounds and any designator; nil, a negative and a
 * bignum count still answer.</li>
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
	 * A bound spelled on {@code write-line} / {@code write-string} to a Gray stream
	 * instance is checked once before the method runs, whatever the stream: a nil,
	 * negative or non-integer {@code :start} (datum as given) and a range outside the
	 * string or a start past its end are a {@code type-error} with nothing written, and a
	 * method that receives the call sees integer bounds, a nil or omitted {@code :end}
	 * being the length. Call position and first class (refusals only).
	 */
	public static final String GRAY_BOUNDS_PROGRAM = """
			(defvar *gwb-nil* nil)
			(defvar *gwb-m1* (read-from-string "-1"))
			(defvar *gwb-f* (read-from-string "1.5"))
			(defvar *gwb-1* (read-from-string "1"))
			(defvar *gwb-3* (read-from-string "3"))
			(defvar *gwb-9* (read-from-string "9"))
			(defclass gwb-rec (rontolisp:fundamental-character-output-stream)
			  ((gwb-log :initform nil)))
			(defmethod rontolisp:stream-write-string ((gwb-s gwb-rec) gwb-str &optional (gwb-start 0) gwb-end)
			  (push (list gwb-str gwb-start gwb-end) (slot-value gwb-s 'gwb-log))
			  gwb-str)
			(defmethod rontolisp:stream-write-char ((gwb-s gwb-rec) gwb-c)
			  (push (list :char gwb-c) (slot-value gwb-s 'gwb-log))
			  gwb-c)
			(defun gwb-probe (thunk datum-p)
			  (let ((gwb-o (make-instance 'gwb-rec)))
			    (list (handler-case (progn (funcall thunk gwb-o) :ok)
			            (type-error (c) (if datum-p (list :type-error (type-error-datum c)) :type-error))
			            (error () :other-error))
			          (reverse (slot-value gwb-o 'gwb-log)))))
			(defmacro gwb-row (datum-p &rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(gwb-probe (lambda (gwb-o) ,f) ,datum-p)) forms))))
			(gwb-row t (write-line "hello" gwb-o :start *gwb-nil* :end *gwb-3*)
			           (write-string "hello" gwb-o :start *gwb-nil* :end *gwb-3*)
			           (write-line "hello" gwb-o :start *gwb-nil*)
			           (write-string "hello" gwb-o :start *gwb-nil*))
			(gwb-row t (write-line "hello" gwb-o :start *gwb-m1*)
			           (write-string "hello" gwb-o :start *gwb-m1*)
			           (write-line "hello" gwb-o :start *gwb-f*)
			           (write-string "hello" gwb-o :start *gwb-f*))
			(gwb-row nil (write-line "hello" gwb-o :start *gwb-9*)
			             (write-string "hello" gwb-o :start *gwb-9*)
			             (write-line "hello" gwb-o :end *gwb-9*)
			             (write-string "hello" gwb-o :end *gwb-9*))
			(gwb-row nil (write-line "hello" gwb-o :start *gwb-3* :end *gwb-1*)
			             (write-string "hello" gwb-o :start *gwb-3* :end *gwb-1*)
			             (write-line "hello" gwb-o :end *gwb-m1*)
			             (write-string "hello" gwb-o :end *gwb-f*))
			(gwb-row nil (funcall #'write-line "hello" gwb-o :start *gwb-nil* :end *gwb-3*)
			             (funcall #'write-string "hello" gwb-o :start *gwb-nil*)
			             (funcall #'write-line "hello" gwb-o :start *gwb-9*)
			             (funcall #'write-string "hello" gwb-o :end *gwb-9*))
			(gwb-row nil (write-line "hello" gwb-o :start *gwb-1* :end *gwb-3*)
			             (write-string "hello" gwb-o :start *gwb-1* :end *gwb-3*)
			             (write-line "hello" gwb-o :start *gwb-1*)
			             (write-string "hello" gwb-o :start *gwb-1*))
			(gwb-row nil (write-line "hello" gwb-o :end *gwb-3*)
			             (write-string "hello" gwb-o :end *gwb-3*)
			             (write-line "hello" gwb-o :end *gwb-nil*)
			             (write-string "hello" gwb-o :end *gwb-nil*))
			(gwb-row nil (write-line "hello" gwb-o :start 0 :end 5)
			             (write-string "hello" gwb-o :end 0)
			             (write-line "" gwb-o :start 0)
			             (write-string "hello" gwb-o :start 5))
			""";

	/** What {@link #GRAY_BOUNDS_PROGRAM} prints (sbcl's answers). */
	public static final String GRAY_BOUNDS_EXPECTED = String.join("\n",
			"(((:TYPE-ERROR NIL) NIL) ((:TYPE-ERROR NIL) NIL) ((:TYPE-ERROR NIL) NIL) ((:TYPE-ERROR NIL) NIL))",
			"(((:TYPE-ERROR -1) NIL) ((:TYPE-ERROR -1) NIL) ((:TYPE-ERROR 1.5) NIL) ((:TYPE-ERROR 1.5) NIL))",
			"((:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL))",
			"((:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL))",
			"((:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL) (:TYPE-ERROR NIL))",
			"((:OK ((\"hello\" 1 3) (:CHAR #\\Newline))) (:OK ((\"hello\" 1 3))) (:OK ((\"hello\" 1 5) (:CHAR #\\Newline))) (:OK ((\"hello\" 1 5))))",
			"((:OK ((\"hello\" 0 3) (:CHAR #\\Newline))) (:OK ((\"hello\" 0 3))) (:OK ((\"hello\" 0 5) (:CHAR #\\Newline))) (:OK ((\"hello\" 0 5))))",
			"((:OK ((\"hello\" 0 5) (:CHAR #\\Newline))) (:OK ((\"hello\" 0 0))) (:OK ((\"\" 0 0) (:CHAR #\\Newline))) (:OK ((\"hello\" 5 5))))");

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
	 * A {@code :count} read at run time, for the remove / delete / substitute families in
	 * call position and first class: anything but an integer or nil -- a float, a ratio,
	 * a symbol -- is a {@code type-error} over the value before the bounds are checked
	 * and before a designator runs, while nil, a negative count and a bignum still read
	 * as CLHS 17.2.1 has them (no limit, zero, more than any list holds). The answers are
	 * sbcl's.
	 */
	public static final String BAD_COUNT_PROGRAM = """
			(defvar *sbk-f* (read-from-string "1.5"))
			(defvar *sbk-r* (read-from-string "1/2"))
			(defvar *sbk-s* (read-from-string "x"))
			(defvar *sbk-m1* (read-from-string "-1"))
			(defvar *sbk-1* (read-from-string "1"))
			(defvar *sbk-9* (read-from-string "9"))
			(defvar *sbk-nil* nil)
			(defvar *sbk-big* (expt 10 30))
			(defun sbk-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list :type-error (type-error-datum c)))
			    (error () :other-error)))
			(defmacro sbk-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(sbk-probe (lambda () ,f))) forms))))
			(sbk-row (remove 2 (list 1 2 3 2) :count *sbk-f*)
			         (remove-if #'evenp (list 1 2 3 2) :count *sbk-f*)
			         (remove-if-not #'evenp (list 1 2 3 2) :count *sbk-f*)
			         (delete 2 (list 1 2 3 2) :count *sbk-f*))
			(sbk-row (delete-if #'evenp (list 1 2 3 2) :count *sbk-f*)
			         (delete-if-not #'evenp (vector 1 2 3 2) :count *sbk-f*)
			         (substitute 0 2 (list 1 2 3 2) :count *sbk-f*)
			         (substitute-if 0 #'evenp (vector 1 2 3 2) :count *sbk-f*))
			(sbk-row (substitute-if-not 0 #'evenp (list 1 2 3 2) :count *sbk-f*)
			         (nsubstitute 0 2 (list 1 2 3 2) :count *sbk-f*)
			         (nsubstitute-if 0 #'evenp (list 1 2 3 2) :count *sbk-f*)
			         (nsubstitute-if-not 0 #'evenp (vector 1 2 3 2) :count *sbk-f*))
			(sbk-row (remove 2 (list 1 2 3 2) :count *sbk-r*)
			         (remove 2 (list 1 2 3 2) :count *sbk-s*)
			         (remove #\\a "abca" :count *sbk-f*)
			         (substitute 0 2 (list 1 2 3 2) :count *sbk-f* :from-end t))
			(sbk-row (funcall #'remove 2 (list 1 2 3 2) :count *sbk-f*)
			         (funcall #'remove-if #'evenp (list 1 2 3 2) :count *sbk-r*)
			         (apply #'delete-if-not #'evenp (list 1 2 3 2) (list :count *sbk-f*))
			         (funcall #'substitute 0 2 (list 1 2 3 2) :count *sbk-f*))
			(sbk-row (funcall #'delete 2 (list 1 2 3 2) :count *sbk-s*)
			         (funcall #'delete-if #'evenp (vector 1 2 3 2) :count *sbk-f*)
			         (funcall #'nsubstitute 0 2 (list 1 2 3 2) :count *sbk-f*)
			         (funcall #'substitute-if-not 0 #'evenp (list 1 2 3 2) :count *sbk-f*))
			(sbk-row (remove 2 (list 1 2 3 2) :count *sbk-f* :start *sbk-9*)
			         (remove 2 (list 1 2 3 2) :start *sbk-9* :count *sbk-f*)
			         (funcall #'remove 2 (list 1 2 3 2) :start *sbk-9* :count *sbk-f*)
			         (delete-if #'evenp (list 1 2 3 2) :count *sbk-f* :end *sbk-9* :from-end t))
			(defvar *sbk-calls* 0)
			(print (list (sbk-probe (lambda () (remove 2 (list 1 2 3 2) :count *sbk-f* :key (lambda (x) (incf *sbk-calls*) x))))
			             (sbk-probe (lambda () (nsubstitute-if 0 (lambda (x) (incf *sbk-calls*) (evenp x)) (list 1 2) :count *sbk-f*)))
			             *sbk-calls*))
			(defvar *sbk-cells* (list 1 2 3 2))
			(print (list (sbk-probe (lambda () (nsubstitute 0 2 *sbk-cells* :count *sbk-f*))) *sbk-cells*
			             (sbk-probe (lambda () (delete 2 *sbk-cells* :count *sbk-f*))) *sbk-cells*))
			(print (list (remove 2 (list 1 2 3 2) :count *sbk-nil*)
			             (remove 2 (list 1 2 3 2) :count *sbk-m1*)
			             (remove 2 (list 1 2 3 2) :count *sbk-big*)
			             (remove 2 (list 1 2 3 2) :count *sbk-1*)))
			(print (list (funcall #'remove 2 (list 1 2 3 2) :count *sbk-nil*)
			             (funcall #'substitute 0 2 (list 1 2 3 2) :count *sbk-m1*)
			             (funcall #'delete 2 (list 1 2 3 2) :count *sbk-big*)
			             (funcall #'nsubstitute-if 0 #'evenp (list 1 2 3 2) :count *sbk-1* :from-end t)))
			(print (list (remove 2 (list 1 2 3 2) :count 0)
			             (remove 2 (list 1 2 3 2) :count nil)
			             (substitute 0 2 (list 1 2 3 2) :count 1)
			             (remove 2 (list 1 2 3 2) :count *sbk-big* :from-end t)))
			""";

	/** What {@link #BAD_COUNT_PROGRAM} prints (sbcl's answers). */
	public static final String BAD_COUNT_EXPECTED = String.join("\n",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5))",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5))",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5))",
			"((:TYPE-ERROR 1/2) (:TYPE-ERROR X) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5))",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR 1/2) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5))",
			"((:TYPE-ERROR X) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5))",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5))",
			"((:TYPE-ERROR 1.5) (:TYPE-ERROR 1.5) 0)", "((:TYPE-ERROR 1.5) (1 2 3 2) (:TYPE-ERROR 1.5) (1 2 3 2))",
			"((1 3) (1 2 3 2) (1 3) (1 3 2))", "((1 3) (1 2 3 2) (1 3) (1 2 3 0))",
			"((1 2 3 2) (1 3) (1 0 3 2) (1 3))");

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

	/**
	 * What a counted or bounded {@code delete} / {@code nsubstitute} spelling reports:
	 * its own name, not the {@code remove} / {@code substitute} its lowering delegates to
	 * -- for a value that is no sequence ({@code SEQUENCE}) and for a {@code :count} that
	 * is no integer ({@code INTEGER}), in call position and first class. sbcl names no
	 * operator in either report; the datum and the type agree with it.
	 */
	public static final String OPERATOR_REPORT_PROGRAM = """
			(defvar *sor-5* (read-from-string "5"))
			(defvar *sor-f* (read-from-string "1.5"))
			(defun sor-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list (type-error-datum c) (princ-to-string c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(defmacro sor-rows (&rest forms)
			  `(progn ,@(mapcar (lambda (f) `(print (sor-probe (lambda () ,f)))) forms)))
			(sor-rows (delete 2 *sor-5*)
			          (delete 2 *sor-5* :count 1)
			          (delete 2 *sor-5* :start 0)
			          (delete-if #'evenp *sor-5* :count 1)
			          (delete-if-not #'evenp *sor-5* :count 1)
			          (nsubstitute 1 2 *sor-5* :count 1)
			          (nsubstitute-if 1 #'evenp *sor-5* :count 1)
			          (nsubstitute-if-not 1 #'evenp *sor-5* :count 1)
			          (remove 2 *sor-5* :count 1)
			          (remove-if #'evenp *sor-5* :count 1)
			          (remove-if-not #'evenp *sor-5* :count 1)
			          (substitute 1 2 *sor-5* :count 1)
			          (substitute-if 1 #'evenp *sor-5* :count 1)
			          (substitute-if-not 1 #'evenp *sor-5* :count 1)
			          (funcall #'delete 2 *sor-5* :count 1)
			          (funcall #'delete-if #'evenp *sor-5* :count 1)
			          (funcall #'nsubstitute-if 1 #'evenp *sor-5* :count 1)
			          (delete 2 (list 1 2) :count *sor-f*)
			          (delete-if #'evenp (vector 1 2) :count *sor-f*)
			          (delete-if-not #'evenp (list 1 2) :count *sor-f*)
			          (nsubstitute 1 2 (vector 1 2) :count *sor-f*)
			          (nsubstitute-if 1 #'evenp (list 1 2) :count *sor-f*)
			          (nsubstitute-if-not 1 #'evenp (vector 1 2) :count *sor-f*))
			""";

	/** What {@link #OPERATOR_REPORT_PROGRAM} prints, one refusal per line. */
	public static final String OPERATOR_REPORT_EXPECTED = String.join("\n", operatorReport("5", "DELETE", "SEQUENCE"),
			operatorReport("5", "DELETE", "SEQUENCE"), operatorReport("5", "DELETE", "SEQUENCE"),
			operatorReport("5", "DELETE-IF", "SEQUENCE"), operatorReport("5", "DELETE-IF-NOT", "SEQUENCE"),
			operatorReport("5", "NSUBSTITUTE", "SEQUENCE"), operatorReport("5", "NSUBSTITUTE-IF", "SEQUENCE"),
			operatorReport("5", "NSUBSTITUTE-IF-NOT", "SEQUENCE"), operatorReport("5", "REMOVE", "SEQUENCE"),
			operatorReport("5", "REMOVE-IF", "SEQUENCE"), operatorReport("5", "REMOVE-IF-NOT", "SEQUENCE"),
			operatorReport("5", "SUBSTITUTE", "SEQUENCE"), operatorReport("5", "SUBSTITUTE-IF", "SEQUENCE"),
			operatorReport("5", "SUBSTITUTE-IF-NOT", "SEQUENCE"), operatorReport("5", "DELETE", "SEQUENCE"),
			operatorReport("5", "DELETE-IF", "SEQUENCE"), operatorReport("5", "NSUBSTITUTE-IF", "SEQUENCE"),
			operatorReport("1.5", "DELETE", "INTEGER"), operatorReport("1.5", "DELETE-IF", "INTEGER"),
			operatorReport("1.5", "DELETE-IF-NOT", "INTEGER"), operatorReport("1.5", "NSUBSTITUTE", "INTEGER"),
			operatorReport("1.5", "NSUBSTITUTE-IF", "INTEGER"), operatorReport("1.5", "NSUBSTITUTE-IF-NOT", "INTEGER"));

	// (DATUM "OP: The value DATUM is not of type TYPE")
	private static String operatorReport(String datum, String operator, String type) {
		return "(" + datum + " \"" + operator + ": The value " + datum + " is not of type " + type + "\")";
	}

	// (DATUM (INTEGER LO HI) "SUBSEQ: invalid bounds S, E for KIND of length N")
	private static String report(String datum, String range, String bounds, String kind, int length) {
		return "(" + datum + " (INTEGER " + range + ") \"SUBSEQ: invalid bounds " + bounds + " for " + kind
				+ " of length " + length + "\")";
	}

}
