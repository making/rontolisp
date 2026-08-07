package am.ik.rontolisp.format;

/**
 * Signals that a source file could not be read as Lisp, so it cannot be formatted. The
 * message carries a {@code line:column} prefix resolved from the failing offset.
 * <p>
 * Positions are computed here rather than through {@code am.ik.rontolisp.SourceLocation}
 * so the {@code format} package stays free of dependencies -- see the package-dependency
 * rules in {@code CLAUDE.md}.
 */
public final class FormatException extends RuntimeException {

	/**
	 * Create an exception positioned at the given offset in the given source.
	 * @param source the whole source text
	 * @param offset the 0-based character offset the failure is reported at
	 * @param message the failure description
	 */
	public FormatException(String source, int offset, String message) {
		super(position(source, offset) + ": " + message);
	}

	private static String position(String source, int offset) {
		int limit = Math.min(offset, source.length());
		int line = 1;
		int lineStart = 0;
		for (int i = 0; i < limit; i++) {
			if (source.charAt(i) == '\n') {
				line++;
				lineStart = i + 1;
			}
		}
		return line + ":" + (limit - lineStart + 1);
	}

}
