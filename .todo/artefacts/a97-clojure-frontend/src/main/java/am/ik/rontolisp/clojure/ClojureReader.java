package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL Clojure front end's reader: source text to DATUMS, case-sensitively. A
 * datum is an ordinary {@link LispVal} the lowering walks: an identifier a
 * {@link LispSymbol} holding its spelling verbatim, a keyword a symbol whose name starts
 * with {@code :}, the empty list {@link LispNil}, a vector the marked list
 * {@code (%vector ...)} and a map the marked list {@code (%hash-map ...)}: markers the
 * lowering consumes and no identifier can spell (user identifiers are mangled behind
 * {@code ClojureLowering.PREFIX}). {@code nil} / {@code true} / {@code false} stay
 * symbols; the lowering decides what they mean. A {@code #(...)} anonymous function reads
 * as {@code (fn %anon ...)}: {@link #FN_ANON} stands for the parameter vector.
 */
final class ClojureReader {

	/** Stands for the parameter vector of a {@code #(...)} anonymous function literal. */
	static final LispSymbol FN_ANON = new LispSymbol("%anon");

	static final LispSymbol VECTOR = new LispSymbol("%vector");

	private static final LispSymbol HASH_MAP = new LispSymbol("%hash-map");

	private static final String DELIMS = " \t\n\r\f,()[]{}\";'@^`~#";

	private final String source;

	private final @Nullable String file;

	private int pos;

	private int line = 1;

	private int column = 1;

	ClojureReader(String source, @Nullable String file) {
		this.source = source;
		this.file = file;
	}

	List<LispVal> readAll() {
		List<LispVal> forms = new ArrayList<>();
		skipSpace();
		while (this.pos < this.source.length()) {
			forms.add(readDatum());
			skipSpace();
		}
		return forms;
	}

	private LispVal readDatum() {
		if (this.pos >= this.source.length()) {
			throw error("unexpected end of input");
		}
		char c = peek();
		return switch (c) {
			case '(' -> readList(')');
			case ')' -> throw error("unexpected ')'");
			case '[' -> readVector();
			case ']' -> throw error("unexpected ']'");
			case '{' -> readBraced();
			case '}' -> throw error("unexpected '}'");
			case '"' -> readString();
			case '\'' -> {
				next();
				yield quoted("quote");
			}
			case '`' -> {
				next();
				yield quoted("syntax-quote");
			}
			case '~' -> {
				next();
				char after = peek();
				if (after == '@') {
					next();
					yield quoted("unquote-splicing");
				}
				yield quoted("unquote");
			}
			case '@' -> {
				next();
				yield list("deref", readDatum());
			}
			case '^' -> {
				next();
				LispVal meta = readDatum();
				yield list("with-meta", readDatum(), meta);
			}
			case '#' -> readDispatch();
			default -> readAtom();
		};
	}

	private LispVal readDispatch() {
		next();
		if (peek() == '\'') { // var
			next();
			return list("var", readDatum());
		}
		if (peek() == '_') { // skip next form
			next();
			readDatum();
			skipSpace();
			return readDatum();
		}
		if (peek() == '(') {
			return readAnonFn();
		}
		if (peek() == '{') { // set: read as a marked list; the lowering refuses it
			next();
			return marked(HASH_MAP, readSeq('}'));
		}
		throw error("unsupported reader form #");
	}

	private LispVal readAnonFn() {
		next();
		List<LispVal> body = readSeq(')');
		List<LispVal> fn = new ArrayList<>();
		fn.add(new LispSymbol("fn"));
		fn.add(FN_ANON);
		fn.addAll(body);
		return list(fn);
	}

	private LispVal readBraced() {
		next();
		List<LispVal> items = readSeq('}');
		if (items.size() % 2 != 0) {
			throw error("a map literal needs an even number of forms");
		}
		return marked(HASH_MAP, items);
	}

	private List<LispVal> readSeq(char close) {
		List<LispVal> items = new ArrayList<>();
		skipSpace();
		while (true) {
			if (peekClose(close)) {
				next();
				return items;
			}
			items.add(readDatum());
			skipSpace();
		}
	}

	private boolean peekClose(char close) {
		if (this.pos >= this.source.length()) {
			throw error("unclosed form, expected '" + close + "'");
		}
		return peek() == close;
	}

	private LispVal readList(char close) {
		next();
		return list(readSeq(close));
	}

	private LispVal readVector() {
		next();
		return marked(VECTOR, readSeq(']'));
	}

	private LispVal quoted(String name) {
		return list(name, readDatum());
	}

	private LispVal readString() {
		next();
		StringBuilder text = new StringBuilder();
		while (true) {
			if (this.pos >= this.source.length()) {
				throw error("unterminated string");
			}
			char c = next();
			if (c == '"') {
				return LispString.literal(text.toString());
			}
			if (c != '\\') {
				text.append(c);
				continue;
			}
			if (this.pos >= this.source.length()) {
				throw error("unterminated escape");
			}
			text.append(switch (next()) {
				case 'n' -> '\n';
				case 't' -> '\t';
				case 'r' -> '\r';
				case 'f' -> '\f';
				case 'b' -> '\b';
				case '\\' -> '\\';
				case '"' -> '"';
				case '\'' -> '\'';
				case 'u' -> {
					if (this.pos + 4 > this.source.length()) {
						throw error("truncated \\u escape");
					}
					int cp = Integer.parseInt(this.source.substring(this.pos, this.pos + 4), 16);
					for (int i = 0; i < 4; i++) {
						next();
					}
					yield Character.toChars(cp);
				}
				default -> Character.toString(peek());
			});
		}
	}

	private LispVal readAtom() {
		int start = this.pos;
		while (this.pos < this.source.length() && DELIMS.indexOf(peek()) < 0) {
			next();
		}
		String token = this.source.substring(start, this.pos);
		if (token.isEmpty()) {
			throw error("expected a form");
		}
		if (token.equals("nil") || token.equals("true") || token.equals("false")) {
			return new LispSymbol(token);
		}
		LispVal n = tryNumber(token);
		if (n != null) {
			return n;
		}
		return new LispSymbol(token);
	}

	/** The number the token spells, or null when it is an identifier. */
	private @Nullable LispVal tryNumber(String token) {
		// a leading sign must be followed by a digit for it to be a number
		String body = token;
		if (body.startsWith("+") || body.startsWith("-")) {
			if (body.length() == 1 || !Character.isDigit(body.charAt(1)) && body.charAt(1) != '.') {
				return null;
			}
		}
		int slash = body.indexOf('/');
		if (slash > 0 && noRadixMark(body)) {
			try {
				return LispRatio.valueOf(new java.math.BigInteger(body.substring(0, slash)),
						new java.math.BigInteger(body.substring(slash + 1)));
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		if (body.indexOf('.') >= 0 || body.indexOf('e') >= 0 || body.indexOf('E') >= 0 || body.indexOf('M') >= 0) {
			try {
				return new LispDouble(Double.parseDouble(body.substring(0, lenWithoutSuffix(body))));
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		try {
			long v = Long.parseLong(stripSuffix(body));
			return new LispInteger(v);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static boolean noRadixMark(String body) {
		return body.indexOf('r') < 0 && body.indexOf('R') < 0 && body.indexOf('x') < 0 && body.indexOf('X') < 0
				&& body.indexOf('.') < 0;
	}

	private static int lenWithoutSuffix(String body) {
		return body.endsWith("M") || body.endsWith("N") ? body.length() - 1 : body.length();
	}

	private static String stripSuffix(String body) {
		return body.endsWith("N") ? body.substring(0, body.length() - 1) : body;
	}

	private LispVal list(String head, LispVal... args) {
		List<LispVal> items = new ArrayList<>();
		items.add(new LispSymbol(head));
		items.addAll(List.of(args));
		return list(items);
	}

	private static LispVal marked(LispSymbol marker, List<LispVal> items) {
		List<LispVal> call = new ArrayList<>();
		call.add(marker);
		call.addAll(items);
		return list(call);
	}

	private static LispVal list(List<LispVal> items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			tail = new LispCons(items.get(i), tail);
		}
		return tail;
	}

	private void skipSpace() {
		while (this.pos < this.source.length()) {
			char c = peek();
			if (c == ';' || (c == '#' && this.pos == 0 && this.source.startsWith("#!", this.pos))) {
				while (this.pos < this.source.length() && peek() != '\n') {
					next();
				}
				continue;
			}
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == ',') {
				next();
				continue;
			}
			return;
		}
	}

	private char peek() {
		return this.source.charAt(this.pos);
	}

	private char next() {
		char c = this.source.charAt(this.pos++);
		if (c == '\n') {
			this.line++;
			this.column = 1;
		}
		else {
			this.column++;
		}
		return c;
	}

	private LispReadException error(String message) {
		return new LispReadException(
				this.file == null ? message : this.file + ":" + this.line + ":" + this.column + ": " + message);
	}

}
