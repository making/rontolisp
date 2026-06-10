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
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '\'') {
				tokens.add(new Token.FunctionQuote());
				this.pos += 2;
			}
			else if (c == '.') {
				if (this.pos + 1 < this.input.length() && !isSymbolChar(this.input.charAt(this.pos + 1))) {
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
		if (this.pos < this.input.length() && this.input.charAt(this.pos) == '/' && this.pos + 1 < this.input.length()
				&& isDigit(this.input.charAt(this.pos + 1))) {
			int slash = this.pos;
			this.pos++; // consume '/'
			int denominatorStart = this.pos;
			consumeDigitsWithGrouping();
			// If symbol characters follow, treat the whole lexeme as a symbol.
			if (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))
					&& this.input.charAt(this.pos) != '.') {
				while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
					this.pos++;
				}
				return new Token.SymbolToken(this.input.substring(start, this.pos));
			}
			String numerator = stripGrouping(this.input.substring(start, slash));
			String denominator = stripGrouping(this.input.substring(denominatorStart, this.pos));
			return new Token.FractionToken(new java.math.BigInteger(numerator), new java.math.BigInteger(denominator));
		}
		if (this.pos < this.input.length() && this.input.charAt(this.pos) == '.' && this.pos + 1 < this.input.length()
				&& isDigit(this.input.charAt(this.pos + 1))) {
			this.pos++; // consume '.'
			while (this.pos < this.input.length() && isDigit(this.input.charAt(this.pos))) {
				this.pos++;
			}
			// If non-dot symbol character follows digits, treat entire token as symbol
			if (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))
					&& this.input.charAt(this.pos) != '.') {
				while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
					this.pos++;
				}
				return new Token.SymbolToken(this.input.substring(start, this.pos));
			}
			return new Token.DoubleToken(Double.parseDouble(stripGrouping(this.input.substring(start, this.pos))));
		}
		// If non-dot symbol character follows digits, treat entire token as symbol
		// (e.g., "1+" -> Symbol("1+"), "1-" -> Symbol("1-"))
		if (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))
				&& this.input.charAt(this.pos) != '.') {
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
			return new Token.SymbolToken(this.input.substring(start, this.pos));
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

	private static String stripGrouping(String number) {
		return number.indexOf(',') < 0 ? number : number.replace(",", "");
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
		return !Character.isWhitespace(c) && c != '(' && c != ')' && c != '\'' && c != '"' && c != ';';
	}

}
