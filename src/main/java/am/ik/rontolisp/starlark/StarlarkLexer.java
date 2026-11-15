package am.ik.rontolisp.starlark;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.starlark.StarlarkToken.Kind;
import org.jspecify.annotations.Nullable;

/**
 * Starlark source to tokens, per the spec's "Lexical elements", one token per
 * {@link #next()} so that an error surfaces in source order with the parser's.
 *
 * <p>
 * Outside brackets a line ending is a {@link Kind#NEWLINE} unless the logical line is
 * blank or a comment, and the spaces opening a line are compared with a stack of
 * indentation widths to give {@link Kind#INDENT} and {@link Kind#OUTDENT}. Inside
 * brackets line endings and indentation are white space; a backslash before a line ending
 * joins the two lines anywhere. Indentation is spaces only: a tab there is an error, as
 * the spec says (Python accepts it). At end of input the lexer closes the last line and
 * every open indentation level before {@link Kind#EOF}.
 *
 * <p>
 * A reserved word ({@code while}, {@code class}, {@code import}, {@code is}, ...) and a
 * string prefix Starlark lacks ({@code f"..."}, {@code u"..."}) fail here, at their
 * position, as {@link LispReadException}s.
 */
final class StarlarkLexer {

	/**
	 * Words the spec reserves: they may not be identifiers and no grammar rule has them.
	 */
	private static final Set<String> RESERVED = Set.of("as", "assert", "async", "await", "class", "del", "except",
			"finally", "from", "global", "import", "is", "nonlocal", "raise", "try", "while", "with", "yield");

	private static final Set<String> STRING_PREFIXES = Set.of("r", "b", "rb", "br");

	private final String source;

	private final @Nullable String file;

	private int pos;

	private int line = 1;

	private int lineStart;

	/** Open brackets: inside any, line endings and indentation are white space. */
	private int depth;

	private final List<Integer> indents = new ArrayList<>(List.of(0));

	private final Deque<StarlarkToken> pending = new ArrayDeque<>();

	private boolean atLineStart = true;

	/** Whether the logical line has a token and so ends in a {@link Kind#NEWLINE}. */
	private boolean lineHasTokens;

	private @Nullable StarlarkToken eof;

	StarlarkLexer(String source, @Nullable String file) {
		this.source = source;
		this.file = file;
	}

