package am.ik.rontolisp.eval;

import java.io.BufferedReader;
import java.io.IOException;

import org.jspecify.annotations.Nullable;

/**
 * One line {@code read-line} read and whether end of file -- not a terminator -- ended
 * it: CL's missing-newline-p. Only {@code \n} ends a line (a lone {@code \r} does not,
 * unlike {@code BufferedReader.readLine}), and one {@code \r} just before the {@code \n}
 * or before end of file is dropped: the rule every stream kind and every backend answers.
 *
 * @param text the line, without its terminator
 * @param missingNewline whether end of file ended the line
 */
record TerminatedLine(String text, boolean missingNewline) {

	/**
	 * Reads one line from a reader.
	 * @param reader the reader
	 * @return the line, or null at end of file
	 * @throws IOException when the read fails
	 */
	static @Nullable TerminatedLine read(BufferedReader reader) throws IOException {
		int c = reader.read();
		if (c < 0) {
			return null;
		}
		StringBuilder line = new StringBuilder();
		while (c >= 0 && c != '\n') {
			line.append((char) c);
			c = reader.read();
		}
		int n = line.length();
		if (n > 0 && line.charAt(n - 1) == '\r') {
			line.setLength(n - 1);
		}
		return new TerminatedLine(line.toString(), c < 0);
	}

	/**
	 * Reads one line from a reader, like {@link #read} without the end-of-file flag.
	 * @param reader the reader
	 * @return the line, or null at end of file
	 * @throws IOException when the read fails
	 */
	static @Nullable String readText(BufferedReader reader) throws IOException {
		TerminatedLine line = read(reader);
		return line == null ? null : line.text();
	}

}
