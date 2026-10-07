package am.ik.rontolisp.eval;

import java.io.BufferedReader;
import java.io.IOException;

import org.jspecify.annotations.Nullable;

/**
 * One line {@code read-line} read and whether end of file -- not a terminator -- ended
 * it: CL's missing-newline-p. The terminators are {@code BufferedReader.readLine}'s
 * ({@code \n}, {@code \r}, {@code \r\n}), none of which is part of the line, the contract
 * every stream kind here answers; a {@code \r} the file ends on ends the line as end of
 * file does, the way the WASM backends -- which split on {@code \n} and strip one
 * trailing {@code \r} -- answer it.
 *
 * @param text the line, without its terminator
 * @param missingNewline whether end of file ended the line
 */
record TerminatedLine(String text, boolean missingNewline) {

	/**
	 * Reads one line from a reader.
	 * @param reader the reader, which supports {@code mark}
	 * @return the line, or null at end of file
	 * @throws IOException when the read fails
	 */
	static @Nullable TerminatedLine read(BufferedReader reader) throws IOException {
		int c = reader.read();
		if (c < 0) {
			return null;
		}
		StringBuilder line = new StringBuilder();
		while (c >= 0 && c != '\n' && c != '\r') {
			line.append((char) c);
			c = reader.read();
		}
		boolean missing = c < 0;
		if (c == '\r') {
			reader.mark(1);
			int next = reader.read();
			if (next < 0) {
				missing = true;
			}
			else if (next != '\n') {
				reader.reset();
			}
		}
		return new TerminatedLine(line.toString(), missing);
	}

}