	/**
	 * The next token; {@link Kind#EOF} once the input is exhausted, and again on every
	 * later call.
	 * @return the token
	 * @throws LispReadException on a lexical error, positioned at the offending text
	 */
	StarlarkToken next() {
		StarlarkToken queued = this.pending.poll();
		if (queued != null) {
			return queued;
		}
		if (this.eof != null) {
			return this.eof;
		}
		while (true) {
			if (this.atLineStart && this.depth == 0) {
				StarlarkToken indentation = this.indentation();
				if (indentation != null) {
					return indentation;
				}
				if (this.atLineStart) {
					continue; // a blank or comment-only line
				}
			}
			if (this.pos >= this.source.length()) {
				return this.endOfInput();
			}
			char c = this.source.charAt(this.pos);
			if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
				this.pos++;
			}
			else if (c == '#') {
				this.skipComment();
			}
			else if (c == '\\') {
				int after = this.lineEndLength(this.pos + 1);
				if (after == 0) {
					throw this.error("unexpected input character '\\'", this.pos, false);
				}
				this.pos += 1 + after;
				this.newLine();
			}
			else if (c == '\n') {
				int column = this.column(this.pos);
				this.pos++;
				this.newLine();
				if (this.depth == 0) {
					this.atLineStart = true;
					if (this.lineHasTokens) {
						this.lineHasTokens = false;
						return new StarlarkToken(Kind.NEWLINE, null, this.line - 1, column);
					}
				}
			}
			else {
				this.lineHasTokens = true;
				return this.token(c);
			}
		}
	}

	/**
	 * Measures the indentation of the line starting at {@link #pos}. A blank or
	 * comment-only line is consumed whole and leaves {@link #atLineStart} set; any other
	 * line yields the {@link Kind#INDENT} or {@link Kind#OUTDENT}s its width implies, or
	 * nothing when the width is unchanged.
	 */
	private @Nullable StarlarkToken indentation() {
		int width = 0;
		int tab = -1;
		while (this.pos < this.source.length()) {
			char c = this.source.charAt(this.pos);
			if (c == ' ') {
				width++;
			}
			else if (c == '\t') {
				tab = tab < 0 ? this.pos : tab;
			}
			else if (c != '\r' && c != '\f') {
				break;
			}
			this.pos++;
		}
		if (this.pos >= this.source.length()) {
			this.atLineStart = false;
			return null;
		}
		char c = this.source.charAt(this.pos);
		if (c == '#') {
			this.skipComment();
		}
		if (this.pos < this.source.length() && this.source.charAt(this.pos) == '\n') {
			this.pos++;
			this.newLine();
			return null;
		}
		if (this.pos >= this.source.length()) {
			this.atLineStart = false;
			return null;
		}
		this.atLineStart = false;
		if (tab >= 0) {
			throw this.error("tab characters are not allowed in indentation", tab, false);
		}
		int column = this.column(this.pos);
		int top = this.indents.getLast();
		if (width > top) {
			this.indents.add(width);
			return new StarlarkToken(Kind.INDENT, null, this.line, column);
		}
		while (width < this.indents.getLast()) {
			this.indents.removeLast();
			this.pending.add(new StarlarkToken(Kind.OUTDENT, null, this.line, column));
		}
		if (width != this.indents.getLast()) {
			throw this.error("unindent does not match any outer indentation level", this.pos, false);
		}
		return this.pending.poll();
	}

	/**
	 * Whether the input is exhausted: every token left is the end of input's
	 * {@link Kind#OUTDENT}s and its {@link Kind#EOF}.
	 * @return {@code true} once the end of input was reached
	 */
	boolean exhausted() {
		return this.eof != null;
	}

	private StarlarkToken endOfInput() {
		int column = this.column(this.pos);
		if (this.lineHasTokens && this.depth == 0) {
			this.lineHasTokens = false;
			return new StarlarkToken(Kind.NEWLINE, null, this.line, column);
		}
		while (this.indents.size() > 1) {
			this.indents.removeLast();
			this.pending.add(new StarlarkToken(Kind.OUTDENT, null, this.line, column));
		}
		StarlarkToken end = new StarlarkToken(Kind.EOF, null, this.line, column);
		this.eof = end;
		this.pending.add(end);
		return this.pending.remove();
	}

	private StarlarkToken token(char c) {
		int start = this.pos;
		if (Character.isLetter(this.source.codePointAt(this.pos)) || c == '_') {
			return this.word(start);
		}
		if (isDigit(c)
				|| (c == '.' && this.pos + 1 < this.source.length() && isDigit(this.source.charAt(this.pos + 1)))) {
			return this.number(start);
		}
		if (c == '"' || c == '\'') {
			return this.string(start, false, false);
		}
		for (int length = 3; length >= 1; length--) {
			if (this.pos + length <= this.source.length()) {
				Kind kind = Kind.punctuation(this.source.substring(this.pos, this.pos + length));
				if (kind != null) {
					this.pos += length;
					this.bracket(kind);
					return new StarlarkToken(kind, null, this.line, this.column(start));
				}
			}
		}
		throw this.error("unexpected input character '" + Character.toString(this.source.codePointAt(this.pos)) + "'",
				this.pos, false);
	}

	private void bracket(Kind kind) {
		switch (kind) {
			case LEFT_PAREN, LEFT_BRACKET, LEFT_BRACE -> this.depth++;
			case RIGHT_PAREN, RIGHT_BRACKET, RIGHT_BRACE -> this.depth = Math.max(0, this.depth - 1);
			default -> {
			}
		}
	}

	private StarlarkToken word(int start) {
		while (this.pos < this.source.length()) {
			int cp = this.source.codePointAt(this.pos);
			if (!Character.isLetterOrDigit(cp) && cp != '_') {
				break;
			}
			this.pos += Character.charCount(cp);
		}
		String word = this.source.substring(start, this.pos);
		boolean quoted = this.pos < this.source.length()
				&& (this.source.charAt(this.pos) == '"' || this.source.charAt(this.pos) == '\'');
		if (quoted && STRING_PREFIXES.contains(word)) {
			return this.string(start, word.contains("r"), word.contains("b"));
		}
		Kind keyword = Kind.keyword(word);
		if (keyword != null) {
			return new StarlarkToken(keyword, null, this.line, this.column(start));
		}
		if (quoted && word.length() <= 2 && word.chars().allMatch(ch -> "fFuUrRbB".indexOf(ch) >= 0)) {
			throw this.error("unsupported string prefix '" + word + "'", start, false);
		}
		if (RESERVED.contains(word)) {
			throw this.error("reserved keyword '" + word + "' is not supported", start, false);
		}
		return new StarlarkToken(Kind.IDENTIFIER, word, this.line, this.column(start));
	}

	private StarlarkToken number(int start) {
		char first = this.source.charAt(this.pos);
		int radix = first == '0' && this.pos + 1 < this.source.length() ? radix(this.source.charAt(this.pos + 1)) : 10;
		if (radix != 10) {
			this.pos += 2;
			int digits = this.pos;
			while (this.pos < this.source.length() && this.source.charAt(this.pos) < 128
					&& Character.digit(this.source.charAt(this.pos), radix) >= 0) {
				this.pos++;
			}
			if (this.pos == digits) {
				throw this.error("invalid " + this.source.substring(start, this.pos) + " literal: no digits", start,
						false);
			}
			BigInteger value = new BigInteger(this.source.substring(digits, this.pos), radix);
			return new StarlarkToken(Kind.INT, value, this.line, this.column(start));
		}
		this.skipDigits();
		boolean isFloat = false;
		if (this.pos < this.source.length() && this.source.charAt(this.pos) == '.') {
			isFloat = true;
			this.pos++;
			this.skipDigits();
		}
		if (this.pos < this.source.length()
				&& (this.source.charAt(this.pos) == 'e' || this.source.charAt(this.pos) == 'E')) {
			int exponent = this.pos + 1;
			if (exponent < this.source.length()
					&& (this.source.charAt(exponent) == '+' || this.source.charAt(exponent) == '-')) {
				exponent++;
			}
			if (exponent < this.source.length() && isDigit(this.source.charAt(exponent))) {
				isFloat = true;
				this.pos = exponent;
				this.skipDigits();
			}
		}
		String text = this.source.substring(start, this.pos);
		if (isFloat) {
			double value = Double.parseDouble(text);
			if (Double.isInfinite(value)) {
				throw this.error("floating-point literal " + text + " is too large", start, false);
			}
			return new StarlarkToken(Kind.FLOAT, value, this.line, this.column(start));
		}
		if (text.length() > 1 && text.charAt(0) == '0') {
			throw this.error("invalid int literal " + text + ": leading zeros are not allowed (0o prefixes octal)",
					start, false);
		}
		return new StarlarkToken(Kind.INT, new BigInteger(text), this.line, this.column(start));
	}

	private static int radix(char marker) {
		return switch (marker) {
			case 'x', 'X' -> 16;
			case 'o', 'O' -> 8;
			case 'b', 'B' -> 2;
			default -> 10;
		};
	}

	private void skipDigits() {
		while (this.pos < this.source.length() && isDigit(this.source.charAt(this.pos))) {
			this.pos++;
		}
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	/**
	 * A string or bytes literal from its prefix at {@code start}; {@link #pos} is at the
	 * opening quote. Escapes per the spec's "String escapes" and "Bytes literals": a
	 * backslash before anything else is an error, an octal or hex escape is at most 127
	 * in a string and 255 in bytes, and a Unicode escape outside the scalar values is an
	 * error.
	 */
	private StarlarkToken string(int start, boolean raw, boolean bytes) {
		int startLine = this.line;
		int startColumn = this.column(start);
		char quote = this.source.charAt(this.pos);
		boolean triple = this.source.startsWith(String.valueOf(quote).repeat(3), this.pos);
		this.pos += triple ? 3 : 1;
		Literal literal = new Literal(bytes);
		while (true) {
			if (this.pos >= this.source.length()) {
				throw this.error("unterminated string literal", startLine, startColumn, true);
			}
			char c = this.source.charAt(this.pos);
			if (c == quote) {
				if (!triple) {
					this.pos++;
					break;
				}
				if (this.source.startsWith(String.valueOf(quote).repeat(3), this.pos)) {
					this.pos += 3;
					break;
				}
				literal.text(c);
				this.pos++;
			}
			else if (c == '\n' || c == '\r') {
				if (!triple) {
					throw this.error("unterminated string literal", startLine, startColumn, false);
				}
				this.pos += this.lineEndLength(this.pos);
				this.newLine();
				literal.text('\n');
			}
			else if (c == '\\') {
				this.escape(literal, raw, bytes, quote, startLine, startColumn);
			}
			else {
				literal.text(c);
				this.pos++;
			}
		}
		Kind kind = bytes ? Kind.BYTES : Kind.STRING;
		return new StarlarkToken(kind, literal.value(), startLine, startColumn);
	}

	private void escape(Literal literal, boolean raw, boolean bytes, char quote, int startLine, int startColumn) {
		int backslash = this.pos;
		if (this.pos + 1 >= this.source.length()) {
			throw this.error("unterminated string literal", startLine, startColumn, true);
		}
		char e = this.source.charAt(this.pos + 1);
		int lineEnd = this.lineEndLength(this.pos + 1);
		if (raw) {
			literal.text('\\');
			if (lineEnd > 0) {
				this.pos += 1 + lineEnd;
				this.newLine();
				literal.text('\n');
			}
			else if (e == quote || e == '\\') {
				literal.text(e);
				this.pos += 2;
			}
			else {
				this.pos++;
			}
			return;
		}
		if (lineEnd > 0) {
			this.pos += 1 + lineEnd;
			this.newLine();
			return;
		}
		this.pos += 2;
		switch (e) {
			case 'a' -> literal.unit(7);
			case 'b' -> literal.unit(8);
			case 'f' -> literal.unit(12);
			case 'n' -> literal.unit(10);
			case 'r' -> literal.unit(13);
			case 't' -> literal.unit(9);
			case 'v' -> literal.unit(11);
			case '\\', '\'', '"' -> literal.unit(e);
			case '0', '1', '2', '3', '4', '5', '6', '7' -> {
				int value = e - '0';
				for (int i = 0; i < 2 && this.pos < this.source.length() && this.source.charAt(this.pos) >= '0'
						&& this.source.charAt(this.pos) <= '7'; i++) {
					value = value * 8 + (this.source.charAt(this.pos++) - '0');
				}
				this.checkUnit(value, bytes, "octal", backslash);
				literal.unit(value);
			}
			case 'x' -> {
				int value = this.hexDigits(2, backslash);
				this.checkUnit(value, bytes, "hex", backslash);
				literal.unit(value);
			}
			case 'u', 'U' -> {
				int value = this.hexDigits(e == 'u' ? 4 : 8, backslash);
				if (value > Character.MAX_CODE_POINT || (value >= 0xD800 && value <= 0xDFFF)) {
					throw this.error("invalid Unicode code point U+" + Integer.toHexString(value).toUpperCase()
							+ " in escape " + this.source.substring(backslash, this.pos), backslash, false);
				}
				literal.codePoint(value);
			}
			default -> throw this.error("invalid escape sequence \\" + e, backslash, false);
		}
	}

	private void checkUnit(int value, boolean bytes, String radix, int backslash) {
		if (value > (bytes ? 255 : 127)) {
			throw this
				.error((bytes ? "" : "non-ASCII ") + radix + " escape " + this.source.substring(backslash, this.pos)
						+ " is out of range" + (bytes ? "" : " (use \\" + "u for a code point)"), backslash, false);
		}
	}

	private int hexDigits(int count, int backslash) {
		if (this.pos + count > this.source.length()) {
			throw this.error("truncated escape sequence " + this.source.substring(backslash), backslash, false);
		}
		long value = 0;
		for (int i = 0; i < count; i++) {
			char c = this.source.charAt(this.pos + i);
			int digit = c < 128 ? Character.digit(c, 16) : -1;
			if (digit < 0) {
				throw this.error("truncated escape sequence " + this.source.substring(backslash, this.pos + i),
						backslash, false);
			}
			value = value * 16 + digit;
		}
		this.pos += count;
		return (int) Math.min(value, Integer.MAX_VALUE);
	}

	/**
	 * The length of the line ending at {@code index}: 1 or 2 ({@code \r\n}), 0 for none.
	 */
	private int lineEndLength(int index) {
		if (index < this.source.length() && this.source.charAt(index) == '\n') {
			return 1;
		}
		if (this.source.startsWith("\r\n", index)) {
			return 2;
		}
		return 0;
	}

	private void skipComment() {
		while (this.pos < this.source.length() && this.source.charAt(this.pos) != '\n') {
			this.pos++;
		}
	}

	/** Records that {@link #pos} is the first character of a new line. */
	private void newLine() {
		this.line++;
		this.lineStart = this.pos;
	}

	private int column(int offset) {
		return offset - this.lineStart + 1;
	}

	private LispReadException error(String message, int offset, boolean endOfFile) {
		return this.error(message, this.line, this.column(offset), endOfFile);
	}

	private LispReadException error(String message, int line, int column, boolean endOfFile) {
		return new LispReadException(message, new SourceLocation(this.file, line, column), endOfFile);
	}

	/**
	 * The value of a string or bytes literal being scanned. A string collects UTF-16
	 * units; bytes collect the UTF-8 encoding of the text and the escapes' raw bytes.
	 */
	private static final class Literal {

		private final boolean bytes;

		private final StringBuilder text = new StringBuilder();

		private final ByteArrayOutputStream octets = new ByteArrayOutputStream();

		/** A pending high surrogate of literal text, completed by the next unit. */
		private char high;

		Literal(boolean bytes) {
			this.bytes = bytes;
		}

		/** A character of the literal's source text. */
		void text(char c) {
			if (!this.bytes) {
				this.text.append(c);
			}
			else if (Character.isHighSurrogate(c)) {
				this.high = c;
			}
			else if (Character.isLowSurrogate(c) && this.high != 0) {
				this.codePoint(Character.toCodePoint(this.high, c));
				this.high = 0;
			}
			else {
				this.codePoint(c);
			}
		}

		/** One element of the value: a char of a string, a byte of bytes. */
		void unit(int value) {
			if (this.bytes) {
				this.octets.write(value);
			}
			else {
				this.text.append((char) value);
			}
		}

		void codePoint(int codePoint) {
			if (this.bytes) {
				this.octets.writeBytes(Character.toString(codePoint).getBytes(StandardCharsets.UTF_8));
			}
			else {
				this.text.appendCodePoint(codePoint);
			}
		}

		Object value() {
			return this.bytes ? this.octets.toByteArray() : this.text.toString();
		}

	}

}
