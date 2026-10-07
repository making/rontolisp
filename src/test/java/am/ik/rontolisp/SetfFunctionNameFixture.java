package am.ik.rontolisp;

/**
 * Programs that use a {@code (setf name)} function name where CL takes a function name.
 * {@link #CLOSURE} references {@code #'(setf name)} from inside a closure: a
 * {@code lambda}, a nested {@code lambda}, a closure a {@code defun} returns, a
 * {@code labels} / {@code flet} function, beside a variable of the same name and for an
 * undefined writer. The compiled backends read the {@code (setf name)} operand as a call
 * whose argument is a free variable and refused the program ({@code Cannot capture
 * variable}). {@link #DESIGNATOR} passes the quoted {@code (setf name)} list to
 * {@code fdefinition}, {@code fboundp}, {@code fmakunbound} and
 * {@code (setf fdefinition)}, which took symbols only: the interpreter signalled
 * {@code FDEFINITION expects a symbol}, the JVM a raw {@code ClassCastException}, both
 * wasm targets a trap. The expected text is SBCL's (a true {@code fboundp} answer is
 * printed through {@code not}, SBCL answering the function). Shared by the backend
 * suites, so every backend is held to one expected text.
 */
public final class SetfFunctionNameFixture {

	private SetfFunctionNameFixture() {
	}

	/** {@code #'(setf name)} inside closures. */
	public static final String CLOSURE = """
			(defun (setf sfc-d) (v x) (list v x))
			(defun sfc-maker (k) (lambda (a) (funcall #'(setf sfc-d) a k)))
			(print (funcall (lambda (a) (funcall #'(setf sfc-d) a 2)) 7))
			(print (funcall (funcall (lambda (a) (lambda (b) (funcall #'(setf sfc-d) a b))) 3) 4))
			(print (funcall (sfc-maker 5) 6))
			(print (mapcar (lambda (x) (funcall #'(setf sfc-d) x x)) '(1 2)))
			(print (labels ((down (n) (if (= n 0) (funcall #'(setf sfc-d) n :done) (down (- n 1))))) (down 3)))
			(print (let ((k 8)) (flet ((wr (v) (funcall #'(setf sfc-d) v k))) (mapcar #'wr '(1 2)))))
			(print (let ((sfc-d 10)) (funcall (lambda () (list sfc-d (funcall #'(setf sfc-d) sfc-d 1))))))
			(print (handler-case (funcall (lambda (a) (funcall #'(setf sfc-nope) a 1)) 0)
			         (undefined-function (c) (cell-error-name c))))
			""";

	/** What {@link #CLOSURE} prints, one value per line. */
	public static final String CLOSURE_EXPECTED = String.join("\n", "(7 2)", "(3 4)", "(6 5)", "((1 1) (2 2))",
			"(0 :DONE)", "((1 8) (2 8))", "(10 (10 1))", "(SETF SFC-NOPE)");

	/**
	 * A quoted {@code (setf name)} designator to the operators that take a function name.
	 */
	public static final String DESIGNATOR = """
			(defun (setf sfn-d) (v x) (list v x))
			(print (funcall (fdefinition '(setf sfn-d)) 1 2))
			(print (list (not (fboundp '(setf sfn-d))) (fboundp '(setf sfn-nope))))
			(print (handler-case (fdefinition '(setf sfn-nope)) (undefined-function (c) (cell-error-name c))))
			(print (funcall (lambda () (funcall (fdefinition '(setf sfn-d)) 3 4))))
			(setf (fdefinition '(setf sfn-e)) (lambda (v x) (list :e v x)))
			(print (list (not (fboundp '(setf sfn-e))) (funcall #'(setf sfn-e) 1 2)
			             (funcall (fdefinition '(setf sfn-e)) 3 4)))
			(print (fmakunbound '(setf sfn-e)))
			(print (fboundp '(setf sfn-e)))
			(print (handler-case (funcall (fdefinition '(setf sfn-e)) 1 2)
			         (undefined-function (c) (cell-error-name c))))
			(print (fmakunbound '(setf sfn-d)))
			(print (fboundp '(setf sfn-d)))
			""";

	/** What {@link #DESIGNATOR} prints, one value per line. */
	public static final String DESIGNATOR_EXPECTED = String.join("\n", "(1 2)", "(NIL NIL)", "(SETF SFN-NOPE)", "(3 4)",
			"(NIL (:E 1 2) (:E 3 4))", "(SETF SFN-E)", "NIL", "(SETF SFN-E)", "(SETF SFN-D)", "NIL");

}
