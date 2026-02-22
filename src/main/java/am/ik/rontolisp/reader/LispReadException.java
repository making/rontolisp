package am.ik.rontolisp.reader;

/**
 * Exception thrown during Lisp expression reading/parsing.
 */
public class LispReadException extends RuntimeException {

	/**
	 * Create a new read exception with the given message.
	 * @param message the error message
	 */
	public LispReadException(String message) {
		super(message);
	}

}
