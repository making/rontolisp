package am.ik.rontolisp.java;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.java.JavaToken.Kind;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Text to tokens, by the lexical grammar of the language specification. A {@code >} is
 * always a token of its own: the parser joins adjacent ones into a shift or a comparison
 * where an operator can stand, so a type argument list closes with no look-back.
 */
final class JavaLexer {

	private static final String[] OPERATORS = { "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", "+=", "-=",
			"*=", "/=", "%=", "&=", "|=", "^=", "<<=", "<<" };

	private final String source;

	private final @Nullable String file;

	private int at;

	private final List<JavaToken> tokens = new ArrayList<>();

	private JavaLexer(String source, @Nullable String file) {
		this.source = source;
		this.file = file;
	}

	/**
	 * Reads every token of a source.
	 * @param source the text
	 * @param file the origin file, or {@code null}
	 * @return the tokens, the last one {@link Kind#END}
	 * @throws LispReadException at the first character or literal that is none of the
	 * language's, or one the front end does not have yet
	 */
	static List<JavaToken> lex(String source, @Nullable String file) {
		JavaLexer lexer = new JavaLexer(source, file);
		lexer.run();
		return lexer.tokens;
	}

	private LispReadException error(String message, int offset) {
		return new LispReadException(message, SourceLocation.at(this.file, offset, this.source),
				offset >= this.source.length());
	}

	private void run() {
		int n = this.source.length();
		while (this.at < n) {
			char c = this.source.charAt(this.at);
			if (Character.isWhitespace(c)) {
				this.at++;
			}
			else if (this.source.startsWith("//", this.at)) {
				while (this.at < n && this.source.charAt(this.at) != '\n') {
					this.at++;
				}
			}
			else if (this.source.startsWith("/*", this.at)) {
				int end = this.source.indexOf("*/", this.at + 2);
				if (end < 0) {
					throw error("unterminated comment", n);
				}
				this.at = end + 2;
			}
			else if (Character.isJavaIdentifierStart(c)) {
				int start = this.at;
				while (this.at < n && Character.isJavaIdentifierPart(this.source.charAt(this.at))) {
					this.at++;
				}
				this.tokens.add(JavaToken.of(Kind.IDENTIFIER, this.source.substring(start, this.at), start));
			}
			else if (Character.isDigit(c)
					|| (c == '.' && this.at + 1 < n && Character.isDigit(this.source.charAt(this.at + 1)))) {
				number();
			}
			else if (this.source.startsWith("\"\"\"", this.at)) {
				throw error("a text block is not supported by the experimental Java subset yet", this.at);
			}
			else if (c == '"') {
				string();
			}
			else if (c == '\'') {
				character();
			}
			else {
				operator(c);
			}
		}
		this.tokens.add(JavaToken.of(Kind.END, "", n));
	}

	private void operator(char c) {
		int start = this.at;
		for (String operator : OPERATORS) {
			if (this.source.startsWith(operator, start)) {
				this.at += operator.length();
				this.tokens.add(JavaToken.of(Kind.OPERATOR, operator, start));
				return;
			}
		}
		if ("(){}[];,.@=<>!~?:+-*/&|^%".indexOf(c) < 0) {
			throw error("illegal character: '" + c + "'", start);
		}
		this.at++;
		this.tokens.add(JavaToken.of(Kind.OPERATOR, String.valueOf(c), start));
	}

