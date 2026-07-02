package am.ik.rontolisp.reader;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
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
			case Token.BigIntegerToken b -> new LispBigInteger(b.value());
			case Token.RatioToken r -> readRatio(r);
			case Token.DoubleToken d -> new LispDouble(d.value());
			case Token.StringToken s -> new LispString(s.value());
			case Token.CharToken ch -> new LispChar(ch.codePoint());
			case Token.SymbolToken sym -> readSymbol(sym);
			case Token.LeftParen ignored -> readList();
			case Token.VectorOpen ignored -> readVector();
			case Token.Quote ignored -> readQuote();
			case Token.FunctionQuote ignored -> readFunctionQuote();
			case Token.Backquote ignored -> readBackquote();
			case Token.Unquote ignored -> throw new LispReadException("Comma is illegal outside of backquote");
			case Token.UnquoteSplicing ignored -> throw new LispReadException(",@ is illegal outside of backquote");
			case Token.RightParen ignored -> throw new LispReadException("Unexpected ')'");
			case Token.Dot ignored -> throw new LispReadException("Unexpected '.'");
			case Token.Eof ignored -> throw new LispReadException("Unexpected end of input");
		};
	}

	private static LispVal readRatio(Token.RatioToken ratio) {
		if (ratio.denominator().signum() == 0) {
			throw new LispReadException("Division by zero in ratio literal: " + ratio.numerator() + "/0");
		}
		// Normalization may demote to an integer (e.g., "4/2" reads as 2).
		return LispRatio.valueOf(ratio.numerator(), ratio.denominator());
	}

	private LispVal readSymbol(Token.SymbolToken sym) {
		String name = sym.name();
		if ("nil".equals(name)) {
			return LispNil.INSTANCE;
		}
		if ("t".equals(name)) {
			return LispTrue.INSTANCE;
		}
		if ("pi".equals(name)) {
			// The mathematical constant pi, read as a self-evaluating double like
			// nil/t. This gives all three backends parity for free.
			return new LispDouble(Math.PI);
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

	// Reads a rank-1 vector literal #(e1 e2 ... en) into a self-evaluating LispArray.
	// The elements are read as ordinary data (not evaluated), matching Common Lisp.
	private LispVal readVector() {
		List<LispVal> elements = new ArrayList<>();
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			elements.add(readExpr());
		}
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		LispVal[] data = elements.toArray(new LispVal[0]);
		return new LispArray(new int[] { data.length }, data);
	}

	private LispVal readQuote() {
		LispVal quoted = readExpr();
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(quoted, LispNil.INSTANCE));
	}

	private LispVal readFunctionQuote() {
		LispVal quoted = readExpr();
		return new LispCons(new LispSymbol(LispNames.FUNCTION), new LispCons(quoted, LispNil.INSTANCE));
	}

	// --- Backquote (quasiquote) -------------------------------------------------
	//
	// A backquote template is expanded AT READ TIME into ordinary list/append/quote
	// forms, so the evaluator and both compilers support it with no backend work:
	// `(a ,b) -> (list (quote a) b)
	// `(a ,@bs c) -> (append (list (quote a)) bs (list (quote c)))
	// `,x -> x
	// Nested backquote is not supported (a clear read error). Because expansion
	// happens in the reader, the runtime `read` of compiled programs does not
	// understand the backquote character.

	/** One expanded template element and whether it splices into the enclosing list. */
	private record TemplateElement(LispVal form, boolean splicing) {
	}

	private LispVal readBackquote() {
		TemplateElement element = readTemplateElement();
		if (element.splicing()) {
			throw new LispReadException(",@ must appear inside a list in a backquote template");
		}
		return element.form();
	}

	private TemplateElement readTemplateElement() {
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input in backquote template");
		}
		Token token = this.tokens.get(this.pos);
		return switch (token) {
			case Token.Unquote ignored -> {
				this.pos++;
				yield new TemplateElement(readExpr(), false);
			}
			case Token.UnquoteSplicing ignored -> {
				this.pos++;
				yield new TemplateElement(readExpr(), true);
			}
			case Token.Backquote ignored -> throw new LispReadException("Nested backquote is not supported");
			case Token.LeftParen ignored -> {
				this.pos++;
				yield new TemplateElement(readTemplateList(), false);
			}
			case Token.Quote ignored -> {
				this.pos++;
				yield new TemplateElement(readWrappedTemplate(LispNames.QUOTE), false);
			}
			case Token.FunctionQuote ignored -> {
				this.pos++;
				yield new TemplateElement(readWrappedTemplate(LispNames.FUNCTION), false);
			}
			// Any other token is constant template data: read it normally and quote
			// symbols so they stay data (numbers, strings, chars, t/nil, vectors are
			// self-evaluating and stay as-is).
			default -> new TemplateElement(quoteIfSymbol(readExpr()), false);
		};
	}

	// 'x inside a template is the two-element template (quote x); #'x is (function x).
	private LispVal readWrappedTemplate(String operator) {
		TemplateElement inner = readTemplateElement();
		if (inner.splicing()) {
			throw new LispReadException(",@ cannot follow ' or #' in a backquote template");
		}
		LispVal quoteSym = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(operator), LispNil.INSTANCE));
		return new LispCons(new LispSymbol(LispNames.LIST),
				new LispCons(quoteSym, new LispCons(inner.form(), LispNil.INSTANCE)));
	}

	private LispVal readTemplateList() {
		List<TemplateElement> elements = new ArrayList<>();
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			elements.add(readTemplateElement());
		}
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		return buildTemplateList(elements);
	}

	private static LispVal buildTemplateList(List<TemplateElement> elements) {
		if (elements.isEmpty()) {
			return LispNil.INSTANCE;
		}
		boolean anySplicing = elements.stream().anyMatch(TemplateElement::splicing);
		if (!anySplicing) {
			// (list f1 ... fn)
			return properList(new LispSymbol(LispNames.LIST), elements.stream().map(TemplateElement::form).toList());
		}
		// (append seg1 ... segk): each splicing element is its own segment, runs of
		// non-splicing elements collapse into (list f...) segments.
		List<LispVal> segments = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		for (TemplateElement element : elements) {
			if (element.splicing()) {
				if (!run.isEmpty()) {
					segments.add(properList(new LispSymbol(LispNames.LIST), run));
					run = new ArrayList<>();
				}
				segments.add(element.form());
			}
			else {
				run.add(element.form());
			}
		}
		if (!run.isEmpty()) {
			segments.add(properList(new LispSymbol(LispNames.LIST), run));
		}
		return properList(new LispSymbol(LispNames.APPEND), segments);
	}

	private static LispVal properList(LispVal head, List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return new LispCons(head, result);
	}

	private static LispVal quoteIfSymbol(LispVal value) {
		if (value instanceof LispSymbol) {
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
		}
		return value;
	}

}
