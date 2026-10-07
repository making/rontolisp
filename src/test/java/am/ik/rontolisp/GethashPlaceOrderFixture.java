package am.ik.rontolisp;

/**
 * A {@code gethash} place evaluates its subforms -- the key, the table and the default --
 * left to right, then the value (CLHS 5.1.1.1), and the default of a write is evaluated
 * though never stored. The interpreter and the JVM never evaluated the default of a
 * {@code setf} (or of an {@code incf} whose new value is a call); Preview 1 and the
 * component evaluated the value before the table and, in a read, the default before the
 * table. Shared by the backend suites, so every backend is held to one expected text.
 */
public final class GethashPlaceOrderFixture {

	private GethashPlaceOrderFixture() {
	}

	/**
	 * A traced key, table, default and value, through {@code setf}, a read and macros.
	 */
	public static final String EVALUATION_ORDER = """
			(defvar *gpo-log* nil)
			(defun gpo-tr (x v) (push x *gpo-log*) v)
			(defun gpo-take () (prog1 (format nil "~{~A~}" (reverse *gpo-log*)) (setq *gpo-log* nil)))
			(let ((h (make-hash-table)))
			  (print (list (setf (gethash (gpo-tr "a" 1) (gpo-tr "b" h)) (gpo-tr "c" 2)) (gpo-take)))
			  (print (list (setf (gethash (gpo-tr "a" 1) (gpo-tr "b" h) (gpo-tr "x" 0)) (gpo-tr "c" 3)) (gpo-take)))
			  (print (list (gethash (gpo-tr "a" 1) (gpo-tr "b" h) (gpo-tr "x" 0)) (gpo-take)))
			  (print (list (incf (gethash (gpo-tr "a" 1) (gpo-tr "b" h) (gpo-tr "x" 0)) (gpo-tr "c" 10)) (gpo-take)))
			  (print (list (push (gpo-tr "c" 7) (gethash (gpo-tr "a" 2) (gpo-tr "b" h) (gpo-tr "x" nil))) (gpo-take)))
			  (let ((d 0)) (print (list (setf (gethash 5 h d) (gpo-tr "c" 6)) (gpo-take))))
			  (print (setf (gethash 6 h (gpo-tr "x" 0)) 8)))
			(print (gpo-take))
			(print (let* ((h1 (make-hash-table)) (h2 (make-hash-table)) (h h1))
			         (setf (gethash 1 h) (progn (setq h h2) 2))
			         (list (gethash 1 h1) (gethash 1 h2))))
			(print (let* ((h1 (make-hash-table)) (h2 (make-hash-table)) (h h1))
			         (setf (gethash 1 (progn (setq h h2) h1)) h)
			         (list (eq (gethash 1 h1) h2) (gethash 1 h2))))
			(print (let* ((h1 (make-hash-table)) (h2 (make-hash-table)) (h h1))
			         (gethash 1 h (progn (setq h h2) 3))))
			(print (let* ((h1 (make-hash-table)) (h2 (make-hash-table)) (h h1))
			         (setf (gethash 1 h2) 9)
			         (list (gethash 1 h (progn (setq h h2) 3)) (gethash 1 (progn (setq h h1) h2) (progn (setq h h2) 3)))))
			(print (list (handler-case (setf (gethash 1 (gpo-tr "t" 5)) (gpo-tr "v" 2)) (error () :error)) (gpo-take)))
			(print (list (handler-case (gethash 1 (gpo-tr "t" 5) (gpo-tr "d" 2)) (error () :error)) (gpo-take)))
			(print (let ((h (make-hash-table)) (k 1)) (setf (gethash k h k) (progn (setq k 2) k)) (gethash 1 h)))
			""";

	/** What {@link #EVALUATION_ORDER} prints, one value per line (SBCL's). */
	public static final String EVALUATION_ORDER_EXPECTED = String.join("\n", "(2 \"abc\")", "(3 \"abxc\")",
			"(3 \"abx\")", "(13 \"abxc\")", "((7) \"cabx\")", "(6 \"c\")", "8", "\"x\"", "(2 NIL)", "(T NIL)", "3",
			"(3 9)", "(:ERROR \"tv\")", "(:ERROR \"td\")", "2");

}
