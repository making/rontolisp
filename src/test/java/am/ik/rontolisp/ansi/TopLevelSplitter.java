package am.ik.rontolisp.ansi;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a Lisp source file into the verbatim text of its top-level forms.
 * <p>
 * The ANSI suite is read one top-level form at a time so that a form the reader or the
 * evaluator cannot take costs exactly one form instead of the rest of the file --
 * {@code LispReader.readAllFromString} reads a whole file up front, so one unreadable
 * datum in it would hide every test after it. This scanner therefore works on the SOURCE,
 * not on the AST, and it must keep a reader prefix ({@code #+}/{@code #-}, {@code '},
 * {@code `}) attached to the datum it guards: dropping the guard would silently run a
 * form written for another implementation.
 */
final class TopLevelSplitter {

	private final String src;

	private TopLevelSplitter(String src) {
		this.src = src;
	}

	/**
	 * The top-level forms of a source text, in order.
	 * @param source the whole file text
	 * @return the verbatim text of each top-level form
	 */
	static List<String> split(String source) {
		TopLevelSplitter s = new TopLevelSplitter(source);
		List<String> forms = new ArrayList<>();
		int i = 0;
		while (true) {
			int start = s.skipWhitespace(i);
			if (start >= source.length()) {
				break;
			}
			int end = s.readDatum(start);
			if (end <= start) {
				break;
			}
			String text = source.substring(start, end).strip();
			if (!text.isEmpty() && !text.equals(")")) {
				forms.add(text);
			}
			i = end;
		}
		return forms;
	}

	/** Past whitespace, {@code ;} line comments and nesting {@code #|...|#} blocks. */
	private int skipWhitespace(int i) {
		int n = this.src.length();
		while (i < n) {
			char c = this.src.charAt(i);
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
				i++;
			}
			else if (c == ';') {
				while (i < n && this.src.charAt(i) != '\n') {
					i++;
				}
			}
			else if (this.src.startsWith("#|", i)) {
				int level = 1;
				i += 2;
				while (i < n && level > 0) {
					if (this.src.startsWith("#|", i)) {
						level++;
						i += 2;
					}
					else if (this.src.startsWith("|#", i)) {
						level--;
						i += 2;
					}
					else {
						i++;
					}
				}
			}
			else {
				return i;
			}
		}
		return i;
	}

	/** The index just past the datum that starts at or after {@code i}. */
	private int readDatum(int i) {
		int n = this.src.length();
		i = skipWhitespace(i);
		if (i >= n) {
			return n;
		}
		char c = this.src.charAt(i);
		if (c == '\'' || c == '`') {
			return readDatum(i + 1);
		}
		if (c == ',') {
			i++;
			if (i < n && this.src.charAt(i) == '@') {
				i++;
			}
			return readDatum(i);
		}
		if (c == '"') {
			return readString(i);
		}
		if (c == '(') {
			return readList(i);
		}
		if (c == ')') {
			return i + 1; // a stray close paren: hand it back so the caller resyncs
		}
		if (c == '|') {
			i++;
			while (i < n && this.src.charAt(i) != '|') {
				i++;
			}
			return Math.min(i + 1, n);
		}
		if (c == '#') {
			return readDispatch(i);
		}
		return readToken(i);
	}

	private int readString(int i) {
		int n = this.src.length();
		i++;
		while (i < n) {
			char c = this.src.charAt(i);
			if (c == '\\') {
				i += 2;
			}
			else if (c == '"') {
				return i + 1;
			}
			else {
				i++;
			}
		}
		return n;
	}

	private int readList(int i) {
		int n = this.src.length();
		i++;
		while (true) {
			i = skipWhitespace(i);
			if (i >= n) {
				return n;
			}
			if (this.src.charAt(i) == ')') {
				return i + 1;
			}
			int j = readDatum(i);
			if (j <= i) {
				return n;
			}
			i = j;
		}
	}

	/** A {@code #} dispatch: the guards take two data, everything else takes one. */
	private int readDispatch(int i) {
		int n = this.src.length();
		if (i + 1 >= n) {
			return n;
		}
		char d = this.src.charAt(i + 1);
		if (d == '+' || d == '-') {
			return readDatum(readDatum(i + 2));
		}
		if (d == '\\') {
			i = Math.min(i + 3, n);
			while (i < n && (Character.isLetterOrDigit(this.src.charAt(i)) || this.src.charAt(i) == '-')) {
				i++;
			}
			return i;
		}
		if (d == '|') {
			return skipWhitespace(i);
		}
		// #' #. #( #* #nA(...) #S(...) #x1f #:foo -- step over the dispatch head, then
		// take the datum or token that follows it.
		i++;
		while (i < n
				&& (Character.isDigit(this.src.charAt(i)) || "rRxXoObBaAsScCpPfFdD".indexOf(this.src.charAt(i)) >= 0)) {
			// A letter is only part of the head when a datum opener follows it (#2A(..),
			// #P".."); otherwise it belongs to the token (#xabc), which readToken picks
			// up.
			char next = i + 1 < n ? this.src.charAt(i + 1) : ' ';
			if (Character.isLetter(this.src.charAt(i)) && next != '(' && next != '"') {
				break;
			}
			i++;
		}
		if (i < n && (this.src.charAt(i) == '\'' || this.src.charAt(i) == '.' || this.src.charAt(i) == ',')) {
			i++;
		}
		return readDatum(i);
	}

	private int readToken(int i) {
		int n = this.src.length();
		while (i < n && " \t\n\r\f()\"';".indexOf(this.src.charAt(i)) < 0) {
			if (this.src.charAt(i) == '\\') {
				i++;
			}
			i++;
		}
		return i;
	}

}
