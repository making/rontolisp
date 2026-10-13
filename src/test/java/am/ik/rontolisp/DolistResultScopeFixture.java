package am.ik.rontolisp;

/**
 * A {@code dolist} over {@code (coerce s 'list)} whose result form names the enclosing
 * {@code let} binding: the reported shape's trailing reference sits OUTSIDE the
 * {@code let}'s scope -- one closing paren too many -- so it is genuinely unbound, and
 * every backend signals {@code unbound-variable} there exactly as SBCL (an
 * {@code undefined variable} warning, then {@code UNBOUND-VARIABLE}) and ECL
 * ({@code The variable FOUND is unbound}) do. There is no interpreter quirk to fix; a
 * change that stopped signalling would diverge from Common Lisp. {@link #SCOPED} pins the
 * in-scope shape answering through the result form, and {@link #UNSCOPED} pins the
 * out-of-scope reference signalling the {@code unbound-variable} naming it. SBCL prints
 * the same two lines. Shared by the backend suites, so every backend is held to one
 * expected text.
 */
public final class DolistResultScopeFixture {

	private DolistResultScopeFixture() {
	}

	/**
	 * The in-scope shape: the result form names the {@code let} binding and the answer
	 * flows out through an in-scope use.
	 */
	public static final String SCOPED = """
			(defun f86-find (s k)
			  (let ((found nil))
			    (dolist (e (coerce s 'list) found)
			      (if (equal e k) (setq found t)))
			    found))
			(print (list (f86-find "abc" #\\b) (f86-find "abc" #\\z)))
			""";

	/** What {@link #SCOPED} prints. */
	public static final String SCOPED_EXPECTED = "(T NIL)";

	/**
	 * The reported shape: the trailing reference sits outside the {@code let}, so the
	 * call signals {@code unbound-variable}, caught here for its name and class.
	 */
	public static final String UNSCOPED = """
			(defun f86-unscoped (s k)
			  (let ((found nil))
			    (dolist (e (coerce s 'list) found)
			      (if (equal e k) (setq found t))))
			  found)
			(print (handler-case (f86-unscoped "abc" #\\b)
			         (unbound-variable (c) (list (cell-error-name c) (type-of c)))))
			""";

	/** What {@link #UNSCOPED} prints. */
	public static final String UNSCOPED_EXPECTED = "(FOUND UNBOUND-VARIABLE)";

}
