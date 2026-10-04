package am.ik.rontolisp;

/**
 * Programs that take {@code boundp} and {@code fboundp} as function values -- through
 * {@code mapcar}, {@code funcall} and {@code apply} -- over a global, a special declared
 * without a value (bound by a {@code let} around the call), an undefined name, the
 * self-bound constants, and functions of every kind. The expected texts are SBCL's, whose
 * {@code fboundp} answers the function object, so a generalized boolean is normalized
 * with {@code (and ... t)}. Shared by the backend suites; {@code ci-spec.yaml}'s
 * {@code boundp-and-fboundp-as-function-values} runs the same program on the native
 * binary.
 */
public final class BoundpFunctionValueFixture {

	private BoundpFunctionValueFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *fv* 1)
			(defvar *fv-nv*)
			(defun fv-fn () 1)
			(print (mapcar #'boundp '(*fv* *fv-none* *fv-nv*)))
			(print (let ((*fv-nv* 1)) (mapcar #'boundp '(*fv-nv* *fv*))))
			(print (list (funcall #'boundp '*fv*) (apply #'boundp '(*fv-nv*))))
			(print (mapcar #'boundp '(t nil :fv-kw)))
			(print (mapcar #'fboundp '(fv-none)))
			(print (and (funcall #'fboundp 'car) t))
			(print (mapcar (lambda (s) (and (funcall #'fboundp s) t)) '(fv-fn car fv-none)))
			(print (and (apply #'fboundp '(fv-fn)) t))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(T NIL NIL)", "(T T)", "(T NIL)", "(T T T)", "(NIL)", "T",
			"(T T NIL)", "T");

}
