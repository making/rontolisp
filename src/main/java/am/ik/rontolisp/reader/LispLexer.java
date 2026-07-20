package am.ik.rontolisp.reader;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Tokenizer for Lisp source code. Besides tokenizing, the lexer resolves the read-time
 * syntax that never reaches the parser: {@code #| ... |#} block comments (nesting, like
 * Common Lisp) and the {@code #+}/{@code #-} feature conditionals, evaluated against the
 * active {@link Features} -- a failing guard skips the following form at the raw
 * character level, so a skipped form may use syntax the reader does not support.
 * {@code #.} read-time evaluation is not supported and is a clear error; in the tolerant
 * mode used for {@code .asd} files the datum is wrapped in a {@code (%read-eval datum)}
 * marker for the consumer to resolve (or, when it uses unsupported syntax, skipped with a
 * warning -- the version-guard idiom).
 */
public final class LispLexer {

	/** How a {@code #.} read-time-eval form is handled. */
	public enum ReadEvalMode {

		/** {@code #.} is a read error (the default). */
		ERROR,
		/**
		 * The datum is wrapped in a {@code (%read-eval datum)} marker; an unreadable
		 * datum is skipped with a warning ({@code .asd} files).
		 */
		SKIP_UNREADABLE,
		/**
		 * The datum is wrapped in a {@code (%read-eval datum)} marker; an unreadable
		 * datum is a read error (source files, resolved by the evaluator).
		 */
		MARKER

	}

	private final String input;

	private final Features features;

	private final ReadEvalMode readEvalMode;

	private int pos;

	/**
	 * Create a new lexer for the given input with the interpreter feature set.
	 * @param input the source code string
	 */
	public LispLexer(String input) {
		this(input, Features.INTERPRETER, false);
	}

	/**
	 * Create a new lexer for the given input.
	 * @param input the source code string
	 * @param features the features the {@code #+}/{@code #-} conditionals test
	 * @param tolerateReadEval whether a {@code #.} form is skipped with a warning instead
	 * of being an error (used for {@code .asd} files)
	 */
	public LispLexer(String input, Features features, boolean tolerateReadEval) {
		this(input, features, tolerateReadEval ? ReadEvalMode.SKIP_UNREADABLE : ReadEvalMode.ERROR);
	}

	/**
	 * Create a new lexer for the given input.
	 * @param input the source code string
	 * @param features the features the {@code #+}/{@code #-} conditionals test
	 * @param readEvalMode how a {@code #.} read-time-eval form is handled
	 */
	public LispLexer(String input, Features features, ReadEvalMode readEvalMode) {
		this.input = input;
		this.features = features;
		this.readEvalMode = readEvalMode;
		this.pos = 0;
	}

	/**
	 * Tokenize the input into a list of tokens.
	 * @return the tokens
	 */
	public List<Token> tokenize() {
		List<Token> tokens = new ArrayList<>();
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (Character.isWhitespace(c)) {
				this.pos++;
			}
			else if (c == ';') {
				skipComment();
			}
			else if (c == '(') {
				tokens.add(new Token.LeftParen());
				this.pos++;
			}
			else if (c == ')') {
				tokens.add(new Token.RightParen());
				this.pos++;
			}
			else if (c == '\'') {
				tokens.add(new Token.Quote());
				this.pos++;
			}
			else if (c == '`') {
				tokens.add(new Token.Backquote());
				this.pos++;
			}
			else if (c == ',') {
				// A comma between two digits is a grouping separator consumed inside
				// readNumber (e.g. "1,000"), so a comma reaching here starts a token:
				// unquote (,x) or unquote-splicing (,@x) inside a backquote template.
				if (this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '@') {
					tokens.add(new Token.UnquoteSplicing());
					this.pos += 2;
				}
				else {
					tokens.add(new Token.Unquote());
					this.pos++;
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '\'') {
				tokens.add(new Token.FunctionQuote());
				this.pos += 2;
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '|') {
				skipBlockComment();
			}
			else if (c == '#' && this.pos + 1 < this.input.length()
					&& (this.input.charAt(this.pos + 1) == '+' || this.input.charAt(this.pos + 1) == '-')) {
				readFeatureConditional();
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '.') {
				if (this.readEvalMode == ReadEvalMode.ERROR) {
					throw new LispReadException("#. read-time evaluation is not supported");
				}
				this.pos += 2;
				int datumStart = this.pos;
				skipDatum();
				// Re-lex the skipped datum and wrap it in a (%read-eval datum) marker for
				// the consumer to resolve: AsdfSystems against a .asd file's defparameter
				// bindings, the evaluator's load against the global environment. In
				// SKIP_UNREADABLE mode a datum using syntax the lexer does not support
				// falls back to a nil placeholder, preserving the surrounding structure
				// (a skipped #. inside a plist/alist must not shift the remaining
				// key/value pairing).
				List<Token> datumTokens = tryTokenizeReadEvalDatum(this.input.substring(datumStart, this.pos));
				if (datumTokens == null) {
					if (this.readEvalMode == ReadEvalMode.MARKER) {
						throw new LispReadException(
								"#. datum could not be read: " + this.input.substring(datumStart, this.pos));
					}
					System.err.println("warning: skipping unsupported #. read-time-eval form");
					tokens.add(new Token.SymbolToken("nil"));
				}
				else {
					tokens.add(new Token.LeftParen());
					tokens.add(new Token.SymbolToken(LispNames.READ_EVAL));
					tokens.addAll(datumTokens);
					tokens.add(new Token.RightParen());
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '\\') {
				tokens.add(readChar());
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '(') {
				tokens.add(new Token.VectorOpen());
				this.pos += 2;
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '*') {
				// #*1010 is a bit-vector literal; #* alone is the empty bit vector
				// (cl-ppcre's charmap slot default #*0).
				int probe = this.pos + 2;
				while (probe < this.input.length()
						&& (this.input.charAt(probe) == '0' || this.input.charAt(probe) == '1')) {
					probe++;
				}
				tokens.add(new Token.BitVectorToken(this.input.substring(this.pos + 2, probe)));
				this.pos = probe;
			}
			else if (c == '#' && this.pos + 2 < this.input.length()
					&& (this.input.charAt(this.pos + 1) == 'f' || this.input.charAt(this.pos + 1) == 'F')
					&& this.input.charAt(this.pos + 2) == '(') {
				// #f( opens a packed-double array literal (e.g., #f(1.0 2.0 3.0)); the
				// contents are read as a nested-list structure and the rank is inferred
				// at
				// read time. #f not followed by '(' falls through to symbol reading
				// below.
				tokens.add(new Token.FloatArrayOpen(true));
				this.pos += 3;
			}
			else if (c == '#' && this.pos + 2 < this.input.length()
					&& (this.input.charAt(this.pos + 1) == 'd' || this.input.charAt(this.pos + 1) == 'D')
					&& this.input.charAt(this.pos + 2) == '(') {
				// #d( opens a packed double-float array literal (e.g., #d(1.0 2.0 3.0));
				// same nested-list contents and inferred rank as #f(, but the wider
				// (f64) backing. #d not followed by '(' falls through to symbol reading
				// below.
				tokens.add(new Token.FloatArrayOpen(false));
				this.pos += 3;
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				// #nA( opens a rank-n array literal (e.g., #2A((1 2) (3 4))). Anything
				// else after #<digits> falls through to symbol reading, preserving the
				// previous tokenization.
				int probe = this.pos + 1;
				while (probe < this.input.length() && isDigit(this.input.charAt(probe))) {
					probe++;
				}
				if (probe + 1 < this.input.length()
						&& (this.input.charAt(probe) == 'A' || this.input.charAt(probe) == 'a')
						&& this.input.charAt(probe + 1) == '(') {
					int rank;
					try {
						rank = Integer.parseInt(this.input.substring(this.pos + 1, probe));
					}
					catch (NumberFormatException overflow) {
						throw new LispReadException("Invalid array rank: " + this.input.substring(this.pos, probe));
					}
					tokens.add(new Token.ArrayOpen(rank));
					this.pos = probe + 2;
				}
				else if (probe < this.input.length()
						&& (this.input.charAt(probe) == '=' || this.input.charAt(probe) == '#')) {
					// #n= labels the next datum, #n# references it.
					int label;
					try {
						label = Integer.parseInt(this.input.substring(this.pos + 1, probe));
					}
					catch (NumberFormatException overflow) {
						throw new LispReadException("Invalid reader label: " + this.input.substring(this.pos, probe));
					}
					tokens.add(this.input.charAt(probe) == '=' ? new Token.LabelDef(label) : new Token.LabelRef(label));
					this.pos = probe + 1;
				}
				else {
					tokens.add(readSymbol());
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && isRadixMarker(this.input.charAt(this.pos + 1))) {
				tokens.add(readRadixNumber());
			}
			else if (c == '.') {
				if (this.pos + 1 >= this.input.length() || !isSymbolChar(this.input.charAt(this.pos + 1))) {
					tokens.add(new Token.Dot());
					this.pos++;
				}
				else {
					tokens.add(readSymbol());
				}
			}
			else if (c == '"') {
				tokens.add(readString());
			}
			else if (isDigit(c)) {
				tokens.add(readNumber());
			}
			else if (c == '-' && this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				tokens.add(readNumber());
			}
			else if (c == '+' && this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				// An explicitly positive number literal (+347): the sign is consumed and
				// the digits parse as usual. A '+' followed by anything else (a symbol
				// like +limit+ or the function +) stays a symbol.
				this.pos++;
				tokens.add(readNumber());
			}
			else {
				tokens.add(readSymbol());
			}
		}
		return tokens;
	}

	private void skipComment() {
		while (this.pos < this.input.length() && this.input.charAt(this.pos) != '\n') {
			this.pos++;
		}
	}

	private Token readNumber() {
		int start = this.pos;
		if (this.input.charAt(this.pos) == '-') {
			this.pos++;
		}
		// Integer digits, allowing ',' as a grouping separator when it sits
		// between two digits (e.g., "1,000" -> 1000). A comma not followed by a
		// digit is not consumed, so token boundaries are otherwise unchanged.
		consumeDigitsWithGrouping();
		boolean isFloat = false;
		// Fractional part: '.' followed by at least one digit (e.g., "1.5").
		if (this.pos < this.input.length() && this.input.charAt(this.pos) == '.' && this.pos + 1 < this.input.length()
				&& isDigit(this.input.charAt(this.pos + 1))) {
			this.pos++; // consume '.'
			while (this.pos < this.input.length() && isDigit(this.input.charAt(this.pos))) {
				this.pos++;
			}
			isFloat = true;
		}
		// Exponent part: a Common Lisp float marker e/s/f/d/l (case-insensitive)
		// followed by an optional sign and at least one digit (e.g., "1d0",
		// "1e0", "1.5f3", "-2d-3"). rontolisp has a single float type, so every
		// marker collapses to the same LispDouble (the single/double distinction
		// of Common Lisp is not preserved).
		if (consumedExponent()) {
			isFloat = true;
		}
		// Ratio literal: integer digits '/' integer digits (e.g., "1/3", "-1/3").
		if (!isFloat && this.pos < this.input.length() && this.input.charAt(this.pos) == '/'
				&& this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
			int slash = this.pos;
			this.pos++; // consume '/'
			consumeDigitsWithGrouping();
			// If a symbol character follows the denominator digits (e.g., "1/2x",
			// "1/2/3"), treat the entire token as a symbol.
			if (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
					this.pos++;
				}
				return numberFallbackSymbol(this.input.substring(start, this.pos));
			}
			String numerator = stripGrouping(this.input.substring(start, slash));
			String denominator = stripGrouping(this.input.substring(slash + 1, this.pos));
			return new Token.RatioToken(new java.math.BigInteger(numerator), new java.math.BigInteger(denominator));
		}
		// If a non-dot symbol character follows, treat the entire token as a
		// symbol (e.g., "1+" -> Symbol("1+"), "1d0x" -> Symbol("1d0x")).
		if (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))
				&& this.input.charAt(this.pos) != '.') {
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
			return numberFallbackSymbol(this.input.substring(start, this.pos));
		}
		if (isFloat) {
			return new Token.DoubleToken(
					Double.parseDouble(normalizeExponentMarker(stripGrouping(this.input.substring(start, this.pos)))));
		}
		String digits = stripGrouping(this.input.substring(start, this.pos));
		try {
			return new Token.NumberToken(Long.parseLong(digits));
		}
		catch (NumberFormatException overflow) {
			// Literal does not fit in a long: promote to an arbitrary-precision integer.
			return new Token.BigIntegerToken(new java.math.BigInteger(digits));
		}
	}

	private static boolean isRadixMarker(char c) {
		return c == 'x' || c == 'X' || c == 'o' || c == 'O' || c == 'b' || c == 'B';
	}

	// Reads a #x/#o/#b radix integer literal (e.g., #x10000, #o400, #b1010, #x-10).
	// The digits (after an optional sign) must be non-empty and valid in the radix;
	// a literal that does not fit in a long is promoted to an arbitrary-precision
	// integer, matching decimal literals.
	private Token readRadixNumber() {
		char marker = this.input.charAt(this.pos + 1);
		int radix = switch (Character.toLowerCase(marker)) {
			case 'x' -> 16;
			case 'o' -> 8;
			default -> 2;
		};
		this.pos += 2; // skip "#x" / "#o" / "#b"
		int start = this.pos;
		if (this.pos < this.input.length() && this.input.charAt(this.pos) == '-') {
			this.pos++;
		}
		int digitsStart = this.pos;
		while (this.pos < this.input.length() && Character.digit(this.input.charAt(this.pos), radix) >= 0) {
			this.pos++;
		}
		if (this.pos == digitsStart || (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos)))) {
			throw new LispReadException("Invalid digits after #" + marker + ": "
					+ this.input.substring(start, Math.min(this.pos + 1, this.input.length())));
		}
		String digits = this.input.substring(start, this.pos);
		try {
			return new Token.NumberToken(Long.parseLong(digits, radix));
		}
		catch (NumberFormatException overflow) {
			return new Token.BigIntegerToken(new java.math.BigInteger(digits, radix));
		}
	}

	private void consumeDigitsWithGrouping() {
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (isDigit(c)) {
				this.pos++;
			}
			else if (c == ',' && this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				this.pos++; // consume grouping separator between digits
			}
			else {
				break;
			}
		}
	}

	// Consume an exponent suffix (marker + optional sign + digits) if a valid
	// one starts at the current position, advancing past it and returning true.
	// On no match, the position is left untouched so the marker can fall through
	// to symbol handling (e.g. "1d" is the symbol "1d", not a float).
	private boolean consumedExponent() {
		if (this.pos >= this.input.length() || !isExponentMarker(this.input.charAt(this.pos))) {
			return false;
		}
		int probe = this.pos + 1;
		if (probe < this.input.length() && (this.input.charAt(probe) == '+' || this.input.charAt(probe) == '-')) {
			probe++;
		}
		if (probe >= this.input.length() || !isDigit(this.input.charAt(probe))) {
			return false;
		}
		while (probe < this.input.length() && isDigit(this.input.charAt(probe))) {
			probe++;
		}
		this.pos = probe;
		return true;
	}

	private static boolean isExponentMarker(char c) {
		return c == 'e' || c == 'E' || c == 's' || c == 'S' || c == 'f' || c == 'F' || c == 'd' || c == 'D' || c == 'l'
				|| c == 'L';
	}

	// Rewrite the Common Lisp exponent marker to 'e' so Double.parseDouble (which
	// only accepts 'e'/'E' as an exponent marker) reads the literal. A valid float
	// token holds at most one marker, so replacing every marker char is safe.
	private static String normalizeExponentMarker(String number) {
		StringBuilder sb = new StringBuilder(number.length());
		for (int i = 0; i < number.length(); i++) {
			char c = number.charAt(i);
			sb.append(isExponentMarker(c) ? 'e' : c);
		}
		return sb.toString();
	}

	private static String stripGrouping(String number) {
		return number.indexOf(',') < 0 ? number : number.replace(",", "");
	}

	// Reads a #\ character literal: a single character (#\a, #\(, #\Space-the-glyph)
	// or, when the first character is a letter and more letters follow, a character
	// name (#\Space, #\Newline, ...). The first character after #\ is always taken
	// literally even if it is whitespace or a delimiter.
	private Token.CharToken readChar() {
		this.pos += 2; // skip "#\"
		if (this.pos >= this.input.length()) {
			throw new LispReadException("Unexpected end of input after #\\");
		}
		int start = this.pos;
		char first = this.input.charAt(this.pos);
		this.pos++;
		// A multi-character name only follows an alphabetic first character.
		if (Character.isLetter(first)) {
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
		}
		String token = this.input.substring(start, this.pos);
		if (token.length() == 1) {
			return new Token.CharToken(first);
		}
		return new Token.CharToken(charByName(token));
	}

	private static int charByName(String name) {
		return switch (name.toLowerCase(java.util.Locale.ROOT)) {
			case "space" -> ' ';
			case "newline", "linefeed", "lf" -> '\n';
			case "tab" -> '\t';
			case "return", "cr" -> '\r';
			case "page" -> '\f';
			case "backspace" -> '\b';
			case "nul", "null" -> 0;
			case "rubout", "delete", "del" -> 127;
			case "escape", "altmode", "esc" -> 27;
			default -> throw new LispReadException("Unknown character name: #\\" + name);
		};
	}

	private Token.SymbolToken readSymbol() {
		// CL single escape: a backslash makes the NEXT character part of the symbol
		// name verbatim, even a terminating one -- real libraries name locals like
		// \(-pos (parse-number). The backslash itself is dropped from the name.
		// CL multiple escape: |...| makes every enclosed character part of the name
		// verbatim, whitespace and terminating characters included (|when used|);
		// the pipes themselves are dropped and a backslash still escapes inside.
		// The reader upcases unescaped characters like CL's :upcase readtable case
		// -- escaped ones stay verbatim -- and folds the finished name to its
		// canonical spelling (UpcaseSymbols). Only rontolisp's own lowercase-authored
		// sources are read case-preserving (Features.INTERNAL).
		boolean upcase = !this.features.preserveCase();
		StringBuilder sb = new StringBuilder();
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (c == '\\' && this.pos + 1 < this.input.length()) {
				sb.append(this.input.charAt(this.pos + 1));
				this.pos += 2;
				continue;
			}
			if (c == '|') {
				this.pos++;
				while (this.pos < this.input.length() && this.input.charAt(this.pos) != '|') {
					char e = this.input.charAt(this.pos);
					if (e == '\\' && this.pos + 1 < this.input.length()) {
						sb.append(this.input.charAt(this.pos + 1));
						this.pos += 2;
						continue;
					}
					sb.append(e);
					this.pos++;
				}
				if (this.pos >= this.input.length()) {
					throw new LispReadException("Unterminated |...| symbol escape");
				}
				this.pos++; // closing |
				continue;
			}
			if (!isSymbolChar(c)) {
				break;
			}
			sb.append(upcase ? Character.toUpperCase(c) : c);
			this.pos++;
		}
		String name = sb.toString();
		return new Token.SymbolToken(upcase ? am.ik.rontolisp.UpcaseSymbols.canonicalize(name) : name);
	}

	// A token that started as a number but fell back to a symbol (e.g. "1+"). These
	// runs come straight from the raw input with no escape processing, so the whole
	// token upcases before the canonical fold ("1+" is its own lowercase fold
	// target).
	private Token.SymbolToken numberFallbackSymbol(String raw) {
		if (this.features.preserveCase()) {
			return new Token.SymbolToken(raw);
		}
		return new Token.SymbolToken(
				am.ik.rontolisp.UpcaseSymbols.canonicalize(raw.toUpperCase(java.util.Locale.ROOT)));
	}

	private Token.StringToken readString() {
		this.pos++; // skip opening "
		StringBuilder sb = new StringBuilder();
		while (this.pos < this.input.length() && this.input.charAt(this.pos) != '"') {
			if (this.input.charAt(this.pos) == '\\' && this.pos + 1 < this.input.length()) {
				this.pos++;
				char escaped = this.input.charAt(this.pos);
				switch (escaped) {
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case '\\' -> sb.append('\\');
					case '"' -> sb.append('"');
					default -> {
						sb.append('\\');
						sb.append(escaped);
					}
				}
			}
			else {
				sb.append(this.input.charAt(this.pos));
			}
			this.pos++;
		}
		if (this.pos >= this.input.length()) {
			throw new LispReadException("Unterminated string literal");
		}
		this.pos++; // skip closing "
		return new Token.StringToken(sb.toString());
	}

	// --- Feature conditionals (#+ / #-) ------------------------------------------
	//
	// The feature expression after #+/#- is parsed here, with a minimal reader over
	// symbols and lists (a feature expression never uses other syntax), and evaluated
	// against the active feature set. When the guard fails, the following form is
	// skipped at the raw character level (skipDatum), NOT tokenized: this is what lets
	// a guarded form use syntax rontolisp does not support, which is the whole point
	// of #+/#- in portable libraries.

	private void readFeatureConditional() {
		boolean negated = this.input.charAt(this.pos + 1) == '-';
		this.pos += 2; // skip "#+" / "#-"
		LispVal expr = readFeatureExpr();
		if (this.features.isEnabled(expr) == negated) {
			skipDatum();
		}
	}

	private LispVal readFeatureExpr() {
		skipInterTokenSpace();
		if (this.pos >= this.input.length()) {
			throw new LispReadException("Unexpected end of input in feature expression");
		}
		char c = this.input.charAt(this.pos);
		if (c == '(') {
			this.pos++;
			List<LispVal> items = new ArrayList<>();
			while (true) {
				skipInterTokenSpace();
				if (this.pos >= this.input.length()) {
					throw new LispReadException("Unexpected end of input in feature expression, expected ')'");
				}
				if (this.input.charAt(this.pos) == ')') {
					this.pos++;
					break;
				}
				items.add(readFeatureExpr());
			}
			LispVal list = LispNil.INSTANCE;
			for (int i = items.size() - 1; i >= 0; i--) {
				list = new LispCons(items.get(i), list);
			}
			return list;
		}
		if (isSymbolChar(c)) {
			int start = this.pos;
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
			return new LispSymbol(this.input.substring(start, this.pos));
		}
		throw new LispReadException("Invalid feature expression starting with '" + c + "'");
	}

	// Skips whitespace and comments (line and block) between tokens.
	private void skipInterTokenSpace() {
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (Character.isWhitespace(c)) {
				this.pos++;
			}
			else if (c == ';') {
				skipComment();
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '|') {
				skipBlockComment();
			}
			else {
				return;
			}
		}
	}

	// Skips a #| ... |# block comment, honoring nesting like Common Lisp.
	private void skipBlockComment() {
		this.pos += 2; // skip "#|"
		int depth = 1;
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (c == '|' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '#') {
				this.pos += 2;
				if (--depth == 0) {
					return;
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '|') {
				this.pos += 2;
				depth++;
			}
			else {
				this.pos++;
			}
		}
		throw new LispReadException("Unterminated block comment");
	}

	// Re-lexes a #. datum that was skipped at the raw character level. Returns null when
	// the datum uses syntax the lexer does not support (the caller falls back to the nil
	// placeholder) or lexes to nothing (a fully #+/#- suppressed datum).
	@Nullable private List<Token> tryTokenizeReadEvalDatum(String datum) {
		try {
			List<Token> datumTokens = new LispLexer(datum, this.features, this.readEvalMode).tokenize();
			if (datumTokens.isEmpty() || !LispReader.parsesAsExpressions(datumTokens, this.features)) {
				return null;
			}
			return datumTokens;
		}
		catch (LispReadException ex) {
			return null;
		}
	}

	// Skips one datum at the raw character level, without tokenizing it, so a form
	// guarded by a failing #+/#- may use syntax the reader does not support. A nested
	// #+/#- produces NO datum under *read-suppress* (it consumes its feature expression
	// and guarded form and yields nothing), so this keeps skipping until a real datum is
	// consumed -- that is what makes the two-form guard idiom
	// (#+feature #+feature A B, which includes both A and B only when the feature is
	// present) skip both A and B when it is absent.
	private void skipDatum() {
		boolean consumedConditional = false;
		while (true) {
			skipInterTokenSpace();
			if (this.pos >= this.input.length()) {
				if (consumedConditional) {
					return;
				}
				throw new LispReadException("Unexpected end of input, expected a form to skip");
			}
			if (this.input.charAt(this.pos) == ')') {
				// A failing conditional guarded the last form(s) before ')': the nested
				// conditional yielded nothing, so there is nothing more to skip.
				if (consumedConditional) {
					return;
				}
				throw new LispReadException("Unexpected ')' where a form to skip was expected");
			}
			if (skipDatumOrConditional()) {
				return;
			}
			consumedConditional = true;
		}
	}

	// Skips one syntactic unit at the raw character level. Returns true if it consumed a
	// real datum, false if it consumed only a nested #+/#- conditional (which yields no
	// datum under *read-suppress*). The caller (skipDatum) has already ensured pos is at
	// a non-space character that is neither EOF nor ')'.
	private boolean skipDatumOrConditional() {
		char c = this.input.charAt(this.pos);
		if (c == '\'' || c == '`') {
			this.pos++;
			skipDatum();
			return true;
		}
		if (c == ',') {
			this.pos++;
			if (this.pos < this.input.length() && this.input.charAt(this.pos) == '@') {
				this.pos++;
			}
			skipDatum();
			return true;
		}
		if (c == '"') {
			skipStringRaw();
			return true;
		}
		if (c == '(') {
			skipDelimitedList();
			return true;
		}
		if (c == '#' && this.pos + 1 < this.input.length()) {
			char next = this.input.charAt(this.pos + 1);
			if (next == '\\') {
				skipCharLiteralRaw();
				return true;
			}
			if (next == '\'') {
				this.pos += 2;
				skipDatum();
				return true;
			}
			if (next == '(') {
				this.pos++;
				skipDelimitedList();
				return true;
			}
			if (next == '+' || next == '-') {
				// A nested conditional inside a skipped form: skip its feature
				// expression and its guarded form, like *read-suppress*. It yields NO
				// datum, so report false -- the enclosing skipDatum keeps going.
				this.pos += 2;
				skipDatum();
				skipDatum();
				return false;
			}
			if (next == '.') {
				this.pos += 2;
				skipDatum();
				return true;
			}
			// #2A(...) and friends: skip the symbol-shaped prefix, then a directly
			// following list (a glued '(' only occurs in array literals here).
			this.pos++;
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
			if (this.pos < this.input.length() && this.input.charAt(this.pos) == '(') {
				skipDelimitedList();
			}
			return true;
		}
		// A symbol or number token.
		while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
			this.pos++;
		}
		return true;
	}

	// Skips a balanced (...) list at the raw character level, honoring strings,
	// comments and character literals (so #\( and "..." do not confuse the depth).
	private void skipDelimitedList() {
		int depth = 0;
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (c == '"') {
				skipStringRaw();
			}
			else if (c == ';') {
				skipComment();
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '|') {
				skipBlockComment();
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '\\') {
				skipCharLiteralRaw();
			}
			else if (c == '(') {
				depth++;
				this.pos++;
			}
			else if (c == ')') {
				depth--;
				this.pos++;
				if (depth == 0) {
					return;
				}
			}
			else {
				this.pos++;
			}
		}
		throw new LispReadException("Unexpected end of input in a skipped form, expected ')'");
	}

	private void skipStringRaw() {
		this.pos++; // skip opening "
		while (this.pos < this.input.length() && this.input.charAt(this.pos) != '"') {
			if (this.input.charAt(this.pos) == '\\' && this.pos + 1 < this.input.length()) {
				this.pos++;
			}
			this.pos++;
		}
		if (this.pos >= this.input.length()) {
			throw new LispReadException("Unterminated string literal");
		}
		this.pos++; // skip closing "
	}

	private void skipCharLiteralRaw() {
		this.pos += 2; // skip "#\"
		if (this.pos >= this.input.length()) {
			throw new LispReadException("Unexpected end of input after #\\");
		}
		char first = this.input.charAt(this.pos);
		this.pos++;
		if (Character.isLetter(first)) {
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
		}
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isSymbolChar(char c) {
		// ',' and '`' terminate a symbol (Common Lisp terminating macro characters)
		// so `(a ,b) and `(,x ,@xs) tokenize without surrounding whitespace. A comma
		// used as a digit-grouping separator ("1,000") is consumed inside readNumber
		// before this predicate is consulted.
		return !Character.isWhitespace(c) && c != '(' && c != ')' && c != '\'' && c != '"' && c != ';' && c != ','
				&& c != '`';
	}

}
