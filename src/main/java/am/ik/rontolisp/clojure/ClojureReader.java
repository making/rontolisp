package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL Clojure front end's reader: source text to DATUMS, case-sensitively. A
 * datum is an ordinary {@link LispVal} the lowering walks: an identifier a
 * {@link LispSymbol} holding its spelling verbatim, a keyword a symbol whose name starts
 * with {@code :}, the empty list {@link LispNil}, a vector the marked list
 * {@code (%vector ...)}, a map the marked list {@code (%hash-map ...)} and a set the
 * marked list {@code (%hash-set ...)}: markers the lowering consumes and no identifier
 * can spell (user identifiers are mangled behind {@code ClojureLowering.PREFIX}).
 * {@code nil} / {@code true} / {@code false} stay symbols; the lowering decides what they
 * mean. A {@code #(...)} anonymous function reads as {@code (fn %anon ...)}:
 * {@link #FN_ANON} stands for the parameter vector.
 */
final class ClojureReader {

	/** Stands for the parameter vector of a {@code #(...)} anonymous function literal. */
	static final LispSymbol FN_ANON = new LispSymbol("%anon");

	static final LispSymbol VECTOR = new LispSymbol("%vector");

	private static final LispSymbol HASH_MAP = new LispSymbol("%hash-map");

	private static final LispSymbol HASH_SET = new LispSymbol("%hash-set");

	/** Marks a regex literal's source string: the lowering compiles it to a pattern. */
	static final LispSymbol REGEX = new LispSymbol("%regex");

	/**
	 * Marks a record literal {@code (%record ns.Name body)}: the lowering builds the
	 * record over the quoted body.
	 */
	static final LispSymbol RECORD = new LispSymbol("%record");

	private static final String DELIMS = " \t\n\r\f,()[]{}\";'@^`~#";

	/**
	 * What {@link #readDatum} answers for a {@code #_} discard (the discarded datum
	 * already read): every collection and {@link #readAll} skips it, so a discard before
	 * a closing bracket or the end of input drops like the oracle's, and
	 * {@link #readRequired} reads on past it.
	 */
	private static final LispSymbol DISCARD = new LispSymbol("%discard");

	private final String source;

	private final @Nullable String file;

	private int pos;

	private int line = 1;

	private int column = 1;

	/**
	 * Where each datum starts, by identity: the offset of its first character, so the
	 * lowering can position its errors at the offending form. The innermost read wins (a
	 * {@code #_}-discarded prefix keeps the surviving datum's own start).
	 */
	private final Map<LispVal, Integer> offsets = new IdentityHashMap<>();

	ClojureReader(String source, @Nullable String file) {
		this.source = source;
		this.file = file;
	}

	/**
	 * The file the text came from.
	 * @return the path, or {@code null} when unknown
	 */
	@Nullable String file() {
		return this.file;
	}

	/**
	 * Where the datum starts, or null when it did not come out of this read.
	 * @param datum the datum
	 * @return the position
	 */
	@Nullable SourceLocation locate(LispVal datum) {
		Integer offset = this.offsets.get(datum);
		if (offset == null) {
			return null;
		}
		return SourceLocation.at(this.file, offset, this.source);
	}

	List<LispVal> readAll() {
		List<LispVal> forms = new ArrayList<>();
		skipSpace();
		this.endsInDiscard = false;
		while (this.pos < this.source.length()) {
			LispVal datum = readDatum();
			this.endsInDiscard = datum == DISCARD;
			if (datum != DISCARD) {
				forms.add(datum);
			}
			skipSpace();
		}
		return forms;
	}

	/**
	 * Whether the last {@link #readAll} ended in a {@code #_} discard: a session buffer
	 * like that waits for the datum after it, like the oracle's REPL reading on.
	 * @return whether the text ends in a discard
	 */
	boolean endsInDiscard() {
		return this.endsInDiscard;
	}

	private boolean endsInDiscard;

	/**
	 * The next datum past whitespace and {@code #_} discards: what a quote, a deref, a
	 * var, metadata and a discard itself read. The end of input is the
	 * {@code unexpected end of input} refusal.
	 */
	private LispVal readRequired() {
		LispVal datum;
		do {
			skipSpace();
			datum = readDatum();
		}
		while (datum == DISCARD);
		return datum;
	}

	/**
	 * Whether a `#` right after an atom ends its token: before the end of input,
	 * whitespace or a delimiter -- never before a dispatch continuation (`'`, `_`, `(`,
	 * `{`, `"`), which still dispatches.
	 */
	private boolean hashEndsToken() {
		if (this.pos + 1 >= this.source.length()) {
			return true;
		}
		char after = this.source.charAt(this.pos + 1);
		return " \t\n\r\f,()[]{}\";".indexOf(after) >= 0;
	}

	private LispVal readDatum() {
		if (this.pos >= this.source.length()) {
			throw error("unexpected end of input");
		}
		int start = this.pos;
		char c = peek();
		LispVal datum = switch (c) {
			case '(' -> readList(')');
			case ')' -> throw error("unexpected ')'");
			case '[' -> readVector();
			case ']' -> throw error("unexpected ']'");
			case '{' -> readBraced();
			case '}' -> throw error("unexpected '}'");
			case '"' -> readString();
			case '\'' -> {
				next();
				skipSpace();
				yield quoted("quote");
			}
			case '`' -> {
				next();
				skipSpace();
				yield quoted("syntax-quote");
			}
			case '~' -> {
				next();
				if (this.pos < this.source.length() && peek() == '@') {
					next();
					skipSpace();
					yield quoted("unquote-splicing");
				}
				skipSpace();
				yield quoted("unquote");
			}
			case '@' -> {
				next();
				yield list("deref", readRequired());
			}
			case '^' -> readMeta();
			case '#' -> readDispatch();
			case '\\' -> readCharLiteral();
			default -> readAtom();
		};
		if (datum != DISCARD) {
			this.offsets.putIfAbsent(datum, start);
		}
		return datum;
	}

	private LispVal readDispatch() {
		next();
		if (this.pos >= this.source.length()) {
			throw error("unexpected end of input");
		}
		if (peek() == '\'') { // var
			next();
			return list("var", readRequired());
		}
		if (peek() == '_') { // skip the next form
			next();
			readRequired();
			return DISCARD;
		}
		if (peek() == '(') {
			return readAnonFn();
		}
		if (peek() == '{') { // set: its own marked list, so the lowering names it
			next();
			return readSet();
		}
		if (peek() == '"') { // a regex literal: its source travels to the lowering
			LispVal source = readRegexSource();
			List<LispVal> regex = new ArrayList<>();
			regex.add(REGEX);
			regex.add(source);
			return list(regex);
		}
		if (peek() == '^') { // the legacy spelling of ^ metadata
			return readMeta();
		}
		if (Character.isLetter(peek())) {
			return readRecordLiteral();
		}
		throw error("unsupported reader form #" + peek());
	}

	/**
	 * One {@code ^meta form} (or legacy {@code #^meta form}), positioned at the caret:
	 * {@code (%with-meta form meta)} -- a head no Clojure call spells, so the lowering
	 * tells reader metadata (dropped, except on a collection literal) from a
	 * {@code with-meta} call (which attaches).
	 */
	private LispVal readMeta() {
		next();
		LispVal meta = readRequired();
		return list(ClojureLowerUtil.READER_META, readRequired(), meta);
	}

	/**
	 * One record literal {@code #ns.Name{:k v ...}} / {@code #ns.Name[v ...]}, positioned
	 * after the hash: {@code (%record ns.Name body)}, the body read as data (the oracle
	 * never evaluates it). Like the oracle, only a dotted class name is a record literal
	 * (an undotted tag is a tagged literal, and no reader function is installed for one);
	 * a body that is neither a map nor a vector is unreadable, and a map body takes
	 * distinct keyword keys only.
	 */
	private LispVal readRecordLiteral() {
		int start = this.pos;
		while (this.pos < this.source.length() && DELIMS.indexOf(peek()) < 0) {
			next();
		}
		String tag = this.source.substring(start, this.pos);
		if (tag.indexOf('.') < 0) {
			if (tag.equals("inst") || tag.equals("uuid")) {
				throw error("unsupported reader form #" + tag);
			}
			throw error("No reader function for tag " + tag);
		}
		skipSpace();
		LispVal body;
		if (this.pos < this.source.length() && peek() == '[') {
			body = readVector();
		}
		else if (this.pos < this.source.length() && peek() == '{') {
			next();
			List<LispVal> items = readSeq('}');
			if (items.size() % 2 != 0) {
				throw error("a map literal needs an even number of forms");
			}
			Set<String> seen = new HashSet<>();
			for (int i = 0; i < items.size(); i += 2) {
				if (!(items.get(i) instanceof LispSymbol key && key.name().startsWith(":"))) {
					throw error("Unreadable defrecord form: key must be of type clojure.lang.Keyword, got "
							+ items.get(i).print());
				}
				if (!seen.add(key.name())) {
					throw error("Duplicate key: " + key.name());
				}
			}
			body = marked(HASH_MAP, items);
		}
		else {
			throw error("Unreadable constructor form starting with \"#" + tag + "\"");
		}
		return list(List.of(RECORD, new LispSymbol(tag), body));
	}

	/**
	 * One character literal: a single character, a lowercase name ({@code newline},
	 * {@code space}, {@code tab}, {@code return}, {@code backspace}, {@code formfeed}), a
	 * {@code u} plus four hex digits or an {@code o} plus one to three octal digits --
	 * exactly the oracle's (Clojure CLI 1.12) spellings, case-sensitively. Like the
	 * oracle's, the character behind the backslash belongs to the literal whatever it is
	 * (so {@code \(}, what {@code pr} spells for the parenthesis, reads back) and a
	 * backslash ends it ({@code [\a\b]} is two characters). Anything else is the oracle's
	 * {@code Unsupported character} refusal.
	 */
	private LispVal readCharLiteral() {
		next(); // the backslash
		int start = this.pos;
		if (this.pos < this.source.length()) {
			next(); // the first character, whatever it is
		}
		while (this.pos < this.source.length() && DELIMS.indexOf(peek()) < 0 && peek() != '\\') {
			next();
		}
		String token = this.source.substring(start, this.pos);
		if (token.length() == 1) {
			return new LispChar(token.charAt(0));
		}
		if (token.length() == 5 && token.charAt(0) == 'u') {
			try {
				int cp = Integer.parseInt(token.substring(1), 16);
				if (Character.isValidCodePoint(cp) && (cp < 0xD800 || cp > 0xDFFF)) {
					return new LispChar(cp);
				}
			}
			catch (NumberFormatException ex) {
				// the refusal below
			}
			throw error("Unsupported character: \\" + token);
		}
		if (token.length() > 1 && token.charAt(0) == 'o' && token.length() <= 4) {
			try {
				int cp = Integer.parseInt(token.substring(1), 8);
				return new LispChar(cp);
			}
			catch (NumberFormatException ex) {
				throw error("Unsupported character: \\" + token);
			}
		}
		int named = switch (token) {
			case "newline" -> '\n';
			case "space" -> ' ';
			case "tab" -> '\t';
			case "return" -> '\r';
			case "backspace" -> '\b';
			case "formfeed" -> '\f';
			default -> -1;
		};
		if (named >= 0) {
			return new LispChar(named);
		}
		throw error("Unsupported character: \\" + token);
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

	/**
	 * One set literal: the items as read, refusing a repeated element by its spelling.
	 * The spelling check is what makes the common duplicate ({@code #{1 1}}) fail like
	 * the oracle's {@code Duplicate key}; two differently-spelled elements that happen to
	 * be equal at run time still dedupe silently.
	 */
	private LispVal readSet() {
		List<LispVal> items = readSeq('}');
		Set<String> seen = new HashSet<>();
		for (LispVal item : items) {
			String spelling = item.print();
			if (!seen.add(spelling)) {
				throw error("Duplicate key: " + spelling);
			}
		}
		return marked(HASH_SET, items);
	}

	private List<LispVal> readSeq(char close) {
		List<LispVal> items = new ArrayList<>();
		skipSpace();
		while (true) {
			if (peekClose(close)) {
				next();
				return items;
			}
			LispVal datum = readDatum();
			if (datum != DISCARD) {
				items.add(datum);
			}
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
		return list(name, readRequired());
	}

	/**
	 * One regex literal's source: every escape validates like the oracle's but stays
	 * verbatim for the pattern parser (which names what it cannot lower), measured on
	 * {@code clj} 1.12.6.1673 (2026-10-02). {@code \\} stays two characters (so
	 * {@code #"\\d"} reads a literal backslash plus {@code d}, where the old read halved
	 * the run and answered the digit class); {@code \"} and {@code \'} stay two
	 * characters without ending the literal; the recognized escape letters stay as
	 * written while any other letter is the oracle's read-time refusal; {@code \\u} takes
	 * four hex digits and {@code \x} two; a {@code \0} needs an octal digit behind it;
	 * {@code \Q} copies raw through {@code \E} (a lone {@code \E} is refused).
	 */
	private LispVal readRegexSource() {
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
			char e = next();
			switch (e) {
				case 'n', 't', 'r', 'f', 'b', 'a', 'e', 'h', 'v', 'w', 'd', 's', 'c', 'p', 'z', 'A', 'B', 'D', 'G', 'H',
						'P', 'R', 'S', 'V', 'W', 'X', 'Z' -> {
					text.append('\\');
					text.append(e);
				}
				case '\\', '"', '\'', '/' -> {
					text.append('\\');
					text.append(e);
				}
				case 'Q' -> {
					text.append('\\');
					text.append('Q');
					readRegexQuoted(text);
				}
				case 'E' -> throw error("Illegal/unsupported escape sequence: \\E");
				case 'u' -> {
					text.append('\\');
					text.append('u');
					text.append(readRegexHex(4, "Unicode"));
				}
				case 'x' -> {
					text.append('\\');
					text.append('x');
					text.append(readRegexHex(2, "hexadecimal"));
				}
				// Octal `0`-`7` stays verbatim (up to two more `0`-`7`): the
				// oracle refuses a `\0` with no octal digit behind it, while
				// `8`/`9` stay verbatim here (the pattern parser reads the
				// backreference, like the oracle) where the string reader
				// refuses them instead.
				case '0', '1', '2', '3', '4', '5', '6', '7' -> {
					if (e == '0' && (this.pos >= this.source.length() || !isOctalDigit(peek()))) {
						throw error("Illegal octal escape sequence: \\0");
					}
					text.append('\\');
					text.append(e);
					int count = 1;
					while (count < 3 && this.pos < this.source.length() && isOctalDigit(peek())) {
						text.append(next());
						count++;
					}
				}
				case '8', '9' -> {
					text.append('\\');
					text.append(e);
				}
				default -> {
					if (Character.isLetter(e)) {
						throw error("Illegal/unsupported escape sequence: \\" + e);
					}
					text.append('\\');
					text.append(e);
				}
			}
		}
	}

	/**
	 * A {@code \Q..\E} span's raw body onto {@code text}: every character copies verbatim
	 * (even the closing quote, which does not end the literal here), through the closing
	 * {@code \E} when one comes, like the oracle.
	 * @param text the source being built
	 */
	private void readRegexQuoted(StringBuilder text) {
		while (true) {
			if (this.pos >= this.source.length()) {
				throw error("unterminated string");
			}
			char c = next();
			if (c == '\\' && this.pos < this.source.length() && peek() == 'E') {
				text.append(c);
				text.append(next());
				return;
			}
			text.append(c);
		}
	}

	/**
	 * {@code count} hex digits of a regex {@code \\u}/{@code \x} escape, kept verbatim:
	 * anything else is the oracle's refusal.
	 * @param count the digits the escape takes (four for {@code \\u}, two for {@code \x})
	 * @param kind the escape's name for the refusal
	 * @return the digits as written
	 */
	private String readRegexHex(int count, String kind) {
		StringBuilder raw = new StringBuilder();
		for (int i = 0; i < count; i++) {
			if (this.pos >= this.source.length() || Character.digit(peek(), 16) < 0) {
				throw error("Illegal " + kind + " escape sequence");
			}
			raw.append(next());
		}
		return raw.toString();
	}

	private static boolean isOctalDigit(char c) {
		return c >= '0' && c <= '7';
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
			char e = next();
			switch (e) {
				case 'n' -> text.append('\n');
				case 't' -> text.append('\t');
				case 'r' -> text.append('\r');
				case 'f' -> text.append('\f');
				case 'b' -> text.append('\b');
				case '\\' -> text.append('\\');
				case '"' -> text.append('"');
				case 'u' -> text.append(Character.toChars(readUnicodeEscape()));
				// Octal `\0`-`\7` (up to two more `0`-`7`), like the oracle: a
				// following `8`/`9` -- or any other non-octal char the reader would
				// not stop at -- is the oracle's `Invalid digit` refusal, and a
				// value past `\377` the oracle's range refusal. `\'` is refused
				// below with the other unknown escapes (b44: the oracle signals
				// `Unsupported escape character: \'`, so the old lenient read as
				// `'` goes).
				case '0', '1', '2', '3', '4', '5', '6', '7' -> {
					int value = e - '0';
					int count = 1;
					while (count < 3 && this.pos < this.source.length() && !escapeStops(peek())) {
						int digit = Character.digit(peek(), 8);
						if (digit < 0) {
							throw error("Invalid digit: " + peek());
						}
						next();
						value = value * 8 + digit;
						count++;
					}
					if (value > 0377) {
						throw error("Octal escape sequence must be in range [0, 377].");
					}
					text.append((char) value);
				}
				case '8', '9' -> throw error("Invalid digit: " + e);
				// like the oracle: an unknown escape signals instead of reading
				// on.
				default -> throw error("Unsupported escape character: \\" + e);

			}
		}
	}

	/**
	 * One backslash-u escape's character, like the oracle ({@code clj} 1.12.6.1673,
	 * measured 2026-10-01): its string reader checks the first digit itself
	 * ({@code Invalid unicode escape}, naming even the closing quote), then runs the
	 * shared unicode reader over exactly four hex digits. A non-hex later digit is
	 * {@code Invalid digit}; fewer than four digits before a stop -- whitespace (the
	 * comma counts as one there), a macro character, or the closing quote -- is
	 * {@code Invalid character length}. A buffer that ends mid-escape stays the
	 * {@code truncated} refusal (a deliberate deviation: the oracle reports the
	 * escape/length refusal even at end of input, but here the end of input may be a REPL
	 * buffer boundary, so {@code ClojureSession.isComplete} keeps waiting for the rest
	 * instead of reporting a complete-but-wrong form).
	 */

	private int readUnicodeEscape() {
		if (this.pos >= this.source.length()) {
			throw error("truncated \\u escape");
		}
		char first = peek();
		if (Character.digit(first, 16) < 0) {
			throw error("Invalid unicode escape: \\u" + first);
		}
		int value = Character.digit(next(), 16);
		int count = 1;
		while (count < 4 && this.pos < this.source.length() && !escapeStops(peek())) {
			int digit = Character.digit(peek(), 16);
			if (digit < 0) {
				throw error("Invalid digit: " + peek());
			}
			next();
			value = value * 16 + digit;
			count++;
		}
		if (count != 4) {
			if (this.pos >= this.source.length()) {
				throw error("truncated \\u escape");
			}
			throw error("Invalid character length: " + count + ", should be: 4");
		}
		return value;

	}

	/**
	 * Whether the oracle's string reader would stop a backslash-u or octal escape before
	 * this character: its shared unicode reader stops at the end of input, at whitespace
	 * (the comma counts as one there) or at a macro character, leaving the character for
	 * the string body. Anything else must be a digit of the escape's base (measured on
	 * {@code clj} 1.12.6.1673 for both arms: a colon refuses with {@code Invalid digit}
	 * while a comma, a semicolon, an open paren and a hash stop the escape there).
	 */
	private static boolean escapeStops(char c) {
		return Character.isWhitespace(c) || c == ',' || "\";'@^`~()[]{}\\%#".indexOf(c) >= 0;
	}

	private LispVal readAtom() {
		int start = this.pos;
		while (this.pos < this.source.length() && DELIMS.indexOf(peek()) < 0) {
			next();
		}
		// a gensym suffix: `x#` reads as one identifier when the `#` ends the
		// token (before whitespace, a delimiter or the end of input); a dispatch
		// form (`#'`, `#(`, `#"`, ...) still dispatches
		while (this.pos < this.source.length() && peek() == '#' && hashEndsToken()) {
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
		if (!numberShaped(token)) {
			return null;
		}
		try {
			return parseNumber(token);
		}
		catch (NumberFormatException | ArithmeticException ex) {
			throw error("Invalid number: " + token);
		}
	}

	/**
	 * Whether the token is number-shaped: a leading digit, a sign followed by a digit or
	 * a dot, or a dot followed by a digit. Anything else is an identifier, so
	 * {@code s/join} never reaches the number parser; a shaped token that parses to
	 * nothing ( {@code 09}, {@code 1e}, {@code 2r}) is the oracle's
	 * {@code Invalid number} refusal.
	 */
	private static boolean numberShaped(String token) {
		if (token.isEmpty()) {
			return false;
		}
		char first = token.charAt(0);
		if (Character.isDigit(first)) {
			return true;
		}
		if ((first == '+' || first == '-') && token.length() > 1) {
			char second = token.charAt(1);
			return Character.isDigit(second) || second == '.';
		}
		return first == '.' && token.length() > 1 && Character.isDigit(token.charAt(1));
	}

	/**
	 * The number the shaped token spells: a ratio, a radix integer ({@code 0x},
	 * {@code Nr}, a leading {@code 0} for octal), an exact ratio for the {@code M}
	 * suffix, a double, or a long (a {@link LispBigInteger} past the {@code long} range,
	 * with or without the {@code N} suffix). The {@code M} suffix lowers to an exact
	 * ratio -- {@code 0.1M} is {@code 1/10}, so decimal arithmetic stays exact instead of
	 * the double's precision loss; it prints as the ratio, not {@code 0.1M}.
	 */
	private static LispVal parseNumber(String token) {
		String unsigned = token.startsWith("+") || token.startsWith("-") ? token.substring(1) : token;
		int slash = token.indexOf('/');
		if (slash > 0 && isDecimalRatio(token)) {
			return LispRatio.valueOf(new java.math.BigInteger(token.substring(0, slash)),
					new java.math.BigInteger(token.substring(slash + 1)));
		}
		if (unsigned.startsWith("0x") || unsigned.startsWith("0X")) {
			return signedOf(new java.math.BigInteger(stripBigSuffix(unsigned.substring(2)), 16), token);
		}
		int mark = Math.max(unsigned.indexOf('r'), unsigned.indexOf('R'));
		if (mark > 0) {
			int radix = Integer.parseInt(unsigned.substring(0, mark));
			if (radix < 2 || radix > 36) {
				throw new NumberFormatException(token);
			}
			return signedOf(new java.math.BigInteger(stripBigSuffix(unsigned.substring(mark + 1)), radix), token);
		}
		if (unsigned.length() > 1 && unsigned.charAt(0) == '0' && isDigits(unsigned)) {
			if (!isOctalDigits(unsigned)) {
				throw new NumberFormatException(token);
			}
			return signedOf(new java.math.BigInteger(unsigned, 8), token);
		}
		if (token.endsWith("M")) {
			java.math.BigDecimal decimal = new java.math.BigDecimal(token.substring(0, token.length() - 1));
			java.math.BigInteger numerator = decimal.unscaledValue();
			int scale = decimal.scale();
			if (scale >= 0) {
				return LispRatio.valueOf(numerator, java.math.BigInteger.TEN.pow(scale));
			}
			return LispRatio.valueOf(numerator.multiply(java.math.BigInteger.TEN.pow(-scale)),
					java.math.BigInteger.ONE);
		}
		if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
			if (token.endsWith("N")) {
				throw new NumberFormatException(token);
			}
			return new LispDouble(Double.parseDouble(token));
		}
		return integerOf(new java.math.BigInteger(stripBigSuffix(token), 10));
	}

	/** Whether the token is a plain decimal ratio: digits around one slash, no suffix. */
	private static boolean isDecimalRatio(String token) {
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			if (c == '/') {
				continue;
			}
			if (c == '+' || c == '-') {
				continue;
			}
			if (!Character.isDigit(c)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isDigits(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (!Character.isDigit(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isOctalDigits(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < '0' || c > '7') {
				return false;
			}
		}
		return true;
	}

	private static String stripBigSuffix(String digits) {
		return digits.endsWith("N") ? digits.substring(0, digits.length() - 1) : digits;
	}

	/** A long when it fits, a {@link LispBigInteger} past the {@code long} range. */
	private static LispVal integerOf(java.math.BigInteger value) {
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	/** A radix value with the token's sign applied (the prefix tests read unsigned). */
	private static LispVal signedOf(java.math.BigInteger value, String token) {
		if (token.startsWith("-")) {
			value = value.negate();
		}
		return integerOf(value);
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
