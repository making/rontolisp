package am.ik.rontolisp.reader;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LispLexerTest {

	@Test
	void tokenizeSimpleExpression() {
		List<Token> tokens = new LispLexer("(+ 1 2)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("+"), new Token.NumberToken(1),
				new Token.NumberToken(2), new Token.RightParen());
	}

	@Test
	void tokenizeNestedExpression() {
		List<Token> tokens = new LispLexer("(print (+ 1 (* 2 3)))").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("print"), new Token.LeftParen(),
				new Token.SymbolToken("+"), new Token.NumberToken(1), new Token.LeftParen(), new Token.SymbolToken("*"),
				new Token.NumberToken(2), new Token.NumberToken(3), new Token.RightParen(), new Token.RightParen(),
				new Token.RightParen());
	}

	@Test
	void tokenizeNegativeNumber() {
		List<Token> tokens = new LispLexer("(- -3 2)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("-"), new Token.NumberToken(-3),
				new Token.NumberToken(2), new Token.RightParen());
	}

	@Test
	void tokenizeStringLiteral() {
		List<Token> tokens = new LispLexer("(print \"hello\")").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("print"),
				new Token.StringToken("hello"), new Token.RightParen());
	}

	@Test
	void tokenizeWithComment() {
		List<Token> tokens = new LispLexer("(+ 1 ; comment\n 2)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("+"), new Token.NumberToken(1),
				new Token.NumberToken(2), new Token.RightParen());
	}

	@Test
	void tokenizeQuote() {
		List<Token> tokens = new LispLexer("'foo").tokenize();
		assertThat(tokens).containsExactly(new Token.Quote(), new Token.SymbolToken("foo"));
	}

	@Test
	void tokenizeEmptyList() {
		List<Token> tokens = new LispLexer("()").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.RightParen());
	}

	@Test
	void tokenizeDoubleNumber() {
		List<Token> tokens = new LispLexer("3.14").tokenize();
		assertThat(tokens).containsExactly(new Token.DoubleToken(3.14));
	}

	@Test
	void tokenizeNegativeDouble() {
		List<Token> tokens = new LispLexer("-1.5").tokenize();
		assertThat(tokens).containsExactly(new Token.DoubleToken(-1.5));
	}

	@Test
	void tokenizeOnePlus() {
		List<Token> tokens = new LispLexer("(1+ x)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("1+"),
				new Token.SymbolToken("x"), new Token.RightParen());
	}

	@Test
	void tokenizeOneMinus() {
		List<Token> tokens = new LispLexer("(1- x)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("1-"),
				new Token.SymbolToken("x"), new Token.RightParen());
	}

	@Test
	void tokenizeMixedIntAndDouble() {
		List<Token> tokens = new LispLexer("(+ 1 2.5)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("+"), new Token.NumberToken(1),
				new Token.DoubleToken(2.5), new Token.RightParen());
	}

	@Test
	void tokenizeGroupedInteger() {
		List<Token> tokens = new LispLexer("1,000").tokenize();
		assertThat(tokens).containsExactly(new Token.NumberToken(1000));
	}

	@Test
	void tokenizeMultiGroupedInteger() {
		List<Token> tokens = new LispLexer("1,234,567").tokenize();
		assertThat(tokens).containsExactly(new Token.NumberToken(1234567));
	}

	@Test
	void tokenizeGroupedNegativeInteger() {
		List<Token> tokens = new LispLexer("-1,000").tokenize();
		assertThat(tokens).containsExactly(new Token.NumberToken(-1000));
	}

	@Test
	void tokenizeGroupedDouble() {
		List<Token> tokens = new LispLexer("3,000.50").tokenize();
		assertThat(tokens).containsExactly(new Token.DoubleToken(3000.5));
	}

	@Test
	void tokenizeGroupedIntegerInExpression() {
		List<Token> tokens = new LispLexer("(+ 1,000 100)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("+"),
				new Token.NumberToken(1000), new Token.NumberToken(100), new Token.RightParen());
	}

	@Test
	void tokenizeCommaNotBetweenDigitsIsNotGrouping() {
		// A comma not followed by a digit is not a grouping separator: the number ends
		// and the comma starts an unquote token (backquote syntax).
		List<Token> tokens = new LispLexer("1,").tokenize();
		assertThat(tokens).containsExactly(new Token.NumberToken(1), new Token.Unquote());
	}

	@Test
	void tokenizeBackquoteAndUnquotes() {
		List<Token> tokens = new LispLexer("`(a ,b ,@c)").tokenize();
		assertThat(tokens).containsExactly(new Token.Backquote(), new Token.LeftParen(), new Token.SymbolToken("a"),
				new Token.Unquote(), new Token.SymbolToken("b"), new Token.UnquoteSplicing(),
				new Token.SymbolToken("c"), new Token.RightParen());
	}

	@Test
	void tokenizeDoubleFloatMarker() {
		// "1d0" is the Common Lisp double-float literal for 1.0.
		List<Token> tokens = new LispLexer("1d0").tokenize();
		assertThat(tokens).containsExactly(new Token.DoubleToken(1.0));
	}

	@Test
	void tokenizeExponentMarkers() {
		// Every Common Lisp float marker (e/s/f/d/l) collapses to the same double.
		assertThat(new LispLexer("1e0").tokenize()).containsExactly(new Token.DoubleToken(1.0));
		assertThat(new LispLexer("1s0").tokenize()).containsExactly(new Token.DoubleToken(1.0));
		assertThat(new LispLexer("1f0").tokenize()).containsExactly(new Token.DoubleToken(1.0));
		assertThat(new LispLexer("1L0").tokenize()).containsExactly(new Token.DoubleToken(1.0));
		assertThat(new LispLexer("1.5d3").tokenize()).containsExactly(new Token.DoubleToken(1500.0));
		assertThat(new LispLexer("-2d-3").tokenize()).containsExactly(new Token.DoubleToken(-0.002));
		assertThat(new LispLexer("6.02e23").tokenize()).containsExactly(new Token.DoubleToken(6.02e23));
	}

	@Test
	void tokenizeExponentMarkerInExpression() {
		List<Token> tokens = new LispLexer("(* 2 1d0)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("*"), new Token.NumberToken(2),
				new Token.DoubleToken(1.0), new Token.RightParen());
	}

	@Test
	void tokenizeMarkerWithoutExponentDigitsIsSymbol() {
		// A marker not followed by digits is not an exponent: the whole token is a
		// symbol, so "1d" and "1d0x" stay symbols rather than becoming floats.
		assertThat(new LispLexer("1d").tokenize()).containsExactly(new Token.SymbolToken("1d"));
		assertThat(new LispLexer("1d0x").tokenize()).containsExactly(new Token.SymbolToken("1d0x"));
	}

	@Test
	void tokenizeRadixIntegerLiterals() {
		assertThat(new LispLexer("#x10000").tokenize()).containsExactly(new Token.NumberToken(0x10000));
		assertThat(new LispLexer("#xff").tokenize()).containsExactly(new Token.NumberToken(255));
		assertThat(new LispLexer("#XFF").tokenize()).containsExactly(new Token.NumberToken(255));
		assertThat(new LispLexer("#o400").tokenize()).containsExactly(new Token.NumberToken(256));
		assertThat(new LispLexer("#b1010").tokenize()).containsExactly(new Token.NumberToken(10));
		assertThat(new LispLexer("#x-10").tokenize()).containsExactly(new Token.NumberToken(-16));
		assertThat(new LispLexer("(+ #x10 #o10 #b10)").tokenize()).containsExactly(new Token.LeftParen(),
				new Token.SymbolToken("+"), new Token.NumberToken(16), new Token.NumberToken(8),
				new Token.NumberToken(2), new Token.RightParen());
	}

	@Test
	void tokenizeRadixIntegerLiteralOverflowPromotesToBigInteger() {
		assertThat(new LispLexer("#x10000000000000000").tokenize())
			.containsExactly(new Token.BigIntegerToken(new java.math.BigInteger("10000000000000000", 16)));
	}

	@Test
	void tokenizeRadixIntegerLiteralWithInvalidDigitThrows() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LispLexer("#xzz").tokenize())
			.isInstanceOf(LispReadException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LispLexer("#b12").tokenize())
			.isInstanceOf(LispReadException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LispLexer("#x").tokenize())
			.isInstanceOf(LispReadException.class);
	}

}
