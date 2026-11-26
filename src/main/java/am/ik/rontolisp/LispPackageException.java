package am.ik.rontolisp;

/**
 * Thrown when package resolution fails, for example referencing an unknown package or
 * using a {@code cl} symbol unqualified in a package that does not use {@code cl}.
 */
public class LispPackageException extends RuntimeException {

	/**
	 * Creates a new exception with the given message.
	 * @param message the detail message
	 */
	public LispPackageException(String message) {
		super(message);
	}

}
