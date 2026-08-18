package am.ik.rontolisp.format;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads Lisp source into a {@link CstNode} tree without losing anything the formatter has
 * to reproduce: every token keeps its verbatim text, comments become nodes, reader macros
 * stay prefixes, and {@code #+}/{@code #-} guards are kept rather than evaluated.
 * <p>
 * The accepted surface syntax is the same as {@code am.ik.rontolisp.reader.LispLexer}'s,
 * down to the details that only matter for a lossless slice: {@code ,} is a terminating
 * character EXCEPT between two digits (rontolisp's {@code 1,000} grouping), a {@code \}
 * escapes the next character inside a token, and {@code |...|} runs swallow terminating
 * characters. Unlike the lexer this reader never interprets what it reads -- it only
 * finds token boundaries -- so a file it can read is a file it can format.
 */
public final class FormatReader {

	private final String source;

	private int pos;

	/**
	 * Create a reader over the given source, VERBATIM: nothing normalizes it first, since
	 * a {@code \r} inside a string or character literal is part of that token. LF, CRLF
	 * and a lone CR are all line endings between tokens ({@code skipSpace}).
	 * @param source the source text
	 */
	public FormatReader(String source) {
		this.source = source;
	}

	/**
	 * Read every top-level node of the source, comments included.
	 * @return the top-level nodes, in order
	 * @throws FormatException if the source cannot be read as Lisp
	 */
	public List<CstNode> readAll() {
		List<CstNode> nodes = readItems(true);
		if (this.pos < this.source.length()) {
			throw error(this.pos, "unexpected ')'");
		}
		return nodes;
	}

	// Reads siblings until ')' (which it leaves unconsumed) or end of input. The caller
	// decides which of the two is legal.
	private List<CstNode> readItems(boolean topLevel) {
		List<CstNode> items = new ArrayList<>();
		boolean first = true;
		while (true) {
			// Start of file counts as a line break so the first node is an "own line"
			// one.
			int newlines = skipSpace() + (first && topLevel ? 1 : 0);
			if (this.pos >= this.source.length() || this.source.charAt(this.pos) == ')') {
				return items;
			}
			items.add(readDatum(new Trivia(!first && newlines >= 2, newlines >= 1)));
			first = false;
		}
	}

	// Consumes whitespace, returning how many line breaks it crossed. A line ending is
	// LF, CRLF or a lone CR, each counting once -- the source is read verbatim, so this
	// is the only place that decides what a line break IS. (Nothing normalizes the source
	// up front: a CR inside a string or character literal is part of that token, and the
	// formatter may not touch it. `.kb/formatter.md`.)
	private int skipSpace() {
		int newlines = 0;
		while (this.pos < this.source.length() && Character.isWhitespace(this.source.charAt(this.pos))) {
			char c = this.source.charAt(this.pos);
			if (c == '\n' || (c == '\r'
					&& (this.pos + 1 >= this.source.length() || this.source.charAt(this.pos + 1) != '\n'))) {
				newlines++;
			}
			this.pos++;
		}
		return newlines;
	}

	private CstNode readDatum(Trivia trivia) {
		char c = this.source.charAt(this.pos);
		return switch (c) {
			case ';' -> new CstNode.LineComment(readLineComment(), trivia);
			case '(' -> readListing("(", trivia);
			case '\'' -> readPrefix("'", trivia);
			case '`' -> readPrefix("`", trivia);
			case ',' -> readPrefix(lookingAt(",@") ? ",@" : ",", trivia);
			case '"' -> new CstNode.Atom(readStringLiteral(), trivia);
			case '#' -> readDispatch(trivia);
			default -> new CstNode.Atom(readToken(), trivia);
		};
	}

	// Every '#' form. The order of the tests matters: the ones whose second character is
	// also a token character (#x1F, #:gensym, #*1010) fall through to readToken, so the
	// forms that are NOT plain tokens have to be recognized first.
	private CstNode readDispatch(Trivia trivia) {
		int start = this.pos;
		if (lookingAt("#|")) {
			return new CstNode.BlockComment(readBlockComment(), trivia);
		}
		if (lookingAt("#\\")) {
			return new CstNode.Atom(readCharLiteral(), trivia);
		}
		if (lookingAt("#'")) {
			return readPrefix("#'", trivia);
		}
		if (lookingAt("#.")) {
			return readPrefix("#.", trivia);
		}
		if (lookingAt("#+") || lookingAt("#-")) {
			return readFeatureGuard(trivia);
		}
		if (lookingAt("#(")) {
			return readListing("#(", trivia);
		}
		// #P"foo/bar" is one lexeme (the dispatch plus the namestring), not a prefix.
		if ((lookingAt("#P\"") || lookingAt("#p\""))) {
			this.pos += 2;
			return new CstNode.Atom(this.source.substring(start, this.pos) + readStringLiteral(), trivia);
		}
		// #S( / #s( structure, #f( / #d( packed float array.
		if (this.pos + 2 < this.source.length() && this.source.charAt(this.pos + 2) == '('
				&& "SsFfDd".indexOf(this.source.charAt(this.pos + 1)) >= 0) {
			return readListing(this.source.substring(start, start + 3), trivia);
		}
		// #nA( rank-n array, #n@( packed integer vector, #n= label, #n# reference.
		int digits = this.pos + 1;
		while (digits < this.source.length() && isDigit(this.source.charAt(digits))) {
			digits++;
		}
		if (digits > this.pos + 1 && digits + 1 < this.source.length() && this.source.charAt(digits + 1) == '('
				&& "Aa@".indexOf(this.source.charAt(digits)) >= 0) {
			return readListing(this.source.substring(start, digits + 2), trivia);
		}
		if (digits > this.pos + 1 && digits < this.source.length() && this.source.charAt(digits) == '=') {
			this.pos = digits + 1;
			return readPrefixBody(this.source.substring(start, this.pos), start, trivia);
		}
		return new CstNode.Atom(readToken(), trivia);
	}

	// #+feature / #-feature. The feature expression is a datum of its own, but it is
	// always tiny and never worth a line of its own, so it is folded into the prefix with
	// its internal whitespace collapsed. What follows is the guarded datum.
	private CstNode readFeatureGuard(Trivia trivia) {
		int start = this.pos;
		String sign = this.source.substring(this.pos, this.pos + 2);
		this.pos += 2;
		skipSpace();
		if (this.pos >= this.source.length()) {
			throw error(start, "end of input in a " + sign + " feature expression");
		}
		int featureStart = this.pos;
		readDatum(Trivia.SAME_LINE);
		String feature = collapseSpace(this.source.substring(featureStart, this.pos));
		return readPrefixBody(sign + feature + " ", start, trivia);
	}

	private CstNode readPrefix(String prefix, Trivia trivia) {
		int start = this.pos;
		this.pos += prefix.length();
		return readPrefixBody(prefix, start, trivia);
	}

	// Reads the datum a prefix binds to. The prefix text is already consumed.
	private CstNode readPrefixBody(String prefix, int start, Trivia trivia) {
		skipSpace();
		if (this.pos >= this.source.length() || this.source.charAt(this.pos) == ')') {
			throw error(start, "'" + prefix.strip() + "' is not followed by a form");
		}
		return new CstNode.Prefix(separatedPrefix(prefix), readDatum(Trivia.SAME_LINE), trivia);
	}

	// A prefix is printed GLUED to its datum, which for ',' can change what the result
	// reads as: `(, @section)` -- a comma whose datum happens to start with '@' -- would
	// come back out as `,@section`, unquote-SPLICING. `,.` is the same reader macro's
	// other spelling. Both are legal spellings upstream uses (trivial-utf-8's pax-pages),
	// so the separating space is part of the token stream and has to survive.
	private String separatedPrefix(String prefix) {
		if (!",".equals(prefix)) {
			return prefix;
		}
		char next = this.source.charAt(this.pos);
		return (next == '@' || next == '.') ? ", " : prefix;
	}

	private CstNode readListing(String open, Trivia trivia) {
		int start = this.pos;
		this.pos += open.length();
		List<CstNode> items = readItems(false);
		if (this.pos >= this.source.length()) {
			throw error(start, "unterminated '" + open + "': no matching ')'");
		}
		this.pos++; // the ')'
		return new CstNode.Listing(open, items, trivia);
	}

	// A line comment ends at the line ending, whichever of the three it is -- a CR that
	// ends the line would otherwise be swallowed into the comment (and, on a CR-only
	// source, the rest of the file with it).
	private String readLineComment() {
		int start = this.pos;
		while (this.pos < this.source.length() && this.source.charAt(this.pos) != '\n'
				&& this.source.charAt(this.pos) != '\r') {
			this.pos++;
		}
		return this.source.substring(start, this.pos).stripTrailing();
	}

	private String readBlockComment() {
		int start = this.pos;
		this.pos += 2;
		int depth = 1;
		while (this.pos < this.source.length()) {
			if (lookingAt("|#")) {
				this.pos += 2;
				if (--depth == 0) {
					return this.source.substring(start, this.pos);
				}
			}
			else if (lookingAt("#|")) {
				this.pos += 2;
				depth++;
			}
			else {
				this.pos++;
			}
		}
		throw error(start, "unterminated '#|' block comment");
	}

	private String readStringLiteral() {
		int start = this.pos;
		this.pos++; // the opening '"'
		while (this.pos < this.source.length() && this.source.charAt(this.pos) != '"') {
			this.pos += this.source.charAt(this.pos) == '\\' ? 2 : 1;
		}
		if (this.pos >= this.source.length()) {
			throw error(start, "unterminated string literal");
		}
		this.pos++; // the closing '"'
		return this.source.substring(start, this.pos);
	}

	// #\c: the character right after the backslash is taken literally even when it is
	// whitespace or a delimiter; only an alphabetic one may start a multi-character name.
	private String readCharLiteral() {
		int start = this.pos;
		this.pos += 2;
		if (this.pos >= this.source.length()) {
			throw error(start, "end of input after '#\\'");
		}
		boolean named = Character.isLetter(this.source.charAt(this.pos));
		this.pos++;
		if (named) {
			while (this.pos < this.source.length() && isTokenChar(this.source.charAt(this.pos))) {
				this.pos++;
			}
		}
		return this.source.substring(start, this.pos);
	}

	private String readToken() {
		int start = this.pos;
		while (this.pos < this.source.length()) {
			char c = this.source.charAt(this.pos);
			if (c == '\\' && this.pos + 1 < this.source.length()) {
				this.pos += 2;
				continue;
			}
			if (c == '|') {
				skipPipeEscape();
				continue;
			}
			// A comma between two digits is rontolisp's grouping separator ("1,000"), not
			// the unquote that terminates a token everywhere else.
			if (c == ',' && this.pos > start && isDigit(this.source.charAt(this.pos - 1))
					&& this.pos + 1 < this.source.length() && isDigit(this.source.charAt(this.pos + 1))) {
				this.pos++;
				continue;
			}
			if (!isTokenChar(c)) {
				break;
			}
			this.pos++;
		}
		if (this.pos == start) {
			throw error(start, "unexpected character '" + this.source.charAt(start) + "'");
		}
		return this.source.substring(start, this.pos);
	}

	private void skipPipeEscape() {
		int start = this.pos;
		this.pos++;
		while (this.pos < this.source.length() && this.source.charAt(this.pos) != '|') {
			this.pos += this.source.charAt(this.pos) == '\\' ? 2 : 1;
		}
		if (this.pos >= this.source.length()) {
			throw error(start, "unterminated '|...|' escape");
		}
		this.pos++;
	}

	private boolean lookingAt(String text) {
		return this.source.startsWith(text, this.pos);
	}

	private FormatException error(int offset, String message) {
		return new FormatException(this.source, offset, message);
	}

	private static String collapseSpace(String text) {
		StringBuilder collapsed = new StringBuilder(text.length());
		boolean pendingSpace = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				pendingSpace = !collapsed.isEmpty();
				continue;
			}
			if (pendingSpace) {
				collapsed.append(' ');
				pendingSpace = false;
			}
			collapsed.append(c);
		}
		return collapsed.toString();
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isTokenChar(char c) {
		return !Character.isWhitespace(c) && c != '(' && c != ')' && c != '\'' && c != '"' && c != ';' && c != ','
				&& c != '`';
	}

}
