package am.ik.rontolisp;

import org.jspecify.annotations.Nullable;

/**
 * A position in a Lisp source file: an optional origin file and a 1-based line/column
 * pair. Positions are computed only when an error is raised (they are never stored on the
 * AST), so an error can name exactly where the malformed input is: by the reader for a
 * read error, and by {@link SourceProvenance} for a frontend-pass error against the cons
 * the failing form was read into.
 *
 * <p>
 * It lives in the AST's own package rather than in {@code reader} because both consumers
 * need it and the frontend passes ({@code compiler}, {@code codegen.*}) may not import
 * {@code reader} -- see the package-dependency rules in {@code CLAUDE.md}.
 *
 * @param file the origin file, or {@code null} when unknown (a REPL buffer, a runtime
 * {@code read} of a string, ...)
 * @param line the 1-based line number
 * @param column the 1-based column number
 */
public record SourceLocation(@Nullable String file, int line, int column) {

	/**
	 * Computes a location for the given character offset in {@code input}, counting lines
	 * by {@code \n} (a lone {@code \r} line ending is treated as part of the same line,
	 * which keeps the count right for CRLF files).
	 * @param file the origin file, or {@code null}
	 * @param offset the character offset into {@code input}
	 * @param input the full source text
	 * @return the computed location
	 */
	public static SourceLocation at(@Nullable String file, int offset, String input) {
		int line = 1;
		int lineStart = 0;
		// A caller may hand over an offset past the end (a scan that ran off the input);
		// clamp it so the column stays inside the last line instead of overshooting it.
		int limit = Math.max(0, Math.min(offset, input.length()));
		for (int i = 0; i < limit; i++) {
			if (input.charAt(i) == '\n') {
				line++;
				lineStart = i + 1;
			}
		}
		return new SourceLocation(file, line, limit - lineStart + 1);
	}

	/**
	 * The error prefix to put in front of a message: {@code file:line:column: } when a
	 * file is known, otherwise the empty string. A location without an origin file (a
	 * runtime {@code read} of a string, a REPL buffer) has no meaningful line context, so
	 * no prefix is emitted and the message stays bare.
	 * @return the prefix, or {@code ""} when the file is unknown
	 */
	public String prefix() {
		if (this.file == null) {
			return "";
		}
		return this.file + ":" + this.line + ":" + this.column + ": ";
	}

}
