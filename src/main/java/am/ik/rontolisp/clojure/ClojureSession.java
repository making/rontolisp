package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.reader.LispReadException;

/**
 * An interactive Clojure session: the global scope a whole-file lowering rebuilds per
 * file, kept across the buffers a REPL reads one at a time. What a session lowers
 * differently from a file is nothing but the scope lifetime: every buffer declares its
 * own top-level {@code def}/{@code defn} names into the session's globals first, so a
 * later buffer may call what an earlier one defined.
 */
public final class ClojureSession {

	private final ClojureLowering lowering = new ClojureLowering();

	/**
	 * Reads and lowers one buffer.
	 * @param source the typed text: any number of datums
	 * @return one entry per top-level datum, in order
	 */
	public List<ClojureTopLevel> read(String source) {
		return this.lowering.interact(new ClojureReader(source, null));
	}

	/**
	 * Whether the text is a complete datum sequence, as opposed to one the reader ran out
	 * of input in the middle of: an open list, vector or map, a string, or a dispatch
	 * prefix still waiting for its datum. Text that is complete but WRONG answers
	 * {@code true}: it must reach {@link #read} to be reported.
	 * @param source the typed text so far
	 * @return {@code false} when more input is needed
	 */
	public static boolean isComplete(String source) {
		int depth = 0;
		boolean inString = false;
		boolean inComment = false;
		for (int i = 0; i < source.length(); i++) {
			char c = source.charAt(i);
			if (inComment) {
				if (c == '\n') {
					inComment = false;
				}
				continue;
			}
			if (inString) {
				if (c == '\\' && i + 1 < source.length()) {
					i++;
				}
				else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == ';') {
				inComment = true;
			}
			else if (c == '"') {
				inString = true;
			}
			else if (c == '(' || c == '[' || c == '{') {
				depth++;
			}
			else if (c == ')' || c == ']' || c == '}') {
				depth--;
			}
		}
		if (inString) {
			return false;
		}
		if (depth > 0) {
			return false;
		}
		// A trailing dispatch prefix (`'`, `` ` ``, `~`, `~@`, `@`, `^`, `#'`, `#_`,
		// `#(`) still waits for its datum.
		try {
			new ClojureReader(source, null).readAll();
			return true;
		}
		catch (LispReadException ex) {
			String message = ex.getMessage();
			return message == null || (!message.contains("unexpected end of input")
					&& !message.contains("unclosed form") && !message.contains("unterminated")
					&& !message.contains("truncated") && !message.contains("expected a form"));
		}
	}

}
