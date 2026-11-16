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
 * compiled call checked the rank before it evaluated the subscripts. The last rows are a
 * read and a store of whatever a parameter holds: a general vector with storage of its
 * own, which wasm reads and stores inline, and everything that arm hands on -- a fill
 * pointer, a view, a packed vector, a string, a subscript out of range, wide or no
 * integer, another rank, no array -- and a store whose value form leaves the block around
 * it, which wasm answered nil for, or never returned from inside a loop. Then the same
 * read, store, push and length of a vector whose storage, dimension or header was
 * replaced under it -- a push that grows, an {@code adjust-array} in place, to a view and
 * back, a full view that grows -- since the cell of a wasm vector names its storage and
 * an access trusts it. Shared by the backend suites, so every backend at every optimize
 * level is held to one expected text.
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
			(ao-check (list (aref *ao-m* 1 1) (aref *ao-v8* 2) (aref *ao-md* 1 1) (funcall #'aref *ao-m* 1 0)))
			(defvar *ao-fp* (make-array 4 :fill-pointer 2 :adjustable t :initial-element 0))
			(defvar *ao-dv* (make-array 2 :displaced-to *ao-v* :displaced-index-offset 1))
			(defvar *ao-big* (expt 2 70))
			(defun ao-get (v i) (aref v i))
			(defun ao-put (v i x) (setf (aref v i) x) (list (aref v i) (length v)))
			(defun ao-exit (v) (block nil (setf (aref v 0) (return :exited)) :stored))
			(defun ao-loop-exit (v) (dotimes (i 3 :done) (setf (aref v i) (if (= i 1) (return v) :set))))
			(ao-check (list (ao-get *ao-v* 2) (ao-get *ao-fp* 3) (ao-get *ao-dv* 1) (ao-get *ao-v8* 0) (ao-get "abc" 1)))
			(ao-check (ao-get *ao-v* 3))
			(ao-check (ao-get *ao-v* -1))
			(ao-check (ao-get *ao-v* *ao-big*))
			(ao-check (ao-get *ao-v* 'x))
			(ao-check (ao-get *ao-fp* 4))
			(ao-check (ao-get *ao-dv* 2))
			(ao-check (ao-get *ao-m* 0))
			(ao-check (ao-get 5 0))
			(ao-check (list (ao-put (vector 1 2 3) 1 :x) (ao-put *ao-fp* 3 :y) (ao-put *ao-dv* 1 :z) *ao-v*))
			(ao-check (ao-put (vector 1 2 3) 3 :x))
			(ao-check (ao-put (vector 1 2 3) 1.5 :x))
			(ao-check (ao-put *ao-m* 0 :x))
			(ao-check (list (ao-exit (vector 1 2 3)) (ao-loop-exit (vector 1 2 3))))
			(defvar *ao-g* (make-array 2 :adjustable t :fill-pointer 0))
			(defvar *ao-a* (make-array 3 :adjustable t :initial-contents '(1 2 3)))
			(defvar *ao-t* (vector 10 20 30 40))
			(defvar *ao-b* (make-array 2 :adjustable t :initial-element 7))
			(defvar *ao-d* (make-array 2 :displaced-to *ao-t* :fill-pointer 2 :adjustable t))
			(defun ao-push (v x) (vector-push-extend x v))
			(defun ao-len (v) (declare (type (vector t) v)) (length v))
			(ao-check (list (ao-push *ao-g* :a) (ao-push *ao-g* :b) (ao-get *ao-g* 1) (ao-push *ao-g* :c) (ao-push *ao-g* :d)
			                (ao-push *ao-g* :e) (ao-put *ao-g* 0 :y) (ao-put *ao-g* 4 :z) (row-major-aref *ao-g* 0)
			                (ao-get *ao-g* 7) (ao-len *ao-g*) *ao-g*))
			(ao-check (ao-get *ao-g* 8))
			(ao-check (list (ao-get *ao-a* 2) (eq *ao-a* (adjust-array *ao-a* 5 :initial-element 0)) (ao-put *ao-a* 4 :w)
			                (eq *ao-a* (adjust-array *ao-a* 2)) (ao-len *ao-a*) *ao-a*))
			(ao-check (ao-get *ao-a* 2))
			(ao-check (ao-put *ao-a* 4 :x))
			(ao-check (list (ao-get *ao-b* 1) (eq *ao-b* (adjust-array *ao-b* 3 :displaced-to *ao-t* :displaced-index-offset 1))
			                (ao-get *ao-b* 2) (ao-put *ao-b* 0 :v) (ao-len *ao-b*) *ao-t*))
			(ao-check (ao-get *ao-b* 3))
			(ao-check (list (eq *ao-b* (adjust-array *ao-b* 2)) (ao-put *ao-b* 1 :u) *ao-b* *ao-t*))
			(ao-check (list (ao-push *ao-d* :p) (ao-get *ao-d* 2) (ao-put *ao-d* 0 :q) (ao-len *ao-d*) *ao-d* *ao-t*))
			(ao-check (list (ao-len *ao-v*) (ao-len *ao-fp*) (ao-push *ao-fp* :r) (ao-len *ao-fp*)))
			(ao-check (ao-push *ao-v* 4))
			(ao-check (ao-push 5 4))""";

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
			(1 3 1.0 1)
			(3 0 3 1 #\\b)
			AREF: The value 3 is not of type (INTEGER 0 (3)) | (INTEGER 0 (3))
			AREF: The value -1 is not of type (INTEGER 0 (3)) | (INTEGER 0 (3))
			AREF: The value 1180591620717411303424 is not of type (INTEGER 0 (3)) | (INTEGER 0 (3))
			AREF: The value X is not of type INTEGER | INTEGER
			AREF: The value 4 is not of type (INTEGER 0 (4)) | (INTEGER 0 (4))
			AREF: The value 2 is not of type (INTEGER 0 (2)) | (INTEGER 0 (2))
			AREF: The value #2A((1 1) (1 1)) is not of type VECTOR | VECTOR
			AREF: The value 5 is not of type ARRAY | ARRAY
			((:X 3) (:Y 2) (:Z 2) #(1 2 :Z))
			(SETF AREF): The value 3 is not of type (INTEGER 0 (3)) | (INTEGER 0 (3))
			(SETF AREF): The value 1.5 is not of type INTEGER | INTEGER
			(SETF AREF): The value #2A((1 1) (1 1)) is not of type VECTOR | VECTOR
			(:EXITED #(:SET 2 3))
			(0 1 :B 2 3 4 (:Y 5) (:Z 5) :Y NIL 5 #(:Y :B :C :D :Z))
			AREF: The value 8 is not of type (INTEGER 0 (8)) | (INTEGER 0 (8))
			(3 T (:W 5) T 2 #(1 2))
			AREF: The value 2 is not of type (INTEGER 0 (2)) | (INTEGER 0 (2))
			(SETF AREF): The value 4 is not of type (INTEGER 0 (2)) | (INTEGER 0 (2))
			(7 T 40 (:V 3) 3 #(10 :V 30 40))
			AREF: The value 3 is not of type (INTEGER 0 (3)) | (INTEGER 0 (3))
			(T (:U 2) #(:V :U) #(10 :V 30 40))
			(2 :P (:Q 3) 3 #(:Q :V :P) #(10 :V 30 40))
			(3 2 2 3)
			VECTOR-PUSH-EXTEND: The value #(1 2 :Z) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)) | (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))
			VECTOR-PUSH-EXTEND: The value 5 is not of type ARRAY | ARRAY""";

}
