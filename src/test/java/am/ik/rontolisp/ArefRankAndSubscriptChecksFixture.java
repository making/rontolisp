package am.ik.rontolisp;

/**
 * An {@code aref} over an array of another rank, or over a subscript that is no integer,
 * against the interpreter: the access is a {@code type-error} -- {@code AREF}'s
 * {@code INTEGER} one over the subscript, or one over the array whose expected type is
 * the array of the rank the subscripts spell ({@code VECTOR} for one, {@code (ARRAY *
 * (* *))} for two). A compiled integer tree's aref read the flat storage of a rank-2
 * array as if it were rank 1 and checked a non-integer subscript only against the bound,
 * and a wasm rank mismatch trapped where a handler should have taken it; a rank mismatch
 * was a {@code program-error} interpreted and a {@code simple-error} compiled, and a
 * compiled call checked the rank before it evaluated the subscripts. Shared by the
 * backend suites, so every backend at every optimize level is held to one expected text.
 */
public final class ArefRankAndSubscriptChecksFixture {

	private ArefRankAndSubscriptChecksFixture() {
	}

	/**
	 * The integer-tree reads, under handlers (WASM compiles it in EH mode): one report
	 * per line.
	 */
	public static final String SOURCE = """
			(defvar *ar-m* (make-array '(2 2) :initial-element 1))
			(defvar *ar-m8* (make-array '(2 2) :element-type '(unsigned-byte 8) :initial-element 1))
			(defvar *ar-md* (make-array '(2 2) :element-type 'double-float :initial-element 1d0))
			(defvar *ar-v* (vector 1 2 3))
			(defvar *ar-v8* (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3)))
			(defmacro ar-report (form)
			  `(handler-case (format t "~a~%" ,form)
			     (error (e) (format t "~a~%" e))))
			(defun ar-twice (v x) (* 2 (aref v x)))
			(defun ar-next (v x) (* 2 (aref v (+ x 1))))
			(defun ar-sum (v) (let ((s 0)) (dotimes (i 2) (setq s (+ s (aref v i)))) s))
			(defun ar-small-p (v) (< (aref v 1) 5))
			(defun ar-read (v x) (aref v x))
			(ar-report (ar-twice *ar-m* 1))
			(ar-report (ar-twice *ar-m8* 1))
			(ar-report (ar-twice *ar-md* 1))
			(ar-report (ar-next *ar-m8* 0))
			(ar-report (ar-sum *ar-m*))
			(ar-report (ar-sum *ar-m8*))
			(ar-report (ar-sum *ar-md*))
			(ar-report (ar-small-p *ar-m8*))
			(ar-report (ar-read *ar-m* 1))
			(ar-report (setf (aref *ar-m* 1) 5))
			(ar-report (ar-twice *ar-v* nil))
			(ar-report (ar-twice *ar-v* 2.0))
			(ar-report (ar-twice *ar-v8* nil))
			(ar-report (ar-twice "abc" 'x))
			(ar-report (ar-twice *ar-v* 1))
			(ar-report (ar-next *ar-v8* 1))
			(ar-report (ar-sum *ar-v8*))""";

	/** What every backend prints for {@link #SOURCE}. */
	public static final String EXPECTED = """
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR
			AREF: The value #d((1.0 1.0) (1.0 1.0)) is not of type VECTOR
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR
			AREF: The value #d((1.0 1.0) (1.0 1.0)) is not of type VECTOR
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR
			(SETF AREF): The value #2A((1 1) (1 1)) is not of type VECTOR
			AREF: The value NIL is not of type INTEGER
			AREF: The value 2.0 is not of type INTEGER
			AREF: The value NIL is not of type INTEGER
			AREF: The value X is not of type INTEGER
			4
			6
			3""";

