package am.ik.rontolisp;

/**
 * User methods on built-ins whose generic takes a tail ({@code &optional}, {@code &key},
 * {@code &rest}), shared by the backend suites: a call the methods do not claim reaches
 * the built-in with EVERY argument -- {@code floor}'s divisor, {@code gethash}'s default,
 * {@code close}'s {@code :abort}, {@code write-line}'s stream and bounds, the second
 * argument of a generic that requires fewer than the built-in -- and a count the built-in
 * rejects reports as the built-in. {@link #CLOSE_PROGRAM} is {@code close}'s
 * {@code :abort} pair in a direct call, without a method.
 */
public final class MethodedBuiltinTailFixture {

	private MethodedBuiltinTailFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defclass mt-box () ())
			(defvar *mt-box* (make-instance 'mt-box))
			(defmethod floor ((b mt-box) &optional d) (declare (ignore d)) 44)
			(defmethod gethash (k (b mt-box) &optional d) (declare (ignore k d)) 45)
			(defmethod close ((b mt-box) &key abort) (declare (ignore abort)) :closed)
			(defmethod write-line ((b mt-box) &optional s) (declare (ignore s)) :written)
			(defmethod open-stream-p ((b mt-box) &rest r) (declare (ignore r)) :open)
			(defmethod rplaca ((b mt-box) &rest r) (declare (ignore r)) :replaced)
			(defun mt-report (thunk)
			  (handler-case (funcall thunk) (program-error (e) (princ-to-string e))))
			(print (list (multiple-value-list (floor 7 2)) (multiple-value-list (floor 7))
			             (multiple-value-list (funcall #'floor 9 4)) (floor *mt-box* 2)))
			(let ((h (make-hash-table)))
			  (setf (gethash :a h) 1)
			  (print (list (multiple-value-list (gethash :x h :dflt)) (multiple-value-list (gethash :a h 0))
			               (gethash :x *mt-box* :dflt))))
			(print (list (close (make-string-output-stream) :abort t) (close *mt-box* :abort t)
			             (open-stream-p *mt-box* 1)))
			(print (with-output-to-string (s)
			         (write-line "abc" s)
			         (write-line "hello" s :start 1 :end 4)
			         (write-line *mt-box* s)))
			(print (list (rplaca (list 1 2) 9) (rplaca *mt-box*)))
			(print (mt-report (lambda () (open-stream-p *standard-output* 1))))
			(print (mt-report (lambda () (rplaca (list 1)))))
			(print (mt-report (lambda () (close *standard-output* :abort))))
			(print (mt-report (lambda () (close *standard-output* :force t))))
			""";

	/** What {@link #PROGRAM} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "((3 1) (7 0) (2 1) 44)", "((:DFLT NIL) (1 T) 45)",
			"(T :CLOSED :OPEN)", "\"abc", "ell", "\"", "((9 2) :REPLACED)",
			"\"OPEN-STREAM-P expects 1 argument, got 2\"", "\"RPLACA expects 2 arguments, got 1\"",
			"\"CLOSE expects 1 or 3 arguments, got 2\"", "\"CLOSE expects 1 argument, got 3\"");

	/** A direct {@code close} with and without its {@code :abort} pair. */
	public static final String CLOSE_PROGRAM = """
			(defun mt-report (thunk)
			  (handler-case (funcall thunk) (program-error (e) (princ-to-string e))))
			(let ((s (make-string-output-stream)))
			  (print (list (close s :abort t) (close (make-string-output-stream) :abort nil))))
			(print (mt-report (lambda () (close (make-string-output-stream) :abort))))
			(print (mt-report (lambda () (close (make-string-output-stream) :force t))))
			""";

	/** What {@link #CLOSE_PROGRAM} prints, one value per line. */
	public static final String CLOSE_EXPECTED = String.join("\n", "(T T)", "\"CLOSE expects 1 or 3 arguments, got 2\"",
			"\"CLOSE expects 1 argument, got 3\"");

}
