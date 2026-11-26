package am.ik.rontolisp.wit;

/**
 * The error arm of a WIT {@code result} a function of a {@link WitImport} interface
 * answers, where the method declares the ok arm as its answer (otherwise it answers a
 * {@link WitResult}): the function throws it in place of a value, carrying the error. A
 * provider throws it for its error arm; the host's binding rontolisp compiles throws it
 * for the host's.
 */
public class WitException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient Object error;

	/**
	 * An error arm.
	 * @param message what failed
	 * @param error the error, of the Java type the WIT error type is ({@code null} for a
	 * {@code result} whose error arm carries nothing)
	 */
	public WitException(String message, Object error) {
		super(message);
		this.error = error;
	}

	/**
	 * The error the arm carries: a value of the Java type the WIT error type is (a case
	 * record of a variant, an enum constant, a string ...), or {@code null} for an arm
	 * carrying nothing.
	 * @return the error
	 */
	public Object error() {
		return this.error;
	}

}
