package am.ik.rontolisp;

/**
 * A rank-1 {@code aref} over an array of another rank, or over a subscript that is no
 * integer, against the interpreter: the access reports {@code aref: expected N
 * subscripts, got M} or {@code AREF}'s {@code INTEGER} type-error. A compiled integer
 * tree's aref read the flat storage of a rank-2 array as if it were rank 1 and checked a
 * non-integer subscript only against the bound, and a wasm rank mismatch trapped where a
 * handler should have taken it. Shared by the backend suites, so every backend at every
 * optimize level is held to one expected text.
 */
public final class ArefRankAndSubscriptChecksFixture {

	private ArefRankAndSubscriptChecksFixture() {
	}

	/** The program, under handlers (WASM compiles it in EH mode): one report per line. */
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
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			aref: expected 2 subscripts, got 1
			AREF: The value NIL is not of type INTEGER
			AREF: The value 2.0 is not of type INTEGER
			AREF: The value NIL is not of type INTEGER
			AREF: The value X is not of type INTEGER
			4
			6
			3""";

}
