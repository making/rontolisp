package am.ik.rontolisp.reader;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.SourceLocation;

/**
 * Exception thrown during Lisp expression reading/parsing. When the failing read had a
 * known origin file, the message is prefixed with {@code file:line:column: } and the
 * position is available via {@link #location()}. A read without an origin file (a runtime
 * {@code read} of a string, a REPL buffer) is prefixed with nothing and keeps its bare
 * message.
 *
 * <p>
 * The exception also records whether the input ran out in the middle of a datum
 * ({@link #isEndOfFile()}): the runtime {@code read} family turns that into an
 * {@code end-of-file} condition and any other read failure into a {@code reader-error}
 * (CLHS 23.1 -- "input ran out mid-datum" vs "this token is bad").
 */
public class LispReadException extends RuntimeException {

	/** The failing source position, or {@code null} when unknown. */
	private final @Nullable SourceLocation location;

	/** Whether the input ran out in the middle of a datum. */
	private final boolean endOfFile;

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
		this(message, location, false);
	}

	/**
	 * Create a new read exception with the given message, position and end-of-file
	 * classification.
	 * @param message the error message
	 * @param location the failing source position, or {@code null}
	 * @param endOfFile whether the input ran out in the middle of a datum
	 */
	public LispReadException(String message, @Nullable SourceLocation location, boolean endOfFile) {
		super(location == null ? message : location.prefix() + message);
		this.location = location;
		this.endOfFile = endOfFile;
	}

	/**
	 * The failing source position, or {@code null} when unknown.
	 * @return the location
	 */
	@Nullable public SourceLocation location() {
		return this.location;
	}

	/**
	 * Whether the input ran out in the middle of a datum (an unterminated string, block
	 * comment or {@code |...|} escape, a list that is never closed, a prefix like
	 * {@code #'} with nothing behind it). Anything else -- a bad token, an unknown
	 * character name, a misplaced dot -- answers false.
	 * @return true for an end-of-input failure
	 */
	public boolean isEndOfFile() {
		return this.endOfFile;
	}

}
