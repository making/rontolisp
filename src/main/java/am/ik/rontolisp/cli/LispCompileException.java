package am.ik.rontolisp.cli;

/**
 * A frontend failure re-reported at the source position of the form that caused it. The
 * message is {@code file:line:column: } followed by the original message, and the
 * original exception is the {@linkplain #getCause() cause}, so a type-based catch inside
 * the frontend still sees the exception it expects -- this wrapper is only ever applied
 * at the compile boundary, once, on the way out.
 *
 * <p>
 * A read error is never wrapped: {@link am.ik.rontolisp.reader.LispReadException} carries
 * its own prefix already.
 */
public class LispCompileException extends RuntimeException {

	/**
	 * Create a new compile exception.
	 * @param message the positioned message
	 * @param cause the original frontend failure
	 */
	public LispCompileException(String message, Throwable cause) {
		super(message, cause);
	}

}
