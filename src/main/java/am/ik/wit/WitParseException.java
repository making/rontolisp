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
		super(message + " at line " + lineOf(source, offset) + ", column " + columnOf(source, offset));
	}

	private static int lineOf(String source, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static int columnOf(String source, int offset) {
		int column = 1;
		for (int i = 0; i < offset && i < source.length(); i++) {
			column = (source.charAt(i) == '\n') ? 1 : column + 1;
		}
		return column;
	}

}