	/**
	 * Call-position accesses, stores and {@code #'aref} /
	 * {@code #'array-row-major-index}, under handlers: each report with its expected
	 * type, then the order the argument forms ran in. Every argument form is evaluated
	 * before any check; then each subscript's type, the array's, its rank, a store's
	 * value and the bounds, in that order.
	 */
	public static final String ORDER_SOURCE = """
			(defvar *ao-m* (make-array '(2 2) :initial-element 1))
			(defvar *ao-md* (make-array '(2 2) :element-type 'double-float :initial-element 1d0))
			(defvar *ao-v* (vector 1 2 3))
			(defvar *ao-v8* (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3)))
			(defvar *ao-log* nil)
			(defun ao-note (x) (push x *ao-log*) x)
			(defun ao-boom () (error "boom"))
			(defmacro ao-check (form)
			  `(handler-case (format t "~s~%" ,form)
			     (type-error (e) (format t "~a | ~s~%" e (type-error-expected-type e)))
			     (error (e) (format t "~a~%" e))))
			(ao-check (aref *ao-m* 1))
			(ao-check (aref *ao-m*))
			(ao-check (aref *ao-v* 0 0 0))
			(ao-check (aref *ao-v8* 0 0))
			(ao-check (aref "abc" 0 0))
			(ao-check (aref *ao-md* 0))
			(ao-check (aref *ao-md* 0 0 0))
			(ao-check (aref *ao-m* nil))
			(ao-check (aref *ao-v* 0 'x))
			(ao-check (aref 5 nil))
			(ao-check (aref *ao-m* (ao-boom)))
			(ao-check (aref *ao-v* 0 (ao-boom)))
			(ao-check (aref *ao-m* (ao-note 7)))
			(ao-check (setf (aref *ao-m* 1) 5))
			(ao-check (setf (aref *ao-v8* 0 0) 5))
			(ao-check (setf (aref *ao-md* 0) "x"))
			(ao-check (setf (aref *ao-md* 0 0 0) 2d0))
			(ao-check (setf (aref (copy-seq "abc") 0 0) #\\x))
			(ao-check (setf (aref *ao-m* nil) 5))
			(ao-check (setf (aref *ao-m* 0) (ao-boom)))
			(ao-check (setf (aref *ao-m* (ao-note 8) 0 0) (ao-note 9)))
			(ao-check (funcall #'aref *ao-m* 1))
			(ao-check (funcall #'aref *ao-m* nil))
			(ao-check (funcall #'aref 5 nil))
			(ao-check (funcall #'array-row-major-index *ao-m* 1))
			(ao-check (funcall #'array-row-major-index *ao-m* nil))
			(ao-check (reverse *ao-log*))
			(ao-check (list (aref *ao-m* 1 1) (aref *ao-v8* 2) (aref *ao-md* 1 1) (funcall #'aref *ao-m* 1 0)))""";

	/** What every backend prints for {@link #ORDER_SOURCE}. */
	public static final String ORDER_EXPECTED = """
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR | VECTOR
			AREF: The value #2A((1 1) (1 1)) is not of type (ARRAY * NIL) | (ARRAY * NIL)
			AREF: The value #(1 2 3) is not of type (ARRAY * (* * *)) | (ARRAY * (* * *))
			AREF: The value #(1 2 3) is not of type (ARRAY * (* *)) | (ARRAY * (* *))
			AREF: The value "abc" is not of type (ARRAY * (* *)) | (ARRAY * (* *))
			AREF: The value #d((1.0 1.0) (1.0 1.0)) is not of type VECTOR | VECTOR
			AREF: The value #d((1.0 1.0) (1.0 1.0)) is not of type (ARRAY * (* * *)) | (ARRAY * (* * *))
			AREF: The value NIL is not of type INTEGER | INTEGER
			AREF: The value X is not of type INTEGER | INTEGER
			AREF: The value NIL is not of type INTEGER | INTEGER
			boom
			boom
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR | VECTOR
			(SETF AREF): The value #2A((1 1) (1 1)) is not of type VECTOR | VECTOR
			(SETF AREF): The value #(1 2 3) is not of type (ARRAY * (* *)) | (ARRAY * (* *))
			(SETF AREF): The value #d((1.0 1.0) (1.0 1.0)) is not of type VECTOR | VECTOR
			(SETF AREF): The value #d((1.0 1.0) (1.0 1.0)) is not of type (ARRAY * (* * *)) | (ARRAY * (* * *))
			(SETF AREF): The value "abc" is not of type (ARRAY * (* *)) | (ARRAY * (* *))
			(SETF AREF): The value NIL is not of type INTEGER | INTEGER
			boom
			(SETF AREF): The value #2A((1 1) (1 1)) is not of type (ARRAY * (* * *)) | (ARRAY * (* * *))
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR | VECTOR
			AREF: The value NIL is not of type INTEGER | INTEGER
			AREF: The value NIL is not of type INTEGER | INTEGER
			ARRAY-ROW-MAJOR-INDEX: The value #2A((1 1) (1 1)) is not of type VECTOR | VECTOR
			ARRAY-ROW-MAJOR-INDEX: The value NIL is not of type INTEGER | INTEGER
			(7 8 9)
			(1 3 1.0 1)""";

}
