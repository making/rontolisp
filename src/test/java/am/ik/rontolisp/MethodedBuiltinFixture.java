package am.ik.rontolisp;

/**
 * User methods on built-ins the interpreter EXPANDS rather than calls ({@code byte-size}
 * into a {@code car}) and on multiple-value producers it lowers by name ({@code floor},
 * {@code gethash}), shared by the backend suites: every backend answers the user method
 * for an instance and the built-in for everything else, in a direct call, a function's
 * tail, a lambda's tail and under a multiple-value consumer, and a methoded reader stays
 * a {@code setf} place.
 */
public final class MethodedBuiltinFixture {

	private MethodedBuiltinFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defclass mb-box () ())
			(defun mb-early-floor (x) (floor x))
			(defmethod byte-size ((b mb-box)) 42)
			(defmethod byte-position ((b mb-box)) 43)
			(defmethod floor ((b mb-box) &optional d) (declare (ignore d)) 44)
			(defmethod gethash (k (b mb-box) &optional d) (declare (ignore k d)) 45)
			(defun mb-floor (x) (floor x))
			(defun mb-put (h k v) (setf (gethash k h) v))
			(defvar *mb-table* (make-hash-table))
			(defvar *mb-box* (make-instance 'mb-box))
			(print (list (byte-size *mb-box*) (byte-position *mb-box*) (byte-size (byte 3 4)) (byte-position (byte 3 4))))
			(print (list (floor *mb-box*) (mb-floor *mb-box*) (funcall (lambda (x) (floor x)) *mb-box*)))
			(print (list (multiple-value-list (floor *mb-box*)) (multiple-value-list (mb-floor *mb-box*))))
			(print (list (multiple-value-list (floor 7.5)) (multiple-value-list (mb-floor 7.5))
			             (multiple-value-list (mb-early-floor 7.5))))
			(mb-put *mb-table* :a 1)
			(incf (gethash :a *mb-table*))
			(print (list (gethash :a *mb-table*) (gethash :a *mb-box*) (multiple-value-list (gethash :zz *mb-table*))))
			""";

	/** What {@link #PROGRAM} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(42 43 3 4)", "(44 44 44)", "((44) (44))",
			"((7 0.5) (7 0.5) (7 0.5))", "(2 45 (NIL NIL))");

}
