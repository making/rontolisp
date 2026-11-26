package am.ik.rontolisp;

/**
 * The function namespace asked about names {@code fmakunbound} retired, through computed
 * designators: {@code fboundp} answers nil, and {@code symbol-function} and
 * {@code fdefinition} signal an {@code undefined-function} catchable by its class on
 * every backend, while a name left alone, or retired and then given a function again,
 * still answers its function. The answers are sbcl's. Shared by the backend suites.
 */
public final class RetiredFunctionDesignatorFixture {

	private RetiredFunctionDesignatorFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun rf-fn () 1)
			(defun rf-kept () 2)
			(defun rf-back () 3)
			(defun rf-uf (thunk)
			  (handler-case (funcall thunk)
			    (undefined-function () :undefined-function)
			    (error () :other-error)))
			(defvar *rf-fn* (intern "RF-FN"))
			(defvar *rf-kept* (intern "RF-KEPT"))
			(defvar *rf-back* (intern "RF-BACK"))
			(fmakunbound *rf-fn*)
			(fmakunbound *rf-back*)
			(setf (symbol-function *rf-back*) (lambda () 4))
			(print (list (fboundp *rf-fn*) (and (fboundp *rf-kept*) t) (and (fboundp *rf-back*) t)))
			(print (list (rf-uf (lambda () (symbol-function *rf-fn*))) (rf-uf (lambda () (fdefinition *rf-fn*)))))
			(print (ignore-errors (symbol-function *rf-fn*)))
			(print (list (funcall (symbol-function *rf-kept*)) (funcall (symbol-function *rf-back*))))
			""";

	/** What {@link #SOURCE} prints, one value per line (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", "(NIL T T)", "(:UNDEFINED-FUNCTION :UNDEFINED-FUNCTION)",
			"NIL", "(2 4)");

}
