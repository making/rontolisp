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
	void tokenizeFraction() {
		List<Token> tokens = new LispLexer("1/3").tokenize();
		assertThat(tokens)
			.containsExactly(new Token.FractionToken(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(3)));
	}

	@Test
	void tokenizeNegativeFraction() {
		List<Token> tokens = new LispLexer("-2/5").tokenize();
		assertThat(tokens).containsExactly(
				new Token.FractionToken(java.math.BigInteger.valueOf(-2), java.math.BigInteger.valueOf(5)));
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
		// A comma not followed by a digit is not a grouping separator; like "1+",
		// the trailing symbol char makes the whole token a symbol.
		List<Token> tokens = new LispLexer("1,").tokenize();
		assertThat(tokens).containsExactly(new Token.SymbolToken("1,"));
	}

}
