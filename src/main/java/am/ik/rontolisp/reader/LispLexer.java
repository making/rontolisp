package am.ik.rontolisp.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for Lisp source code.
 */
public final class LispLexer {

	private final String input;

	private int pos;

	/**
	 * Create a new lexer for the given input.
	 * @param input the source code string
	 */
	public LispLexer(String input) {
		this.input = input;
		this.pos = 0;
	}

	/**
	 * Tokenize the input into a list of tokens.
	 * @return the tokens
	 */
	public List<Token> tokenize() {
		List<Token> tokens = new ArrayList<>();
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (Character.isWhitespace(c)) {
				this.pos++;
			}
			else if (c == ';') {
				skipComment();
			}
			else if (c == '(') {
				tokens.add(new Token.LeftParen());
				this.pos++;
			}
			else if (c == ')') {
				tokens.add(new Token.RightParen());
				this.pos++;
			}
			else if (c == '\'') {
				tokens.add(new Token.Quote());
				this.pos++;
			}
			else if (c == '`') {
				tokens.add(new Token.Backquote());
				this.pos++;
			}
			else if (c == ',') {
				// A comma between two digits is a grouping separator consumed inside
				// readNumber (e.g. "1,000"), so a comma reaching here starts a token:
				// unquote (,x) or unquote-splicing (,@x) inside a backquote template.
				if (this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '@') {
					tokens.add(new Token.UnquoteSplicing());
					this.pos += 2;
				}
				else {
					tokens.add(new Token.Unquote());
					this.pos++;
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '\'') {
				tokens.add(new Token.FunctionQuote());
				this.pos += 2;
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '\\') {
				tokens.add(readChar());
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '(') {
				tokens.add(new Token.VectorOpen());
				this.pos += 2;
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				// #nA( opens a rank-n array literal (e.g., #2A((1 2) (3 4))). Anything
				// else after #<digits> falls through to symbol reading, preserving the
				// previous tokenization.
				int probe = this.pos + 1;
				while (probe < this.input.length() && isDigit(this.input.charAt(probe))) {
					probe++;
				}
				if (probe + 1 < this.input.length()
						&& (this.input.charAt(probe) == 'A' || this.input.charAt(probe) == 'a')
						&& this.input.charAt(probe + 1) == '(') {
					int rank;
					try {
						rank = Integer.parseInt(this.input.substring(this.pos + 1, probe));
					}
					catch (NumberFormatException overflow) {
						throw new LispReadException("Invalid array rank: " + this.input.substring(this.pos, probe));
					}
					tokens.add(new Token.ArrayOpen(rank));
					this.pos = probe + 2;
				}
				else {
					tokens.add(readSymbol());
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && isRadixMarker(this.input.charAt(this.pos + 1))) {
				tokens.add(readRadixNumber());
			}
			else if (c == '.') {
				if (this.pos + 1 >= this.input.length() || !isSymbolChar(this.input.charAt(this.pos + 1))) {
					tokens.add(new Token.Dot());
					this.pos++;
				}
				else {
					tokens.add(readSymbol());
				}
			}
			else if (c == '"') {
				tokens.add(readString());
			}
			else if (isDigit(c)) {
				tokens.add(readNumber());
			}
			else if (c == '-' && this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				tokens.add(readNumber());
			}
			else {
				tokens.add(readSymbol());
			}
		}
		return tokens;
	}

	private void skipComment() {
		while (this.pos < this.input.length() && this.input.charAt(this.pos) != '\n') {
			this.pos++;
		}
	}

	private Token readNumber() {
		int start = this.pos;
		if (this.input.charAt(this.pos) == '-') {
			this.pos++;
		}
		// Integer digits, allowing ',' as a grouping separator when it sits
		// between two digits (e.g., "1,000" -> 1000). A comma not followed by a
		// digit is not consumed, so token boundaries are otherwise unchanged.
		consumeDigitsWithGrouping();
		boolean isFloat = false;
		// Fractional part: '.' followed by at least one digit (e.g., "1.5").
		if (this.pos < this.input.length() && this.input.charAt(this.pos) == '.' && this.pos + 1 < this.input.length()
				&& isDigit(this.input.charAt(this.pos + 1))) {
			this.pos++; // consume '.'
			while (this.pos < this.input.length() && isDigit(this.input.charAt(this.pos))) {
				this.pos++;
			}
			isFloat = true;
		}
		// Exponent part: a Common Lisp float marker e/s/f/d/l (case-insensitive)
		// followed by an optional sign and at least one digit (e.g., "1d0",
		// "1e0", "1.5f3", "-2d-3"). rontolisp has a single float type, so every
		// marker collapses to the same LispDouble (the single/double distinction
		// of Common Lisp is not preserved).
		if (consumedExponent()) {
			isFloat = true;
		}
		// Ratio literal: integer digits '/' integer digits (e.g., "1/3", "-1/3").
		if (!isFloat && this.pos < this.input.length() && this.input.charAt(this.pos) == '/'
				&& this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
			int slash = this.pos;
			this.pos++; // consume '/'
			consumeDigitsWithGrouping();
			// If a symbol character follows the denominator digits (e.g., "1/2x",
			// "1/2/3"), treat the entire token as a symbol.
			if (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
					this.pos++;
				}
				return new Token.SymbolToken(this.input.substring(start, this.pos));
			}
			String numerator = stripGrouping(this.input.substring(start, slash));
			String denominator = stripGrouping(this.input.substring(slash + 1, this.pos));
			return new Token.RatioToken(new java.math.BigInteger(numerator), new java.math.BigInteger(denominator));
		}
		// If a non-dot symbol character follows, treat the entire token as a
		// symbol (e.g., "1+" -> Symbol("1+"), "1d0x" -> Symbol("1d0x")).
		if (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))
				&& this.input.charAt(this.pos) != '.') {
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
			return new Token.SymbolToken(this.input.substring(start, this.pos));
		}
		if (isFloat) {
			return new Token.DoubleToken(
					Double.parseDouble(normalizeExponentMarker(stripGrouping(this.input.substring(start, this.pos)))));
		}
		String digits = stripGrouping(this.input.substring(start, this.pos));
		try {
			return new Token.NumberToken(Long.parseLong(digits));
		}
		catch (NumberFormatException overflow) {
			// Literal does not fit in a long: promote to an arbitrary-precision integer.
			return new Token.BigIntegerToken(new java.math.BigInteger(digits));
		}
	}

	private static boolean isRadixMarker(char c) {
		return c == 'x' || c == 'X' || c == 'o' || c == 'O' || c == 'b' || c == 'B';
	}

	// Reads a #x/#o/#b radix integer literal (e.g., #x10000, #o400, #b1010, #x-10).
	// The digits (after an optional sign) must be non-empty and valid in the radix;
	// a literal that does not fit in a long is promoted to an arbitrary-precision
	// integer, matching decimal literals.
	private Token readRadixNumber() {
		char marker = this.input.charAt(this.pos + 1);
		int radix = switch (Character.toLowerCase(marker)) {
			case 'x' -> 16;
			case 'o' -> 8;
			default -> 2;
		};
		this.pos += 2; // skip "#x" / "#o" / "#b"
		int start = this.pos;
		if (this.pos < this.input.length() && this.input.charAt(this.pos) == '-') {
			this.pos++;
		}
		int digitsStart = this.pos;
		while (this.pos < this.input.length() && Character.digit(this.input.charAt(this.pos), radix) >= 0) {
			this.pos++;
		}
		if (this.pos == digitsStart || (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos)))) {
			throw new LispReadException("Invalid digits after #" + marker + ": "
					+ this.input.substring(start, Math.min(this.pos + 1, this.input.length())));
		}
		String digits = this.input.substring(start, this.pos);
		try {
			return new Token.NumberToken(Long.parseLong(digits, radix));
		}
		catch (NumberFormatException overflow) {
			return new Token.BigIntegerToken(new java.math.BigInteger(digits, radix));
		}
	}

	private void consumeDigitsWithGrouping() {
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (isDigit(c)) {
				this.pos++;
			}
			else if (c == ',' && this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				this.pos++; // consume grouping separator between digits
			}
			else {
				break;
			}
		}
	}

	// Consume an exponent suffix (marker + optional sign + digits) if a valid
	// one starts at the current position, advancing past it and returning true.
	// On no match, the position is left untouched so the marker can fall through
	// to symbol handling (e.g. "1d" is the symbol "1d", not a float).
	private boolean consumedExponent() {
		if (this.pos >= this.input.length() || !isExponentMarker(this.input.charAt(this.pos))) {
			return false;
		}
		int probe = this.pos + 1;
		if (probe < this.input.length() && (this.input.charAt(probe) == '+' || this.input.charAt(probe) == '-')) {
			probe++;
		}
		if (probe >= this.input.length() || !isDigit(this.input.charAt(probe))) {
			return false;
		}
		while (probe < this.input.length() && isDigit(this.input.charAt(probe))) {
			probe++;
		}
		this.pos = probe;
		return true;
	}

	private static boolean isExponentMarker(char c) {
		return c == 'e' || c == 'E' || c == 's' || c == 'S' || c == 'f' || c == 'F' || c == 'd' || c == 'D' || c == 'l'
				|| c == 'L';
	}

	// Rewrite the Common Lisp exponent marker to 'e' so Double.parseDouble (which
	// only accepts 'e'/'E' as an exponent marker) reads the literal. A valid float
	// token holds at most one marker, so replacing every marker char is safe.
	private static String normalizeExponentMarker(String number) {
		StringBuilder sb = new StringBuilder(number.length());
		for (int i = 0; i < number.length(); i++) {
			char c = number.charAt(i);
			sb.append(isExponentMarker(c) ? 'e' : c);
		}
		return sb.toString();
	}

	private static String stripGrouping(String number) {
		return number.indexOf(',') < 0 ? number : number.replace(",", "");
	}

	// Reads a #\ character literal: a single character (#\a, #\(, #\Space-the-glyph)
	// or, when the first character is a letter and more letters follow, a character
	// name (#\Space, #\Newline, ...). The first character after #\ is always taken
	// literally even if it is whitespace or a delimiter.
	private Token.CharToken readChar() {
		this.pos += 2; // skip "#\"
		if (this.pos >= this.input.length()) {
			throw new LispReadException("Unexpected end of input after #\\");
		}
		int start = this.pos;
		char first = this.input.charAt(this.pos);
		this.pos++;
		// A multi-character name only follows an alphabetic first character.
		if (Character.isLetter(first)) {
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
		}
		String token = this.input.substring(start, this.pos);
		if (token.length() == 1) {
			return new Token.CharToken(first);
		}
		return new Token.CharToken(charByName(token));
	}

	private static int charByName(String name) {
		return switch (name.toLowerCase(java.util.Locale.ROOT)) {
			case "space" -> ' ';
			case "newline", "linefeed", "lf" -> '\n';
			case "tab" -> '\t';
			case "return", "cr" -> '\r';
			case "page" -> '\f';
			case "backspace" -> '\b';
			case "nul", "null" -> 0;
			case "rubout", "delete", "del" -> 127;
			case "escape", "altmode", "esc" -> 27;
			default -> throw new LispReadException("Unknown character name: #\\" + name);
		};
	}

	private Token.SymbolToken readSymbol() {
		int start = this.pos;
		while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
			this.pos++;
		}
		return new Token.SymbolToken(this.input.substring(start, this.pos));
	}

	private Token.StringToken readString() {
		this.pos++; // skip opening "
		StringBuilder sb = new StringBuilder();
		while (this.pos < this.input.length() && this.input.charAt(this.pos) != '"') {
			if (this.input.charAt(this.pos) == '\\' && this.pos + 1 < this.input.length()) {
				this.pos++;
				char escaped = this.input.charAt(this.pos);
				switch (escaped) {
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case '\\' -> sb.append('\\');
					case '"' -> sb.append('"');
					default -> {
						sb.append('\\');
						sb.append(escaped);
					}
				}
			}
			else {
				sb.append(this.input.charAt(this.pos));
			}
			this.pos++;
		}
		if (this.pos >= this.input.length()) {
			throw new LispReadException("Unterminated string literal");
		}
		this.pos++; // skip closing "
		return new Token.StringToken(sb.toString());
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isSymbolChar(char c) {
		// ',' and '`' terminate a symbol (Common Lisp terminating macro characters)
		// so `(a ,b) and `(,x ,@xs) tokenize without surrounding whitespace. A comma
		// used as a digit-grouping separator ("1,000") is consumed inside readNumber
		// before this predicate is consulted.
		return !Character.isWhitespace(c) && c != '(' && c != ')' && c != '\'' && c != '"' && c != ';' && c != ','
				&& c != '`';
	}

}
