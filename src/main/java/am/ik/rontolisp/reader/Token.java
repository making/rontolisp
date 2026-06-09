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

	/** Dot ({@code .}) token. */
	record Dot() implements Token {
	}

	/** End-of-file token. */
	record Eof() implements Token {
	}

}
