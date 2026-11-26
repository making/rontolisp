package am.ik.rontolisp;

/**
 * Programs that take a function a {@code defun} below the top level defines -- in a
 * function body and over a {@code let}, a plain name and a {@code (setf name)} one --
 * before and after that definition runs. A literal {@code fboundp} of such a name
 * answered NIL on the compiled backends even after the definition ran, and a direct call,
 * a place, {@code #'name}, a quoted designator, {@code symbol-function} and
 * {@code fdefinition} before it ran read the still-empty variable that holds the
 * function: the call reported {@code undefined-function} naming NIL and the references
 * answered NIL, where SBCL and the interpreter name the function. A lexical variable
 * spelled like the function shadowed it at {@code #'name} and at a direct call. The
 * expected text is SBCL's (a true {@code fboundp} answer is printed through {@code and},
 * SBCL answering the function). {@link #RESTART} adds a {@code handler-bind}, which puts
 * the compiled backends in restart mode. Shared by the backend suites, so every backend
 * is held to one expected text.
 */
public final class NestedDefunNameFixture {

	private NestedDefunNameFixture() {
	}

	/** The program without a {@code handler-bind}. */
	public static final String PLAIN = """
			(defun nd-def () (defun nd-q (x) (* x 2)) (defun (setf nd-q) (v x) (list v x)))
			(let ((n 0))
			  (defun nd-c () (incf n))
			  (defun (setf nd-c) (v) (setq n v)))
			(defun nd-early (x) (nd-q x))
			(defun nd-name (thunk) (handler-case (funcall thunk) (undefined-function (c) (cell-error-name c))))
			(print (list (fboundp 'nd-q) (fboundp '(setf nd-q)) (and (fboundp 'nd-c) t) (and (fboundp '(setf nd-c)) t)))
			(print (list (nd-name (lambda () (nd-q 3))) (nd-name (lambda () (setf (nd-q 3) 4)))
			             (nd-name (lambda () (funcall #'nd-q 3))) (nd-name (lambda () (funcall 'nd-q 3)))
			             (nd-name (lambda () (symbol-function 'nd-q))) (nd-name (lambda () (fdefinition '(setf nd-q))))
			             (nd-name (lambda () (nd-early 1)))))
			(print (handler-case (nd-q 3) (undefined-function (c) (cell-error-name c))))
			(print (let ((nd-q 5)) (list nd-q (fboundp 'nd-q))))
			(nd-def)
			(print (list (nd-q 3) (setf (nd-q 3) 4) (funcall #'nd-q 5) (funcall 'nd-q 6) (nd-early 7)))
			(print (list (and (fboundp 'nd-q) t) (and (fboundp '(setf nd-q)) t) (functionp (symbol-function 'nd-q))))
			(print (let ((nd-q 5)) (list nd-q (and (fboundp 'nd-q) t) (funcall #'nd-q nd-q) (nd-q nd-q))))
			(print (list (nd-c) (setf (nd-c) 10) (nd-c)))
			""";

	/** What {@link #PLAIN} prints, one value per line. */
	public static final String PLAIN_EXPECTED = String.join("\n", "(NIL NIL T T)",
			"(ND-Q (SETF ND-Q) ND-Q ND-Q ND-Q (SETF ND-Q) ND-Q)", "ND-Q", "(5 NIL)", "(6 (4 3) 10 12 14)", "(T T T)",
			"(5 T 10 10)", "(1 10 11)");

	/** The program whose {@code handler-bind} sees the condition first. */
	public static final String RESTART = """
			(defvar *nd-seen* nil)
			(defun nd-late () (defun nd-r (x) x) (defun (setf nd-r) (v x) (list v x)))
			(defun nd-bound (thunk)
			  (handler-case
			      (handler-bind ((undefined-function (lambda (c) (push (cell-error-name c) *nd-seen*))))
			        (funcall thunk))
			    (undefined-function (c) (list (cell-error-name c) (type-of c)))))
			(print (list (nd-bound (lambda () (nd-r 1))) (nd-bound (lambda () (setf (nd-r 1) 2)))
			             (nd-bound (lambda () #'nd-r)) *nd-seen*))
			(nd-late)
			(print (list (nd-r 1) (setf (nd-r 1) 2) (and (fboundp 'nd-r) t)))
			""";

	/** What {@link #RESTART} prints, one value per line. */
	public static final String RESTART_EXPECTED = String.join("\n",
			"((ND-R UNDEFINED-FUNCTION) ((SETF ND-R) UNDEFINED-FUNCTION) (ND-R UNDEFINED-FUNCTION)"
					+ " (ND-R (SETF ND-R) ND-R))",
			"(1 (2 1) T)");

}
