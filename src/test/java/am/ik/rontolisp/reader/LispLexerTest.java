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
	void tokenizeMixedIntAndDouble() {
		List<Token> tokens = new LispLexer("(+ 1 2.5)").tokenize();
		assertThat(tokens).containsExactly(new Token.LeftParen(), new Token.SymbolToken("+"), new Token.NumberToken(1),
				new Token.DoubleToken(2.5), new Token.RightParen());
	}

}
