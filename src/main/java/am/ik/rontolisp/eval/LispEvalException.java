package am.ik.rontolisp.eval;

/**
 * Exception thrown during Lisp expression evaluation.
 */
public class LispEvalException extends RuntimeException {

	/**
	 * Create a new evaluation exception with the given message.
	 * @param message the error message
	 */
	public LispEvalException(String message) {
		super(message);
	}

}
