package am.ik.rontolisp.reader;

public sealed interface Token {

	record LeftParen() implements Token {
	}

	record RightParen() implements Token {
	}

	record Quote() implements Token {
	}

	record NumberToken(long value) implements Token {
	}

	record SymbolToken(String name) implements Token {
	}

	record StringToken(String value) implements Token {
	}

	record Dot() implements Token {
	}

	record Eof() implements Token {
	}

}
