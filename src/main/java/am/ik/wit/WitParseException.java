package am.ik.wit;

/**
 * Signals a syntax error while lexing or parsing WIT text, with the 1-based line and
 * column of the offending position in the message.
 */
public class WitParseException extends RuntimeException {

	/**
	 * Creates the exception, locating {@code offset} in {@code source} for the message.
	 * @param message what was wrong
	 * @param source the full WIT source text being parsed
	 * @param offset the character offset of the error in {@code source}
	 */
	public WitParseException(String message, String source, int offset) {
		super(message + " at line " + WitLocations.lineOf(source, offset) + ", column "
				+ WitLocations.columnOf(source, offset));
	}

}
