package am.ik.rontolisp.reader;

import am.ik.rontolisp.FloatWidth;

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
	 * Sharp-L ({@code #L} / {@code #nL}) token: iterate's numbered-argument lambda
	 * abbreviation, which the reader lowers over the datum that follows.
	 *
	 * @param nArgs the explicit argument count of {@code #nL}, or -1 for a bare
	 * {@code #L} (the count is then the highest {@code !n} the body mentions)
	 */
	record SharpL(int nArgs) implements Token {
	}

	/**
	 * Structure literal open ({@code #S(} / {@code #s(}) token; closed by the matching
	 * {@link RightParen}. The contents are the type name followed by alternating slot
	 * names and values, read as data.
	 */
	record StructOpen() implements Token {
	}

	/**
	 * Pathname literal open ({@code #P"} / {@code #p"}) token: the {@link StringToken}
	 * that follows is the namestring, and the reader builds the pathname value (a
	 * {@code LispInstance} over the fixed {@code LispLayout.PATHNAME}) from it. Only
	 * {@code #P} directly followed by a string is the dispatch; {@code #PFOO} stays a
	 * symbol.
	 */
	record PathnameOpen() implements Token {
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
	 * Reader-label definition ({@code #n=}) token: the next datum is recorded under the
	 * label. Lite: forward/circular references are not supported.
	 *
	 * @param label the label number
	 */
	record LabelDef(int label) implements Token {
	}

	/**
	 * Reader-label reference ({@code #n#}) token: stands for the datum recorded by the
	 * matching {@link LabelDef}.
	 *
	 * @param label the label number
	 */
	record LabelRef(int label) implements Token {
	}

	/**
	 * Bit-vector literal token ({@code #*1010}): the bits follow {@code #*} directly,
	 * {@code #*} alone is the empty bit vector. Read into the general vector holding the
	 * integers 0/1 (rontolisp has no packed bit representation).
	 *
	 * @param bits the literal's 0/1 digits, in order
	 */
	record BitVectorToken(String bits) implements Token {
	}

	/**
	 * Packed float-array literal open token: {@code #f(} for the single-float width
	 * ({@code single}) and {@code #d(} for the double-float width; closed by the
	 * {@link RightParen} matching the opening parenthesis. The rank is inferred from the
	 * nesting depth of the contents at read time.
	 *
	 * @param width which packed float width the literal opens -- an
	 * {@link am.ik.rontolisp.FloatWidth} rather than a boolean, so reading a width off a
	 * literal is an exhaustive {@code switch} and a fourth width has to be answered
	 * rather than falling into the double-float arm
	 */
	record FloatArrayOpen(FloatWidth width) implements Token {
	}

	/**
	 * Packed integer-vector literal open token ({@code #8@(} / {@code #16@(} /
	 * {@code #32@(} -- ironclad's array-reader dispatch syntax); closed by the
	 * {@link RightParen} matching the opening parenthesis. Read into a rank-1
	 * {@code LispIntVector} of the given element width; any other width reads as a plain
	 * {@code #(...)} vector.
	 *
	 * @param width the element width in bits (8, 16 or 32)
	 */
	record IntVectorOpen(int width) implements Token {
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
