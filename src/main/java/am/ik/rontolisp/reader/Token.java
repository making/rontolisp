package am.ik.rontolisp.reader;

/**
 * Token types produced by the lexer.
 */
public sealed interface Token {

	/** Left parenthesis token. */
	record LeftParen() implements Token {
	}

	/** Right parenthesis token. */
	record RightParen() implements Token {
	}

	/** Quote ({@code '}) token. */
	record Quote() implements Token {
	}

	/** Function quote ({@code #'}) token. */
	record FunctionQuote() implements Token {
	}

	/** Backquote ({@code `}) token. */
	record Backquote() implements Token {
	}

	/** Unquote ({@code ,}) token; only valid inside a backquote template. */
	record Unquote() implements Token {
	}

	/** Unquote-splicing ({@code ,@}) token; only valid inside a backquote template. */
	record UnquoteSplicing() implements Token {
	}

	/** Vector literal open ({@code #(}) token; closed by a {@link RightParen}. */
	record VectorOpen() implements Token {
	}

	/**
	 * Rank-n array literal open ({@code #nA(}) token; closed by the {@link RightParen}
	 * matching the opening parenthesis.
	 *
	 * @param rank the array rank (the digits between {@code #} and {@code A})
	 */
	record ArrayOpen(int rank) implements Token {
	}

	/**
	 * Integer number token.
	 *
	 * @param value the integer value
	 */
	record NumberToken(long value) implements Token {
	}

	/**
	 * Arbitrary-precision integer token, produced when an integer literal does not fit in
	 * a {@code long}.
	 *
	 * @param value the big integer value
	 */
	record BigIntegerToken(java.math.BigInteger value) implements Token {
	}

	/**
	 * Ratio literal token (e.g., {@code 1/3}).
	 *
	 * @param numerator the numerator
	 * @param denominator the denominator
	 */
	record RatioToken(java.math.BigInteger numerator, java.math.BigInteger denominator) implements Token {
	}

	/**
	 * Floating-point number token.
	 *
	 * @param value the double value
	 */
	record DoubleToken(double value) implements Token {
	}

	/**
	 * Symbol token.
	 *
	 * @param name the symbol name
	 */
	record SymbolToken(String name) implements Token {
	}

	/**
	 * String literal token.
	 *
	 * @param value the string content
	 */
	record StringToken(String value) implements Token {
	}

	/**
	 * Character literal token (e.g., {@code #\a}, {@code #\Space}).
	 *
	 * @param codePoint the Unicode code point of the character
	 */
	record CharToken(int codePoint) implements Token {
	}

	/** Dot ({@code .}) token. */
	record Dot() implements Token {
	}

	/** End-of-file token. */
	record Eof() implements Token {
	}

}
