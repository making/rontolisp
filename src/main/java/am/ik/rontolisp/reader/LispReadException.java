package am.ik.rontolisp.reader;

import org.jspecify.annotations.Nullable;

/**
 * Exception thrown during Lisp expression reading/parsing. When the failing read had a
 * known origin file, the message is prefixed with {@code file:line:column: } and the
 * position is available via {@link #location()}. A read without an origin file (a runtime
 * {@code read} of a string, a REPL buffer) is prefixed with nothing and keeps its bare
 * message.
 */
public class LispReadException extends RuntimeException {

	/** The failing source position, or {@code null} when unknown. */
	private final @Nullable SourceLocation location;

	/**
	 * Create a new read exception with the given message and no position.
	 * @param message the error message
	 */
	public LispReadException(String message) {
		this(message, null);
	}

	/**
	 * Create a new read exception with the given message and position. The position is
	 * prefixed to the message, so the exception reads self-descriptively even where only
	 * {@link #getMessage()} is surfaced.
	 * @param message the error message
	 * @param location the failing source position, or {@code null}
	 */
	public LispReadException(String message, @Nullable SourceLocation location) {
		super(location == null ? message : location.prefix() + message);
		this.location = location;
	}

	/**
	 * The failing source position, or {@code null} when unknown.
	 * @return the location
	 */
	@Nullable public SourceLocation location() {
		return this.location;
	}

}