	private void number() {
		int start = this.at;
		int n = this.source.length();
		int radix = 10;
		if (this.source.startsWith("0x", start) || this.source.startsWith("0X", start)) {
			radix = 16;
			this.at += 2;
		}
		else if (this.source.startsWith("0b", start) || this.source.startsWith("0B", start)) {
			radix = 2;
			this.at += 2;
		}
		int digits = this.at;
		boolean floating = false;
		while (this.at < n) {
			char d = this.source.charAt(this.at);
			if (Character.digit(d, radix) >= 0 || d == '_') {
				this.at++;
			}
			else if (radix == 10 && d == '.' && !floating && !this.source.startsWith("...", this.at)) {
				// a digit, an exponent or the end of the literal may follow the point
				floating = true;
				this.at++;
			}
			else if (radix == 10 && (d == 'e' || d == 'E')) {
				floating = true;
				this.at++;
				if (this.at < n && (this.source.charAt(this.at) == '+' || this.source.charAt(this.at) == '-')) {
					this.at++;
				}
			}
			else {
				break;
			}
		}
		String text = this.source.substring(digits, this.at).replace("_", "");
		char suffix = this.at < n ? this.source.charAt(this.at) : ' ';
		if (radix == 16 && (suffix == '.' || suffix == 'p' || suffix == 'P')) {
			throw error("a hexadecimal floating-point literal is not supported by the experimental Java subset yet",
					start);
		}
		if (suffix == 'f' || suffix == 'F') {
			if (radix == 10) {
				throw error("the type float is not supported by the experimental Java subset yet", start);
			}
		}
		if (radix == 10 && (floating || suffix == 'd' || suffix == 'D')) {
			if (suffix == 'd' || suffix == 'D') {
				this.at++;
			}
			double value;
			try {
				value = Double.parseDouble(text);
			}
			catch (NumberFormatException ex) {
				throw error("malformed floating-point literal", start);
			}
			if (Double.isInfinite(value)) {
				throw error("floating-point number too large", start);
			}
			this.tokens.add(new JavaToken(Kind.DOUBLE, text, null, false, value, start));
			endOfNumber(start);
			return;
		}
		boolean isLong = suffix == 'L' || suffix == 'l';
		if (isLong) {
			this.at++;
		}
		if (radix == 10 && text.length() > 1 && text.charAt(0) == '0') {
			radix = 8;
		}
		BigInteger value;
		try {
			value = new BigInteger(text, radix);
		}
		catch (NumberFormatException ex) {
			throw error("malformed number", start);
		}
		this.tokens.add(new JavaToken(isLong ? Kind.LONG : Kind.INT, text, value, radix == 10, 0, start));
		endOfNumber(start);
	}

	private void endOfNumber(int start) {
		if (this.at < this.source.length() && Character.isJavaIdentifierPart(this.source.charAt(this.at))) {
			throw error("malformed number", start);
		}
	}

	private void string() {
		int start = this.at++;
		StringBuilder value = new StringBuilder();
		while (true) {
			if (this.at >= this.source.length() || this.source.charAt(this.at) == '\n') {
				throw error("unterminated string literal", this.at >= this.source.length() ? this.at : start);
			}
			char c = this.source.charAt(this.at);
			if (c == '"') {
				this.at++;
				break;
			}
			value.append(c == '\\' ? escape() : (char) next());
		}
		this.tokens.add(JavaToken.of(Kind.STRING, value.toString(), start));
	}

	private void character() {
		int start = this.at++;
		if (this.at >= this.source.length() || this.source.charAt(this.at) == '\''
				|| this.source.charAt(this.at) == '\n') {
			throw error("empty or unterminated character literal", start);
		}
		int value = this.source.charAt(this.at) == '\\' ? escape() : next();
		if (this.at >= this.source.length() || this.source.charAt(this.at) != '\'') {
			throw error("unterminated character literal", start);
		}
		this.at++;
		this.tokens.add(new JavaToken(Kind.CHAR, "", null, false, value, start));
	}

	private int next() {
		return this.source.charAt(this.at++);
	}

	/** An escape sequence, the backslash included: the UTF-16 unit it names. */
	private char escape() {
		int start = this.at++;
		if (this.at >= this.source.length()) {
			throw error("unterminated escape sequence", this.at);
		}
		char c = this.source.charAt(this.at++);
		switch (c) {
			case 'b':
				return '\b';
			case 't':
				return '\t';
			case 'n':
				return '\n';
			case 'f':
				return '\f';
			case 'r':
				return '\r';
			case 's':
				return ' ';
			case '"', '\'', '\\':
				return c;
			case 'u': {
				while (this.at < this.source.length() && this.source.charAt(this.at) == 'u') {
					this.at++;
				}
				if (this.at + 4 > this.source.length()) {
					throw error("illegal unicode escape", start);
				}
				try {
					char unit = (char) Integer.parseInt(this.source.substring(this.at, this.at + 4), 16);
					this.at += 4;
					return unit;
				}
				catch (NumberFormatException ex) {
					throw error("illegal unicode escape", start);
				}
			}
			default:
				if (c >= '0' && c <= '7') {
					int value = c - '0';
					int most = c <= '3' ? 2 : 1;
					while (most-- > 0 && this.at < this.source.length() && this.source.charAt(this.at) >= '0'
							&& this.source.charAt(this.at) <= '7') {
						value = value * 8 + (this.source.charAt(this.at++) - '0');
					}
					return (char) value;
				}
				throw error("illegal escape character", start);
		}
	}

}
