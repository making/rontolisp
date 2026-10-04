package am.ik.rontolisp;

/**
 * A program over {@code uiop:register-hook-function}: upstream's body pushes the hook
 * ({@code pushnew} by {@code equal}) onto the variable a symbol names at run time,
 * through the {@code symbol-value} place, and calls it now when asked; an active binding
 * of the variable takes the hook, as {@code setq} would. The expected text is SBCL's with
 * its own uiop. Shared by the backend suites.
 */
public final class RegisterHookFunctionFixture {

	private RegisterHookFunctionFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *urh* nil)
			(defun urh-show () *urh*)
			(uiop:register-hook-function '*urh* 'car)
			(uiop:register-hook-function '*urh* 'car)
			(print (list (uiop:register-hook-function '*urh* (lambda () :ran) t) (length *urh*) (second *urh*)))
			(print (list (let ((*urh* nil)) (uiop:register-hook-function '*urh* 'cdr) (urh-show)) (length *urh*)))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = "(:RAN 2 CAR)\n((CDR) 2)";

}
