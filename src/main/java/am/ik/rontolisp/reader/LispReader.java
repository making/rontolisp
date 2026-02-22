package am.ik.rontolisp.reader;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * Parser for Lisp expressions. Converts a list of tokens into LispVal AST nodes.
 */
public final class LispReader {

	private final List<Token> tokens;

	private int pos;

	private LispReader(List<Token> tokens) {
		this.tokens = tokens;
		this.pos = 0;
	}

	/**
	 * Read a single expression from the input string.
	 * @param input the source code string
	 * @return the parsed expression
	 */
	public static LispVal readFromString(String input) {
		List<LispVal> exprs = readAllFromString(input);
		if (exprs.isEmpty()) {
			return LispNil.INSTANCE;
		}
		return exprs.get(0);
	}

	/**
	 * Read all expressions from the input string.
	 * @param input the source code string
	 * @return the list of parsed expressions
	 */
	public static List<LispVal> readAllFromString(String input) {
		List<Token> tokens = new LispLexer(input).tokenize();
		LispReader reader = new LispReader(tokens);
		List<LispVal> result = new ArrayList<>();
		while (reader.pos < reader.tokens.size()) {
			result.add(reader.readExpr());
		}
		return result;
	}

	private LispVal readExpr() {
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input");
		}
		Token token = this.tokens.get(this.pos);
		this.pos++;
		return switch (token) {
			case Token.NumberToken n -> new LispInteger(n.value());
			case Token.DoubleToken d -> new LispDouble(d.value());
			case Token.StringToken s -> new LispString(s.value());
			case Token.SymbolToken sym -> readSymbol(sym);
			case Token.LeftParen ignored -> readList();
			case Token.Quote ignored -> readQuote();
			case Token.RightParen ignored -> throw new LispReadException("Unexpected ')'");
			case Token.Dot ignored -> throw new LispReadException("Unexpected '.'");
			case Token.Eof ignored -> throw new LispReadException("Unexpected end of input");
		};
	}

	private LispVal readSymbol(Token.SymbolToken sym) {
		String name = sym.name();
		if ("nil".equals(name)) {
			return LispNil.INSTANCE;
		}
		if ("t".equals(name)) {
			return LispTrue.INSTANCE;
		}
		return new LispSymbol(name);
	}

	private LispVal readList() {
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input, expected ')'");
		}
		if (this.tokens.get(this.pos) instanceof Token.RightParen) {
			this.pos++; // consume ')'
			return LispNil.INSTANCE;
		}
		List<LispVal> elements = new ArrayList<>();
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			elements.add(readExpr());
		}
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		// Build cons chain from right to left
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

	private LispVal readQuote() {
		LispVal quoted = readExpr();
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(quoted, LispNil.INSTANCE));
	}

}
