package am.ik.rontolisp;

/**
 * The standard generic functions whose only methods are the standard ones --
 * {@code initialize-instance}, {@code reinitialize-instance}, {@code shared-initialize}
 * and {@code make-load-form} -- taken as function values and called. {@link #VALUES}
 * names them in a program with no class definition: the JVM, P1 and the component refused
 * it ({@code Cannot compile: INITIALIZE-INSTANCE as a function value}) and the
 * interpreter had no {@code make-load-form}. {@link #NO_METHOD} calls each on a
 * non-instance: SBCL signals {@code no-applicable-method}, where the compiled backends
 * answered the argument and the interpreter signalled {@code %MOP-FILL-SLOTS}'s argument
 * check. {@link #WITH_CLASS} calls them on instances, where {@code make-load-form}'s
 * standard methods signal an error (CLHS {@code make-load-form}), before and after a
 * program method on another type. The expected text is rontolisp's no-applicable-method
 * report and SBCL's {@code make-load-form} report. Shared by the backend suites, so every
 * backend is held to one expected text.
 */
public final class StandardGenericFunctionValueFixture {

	private StandardGenericFunctionValueFixture() {
	}

	/** The four names as values, with nothing else in the program. */
	public static final String VALUES = """
			(print (mapcar #'functionp (list #'initialize-instance #'reinitialize-instance
			                                 #'shared-initialize #'make-load-form)))
			""";

	/** What {@link #VALUES} prints. */
	public static final String VALUES_EXPECTED = "(T T T T)";

	/**
	 * Each name called through its value on an argument no standard method applies to.
	 */
	public static final String NO_METHOD = """
			(defun sgf-report (thunk)
			  (handler-case (funcall thunk) (error (c) (princ-to-string c))))
			(print (list (sgf-report (lambda () (funcall #'initialize-instance 1)))
			             (sgf-report (lambda () (funcall #'reinitialize-instance "s" :x 1)))
			             (sgf-report (lambda () (funcall #'shared-initialize 'sym t)))
			             (sgf-report (lambda () (funcall #'make-load-form '(1))))))
			""";

	/** What {@link #NO_METHOD} prints. */
	public static final String NO_METHOD_EXPECTED = "(\"No applicable method: INITIALIZE-INSTANCE on INTEGER\""
			+ " \"No applicable method: REINITIALIZE-INSTANCE on STRING\""
			+ " \"No applicable method: SHARED-INITIALIZE on SYMBOL\""
			+ " \"No applicable method: MAKE-LOAD-FORM on CONS\")";

	/**
	 * The values called on instances, and {@code make-load-form} after a program method.
	 */
	public static final String WITH_CLASS = """
			(defun sgf-report (thunk)
			  (handler-case (funcall thunk) (error (c) (princ-to-string c))))
			(defclass sgf-point () ((x :initarg :x :initform 0 :accessor sgf-x)))
			(defstruct sgf-cell v)
			(defparameter *sgf-p* (make-instance 'sgf-point))
			(print (list (eq *sgf-p* (funcall #'initialize-instance *sgf-p*))
			             (eq *sgf-p* (funcall #'reinitialize-instance *sgf-p* :x 3))
			             (sgf-x *sgf-p*)
			             (eq *sgf-p* (funcall #'shared-initialize *sgf-p* t))
			             (sgf-report (lambda () (funcall #'initialize-instance 1)))
			             (sgf-report (lambda () (funcall 'make-load-form *sgf-p*)))))
			(defmethod make-load-form ((c sgf-cell) &optional env)
			  (declare (ignore env))
			  (list 'make-sgf-cell :v (sgf-cell-v c)))
			(print (list (funcall #'make-load-form (make-sgf-cell :v 2))
			             (sgf-report (lambda () (make-load-form *sgf-p*)))
			             (sgf-report (lambda () (make-load-form 7)))))
			""";

	/** What {@link #WITH_CLASS} prints, one value per line. */
	public static final String WITH_CLASS_EXPECTED = String.join("\n",
			"(T T 3 T \"No applicable method: INITIALIZE-INSTANCE on INTEGER\""
					+ " \"don't know how to dump #<SGF-POINT :X 3> (default MAKE-LOAD-FORM method called).\")",
			"((MAKE-SGF-CELL :V 2)"
					+ " \"don't know how to dump #<SGF-POINT :X 3> (default MAKE-LOAD-FORM method called).\""
					+ " \"No applicable method: MAKE-LOAD-FORM on INTEGER\")");

}
