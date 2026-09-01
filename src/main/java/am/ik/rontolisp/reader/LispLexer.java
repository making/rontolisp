package am.ik.rontolisp.reader;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * Tokenizer for Lisp source code. Besides tokenizing, the lexer resolves the read-time
 * syntax that never reaches the parser: {@code #| ... |#} block comments (nesting, like
 * Common Lisp) and the {@code #+}/{@code #-} feature conditionals, evaluated against the
 * active {@link Features} -- a failing guard skips the following form at the raw
 * character level, so a skipped form may use syntax the reader does not support.
 * {@code #.} read-time evaluation is not supported and is a clear error; in the tolerant
 * mode used for {@code .asd} files the datum is wrapped in a {@code (%read-eval datum)}
 * marker for the consumer to resolve (or, when it uses syntax the lexer cannot re-lex, in
 * a {@code (%read-eval-unreadable "RAW TEXT")} marker the consumer decides about).
 */
public final class LispLexer {

	/** How a {@code #.} read-time-eval form is handled. */
	public enum ReadEvalMode {

		/** {@code #.} is a read error (the default). */
		ERROR,
		/**
		 * The datum is wrapped in a {@code (%read-eval datum)} marker; a datum that
		 * cannot be re-lexed is wrapped in a {@code (%read-eval-unreadable "RAW TEXT")}
		 * marker instead, for the consumer to accept or reject ({@code .asd} files).
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

	/** The origin file, or {@code null} when unknown; used to prefix read errors. */
	private final @Nullable String file;

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
		this(input, features, readEvalMode, null);
	}

	/**
	 * Create a new lexer for the given input.
	 * @param input the source code string
	 * @param features the features the {@code #+}/{@code #-} conditionals test
	 * @param readEvalMode how a {@code #.} read-time-eval form is handled
	 * @param file the origin file, or {@code null} when unknown
	 */
	public LispLexer(String input, Features features, ReadEvalMode readEvalMode, @Nullable String file) {
		this.input = input;
		this.features = features;
		this.readEvalMode = readEvalMode;
		this.file = file;
		this.pos = 0;
	}

	/**
	 * A {@link LispReadException} whose message is prefixed with the current position in
	 * this input. The lexer scans character-by-character, so {@code pos} is the failing
	 * (or last-consumed) offset -- right for every error the scan detects AT the bad
	 * input. A construct that only fails at end of input uses {@link #errAt} instead.
	 * @param message the error message
	 * @return the positioned exception
	 */
	private LispReadException err(String message) {
		return errAt(this.pos, message);
	}

	/**
	 * A {@link LispReadException} positioned at an explicit offset rather than at the
	 * scan position. Used by every construct that fails only once it runs out of input
	 * (an unterminated string, block comment, {@code |...|} escape or skipped list): the
	 * scan position is then end-of-file, which in a big spliced library names the last
	 * line of the file instead of the opening delimiter that actually needs fixing.
	 * @param offset the character offset to report
	 * @param message the error message
	 * @return the positioned exception
	 */
	private LispReadException errAt(int offset, String message) {
		return new LispReadException(message, SourceLocation.at(this.file, offset, this.input));
	}

	private static void add(List<LocatedToken> tokens, Token token, int offset) {
		tokens.add(new LocatedToken(token, offset));
	}

	/**
	 * Tokenize the input into a list of tokens.
	 * @return the tokens
	 */
	public List<Token> tokenize() {
		return tokenizeWithPositions().stream().map(LocatedToken::token).toList();
	}

	/**
	 * Tokenize the input into a list of tokens, each paired with the character offset at
	 * which it starts in the input -- the position the parser reports on a read error.
	 * @return the positioned tokens
	 */
	public List<LocatedToken> tokenizeWithPositions() {
		List<LocatedToken> tokens = new ArrayList<>();
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			// Whitespace, comments and feature conditionals produce no token; consuming
			// them here lets every token branch below start at a real token start.
			if (Character.isWhitespace(c)) {
				this.pos++;
				continue;
			}
			if (c == ';') {
				skipComment();
				continue;
			}
			if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '|') {
				skipBlockComment();
				continue;
			}
			if (c == '#' && this.pos + 1 < this.input.length()
					&& (this.input.charAt(this.pos + 1) == '+' || this.input.charAt(this.pos + 1) == '-')) {
				readFeatureConditional();
				continue;
			}
			int tokenStart = this.pos;
			if (c == '(') {
				add(tokens, new Token.LeftParen(), tokenStart);
				this.pos++;
			}
			else if (c == ')') {
				add(tokens, new Token.RightParen(), tokenStart);
				this.pos++;
			}
			else if (c == '\'') {
				add(tokens, new Token.Quote(), tokenStart);
				this.pos++;
			}
			else if (c == '`') {
				add(tokens, new Token.Backquote(), tokenStart);
				this.pos++;
			}
			else if (c == ',') {
				// A comma between two digits is a grouping separator consumed inside
				// readNumber (e.g. "1,000"), so a comma reaching here starts a token:
				// unquote (,x) or unquote-splicing (,@x / ,.x) inside a backquote
				// template. CLHS 2.4.6 gives ",." the semantics of ",@" plus permission
				// to destroy the spliced list; splicing non-destructively is conformant,
				// so both spellings produce one token.
				if (this.pos + 1 < this.input.length()
						&& (this.input.charAt(this.pos + 1) == '@' || this.input.charAt(this.pos + 1) == '.')) {
					add(tokens, new Token.UnquoteSplicing(), tokenStart);
					this.pos += 2;
				}
				else {
					add(tokens, new Token.Unquote(), tokenStart);
					this.pos++;
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '\'') {
				add(tokens, new Token.FunctionQuote(), tokenStart);
				this.pos += 2;
			}
			else if (c == '#' && this.pos + 1 < this.input.length()
					&& (this.input.charAt(this.pos + 1) == 'L' || this.input.charAt(this.pos + 1) == 'l')) {
				// #L( -- iterate's SharpL abbreviation, native here for the same reason
				// #N@( is: a user dispatch macro cannot extend the Java-side reader, and
				// set-dispatch-macro-character is an accepted no-op. The arity is the
				// highest !n the datum mentions; the reader does the lowering.
				add(tokens, new Token.SharpL(-1), tokenStart);
				this.pos += 2;
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '.') {
				if (this.readEvalMode == ReadEvalMode.ERROR) {
					throw err("#. read-time evaluation is not supported");
				}
				this.pos += 2;
				int datumStart = this.pos;
				skipDatum();
				// Re-lex the skipped datum and wrap it in a (%read-eval datum) marker for
				// the consumer to resolve: AsdfSystems against a .asd file's defparameter
				// bindings, the evaluator's load against the global environment. In
				// SKIP_UNREADABLE mode a datum using syntax the lexer does not support
				// becomes a (%read-eval-unreadable "RAW TEXT") marker instead: it keeps
				// the surrounding structure (a #. inside a plist/alist must not shift the
				// remaining key/value pairing) AND leaves the decision "does this value
				// matter" to the consumer, which is the only layer that knows. Warning
				// here cannot be right -- most such data sits in metadata nothing reads.
				List<LocatedToken> datumTokens = tryTokenizeReadEvalDatum(this.input.substring(datumStart, this.pos));
				if (datumTokens == null) {
					if (this.readEvalMode == ReadEvalMode.MARKER) {
						throw err("#. datum could not be read: " + this.input.substring(datumStart, this.pos));
					}
					add(tokens, new Token.LeftParen(), tokenStart);
					add(tokens, new Token.SymbolToken(LispNames.READ_EVAL_UNREADABLE), tokenStart);
					add(tokens, new Token.StringToken(this.input.substring(datumStart, this.pos).trim()), datumStart);
					add(tokens, new Token.RightParen(), this.pos);
				}
				else {
					// The marker's tokens all sit at (or just after) the #.; the datum's
					// own tokens are rebased onto their real offset in this input.
					add(tokens, new Token.LeftParen(), tokenStart);
					add(tokens, new Token.SymbolToken(LispNames.READ_EVAL), tokenStart);
					for (LocatedToken datumToken : datumTokens) {
						tokens.add(new LocatedToken(datumToken.token(), datumStart + datumToken.offset()));
					}
					add(tokens, new Token.RightParen(), this.pos);
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '\\') {
				add(tokens, readChar(), tokenStart);
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '(') {
				add(tokens, new Token.VectorOpen(), tokenStart);
				this.pos += 2;
			}
			else if (c == '#' && this.pos + 2 < this.input.length()
					&& (this.input.charAt(this.pos + 1) == 'S' || this.input.charAt(this.pos + 1) == 's')
					&& this.input.charAt(this.pos + 2) == '(') {
				// #S( opens a structure literal (e.g., #S(POINT :X 1 :Y 2)); the contents
				// are read as data and folded into an instance once a registry is
				// available. #S not followed by '(' falls through to symbol reading
				// below,
				// which is what it did before this branch existed.
				add(tokens, new Token.StructOpen(), tokenStart);
				this.pos += 3;
			}
			else if (c == '#' && this.pos + 2 < this.input.length()
					&& (this.input.charAt(this.pos + 1) == 'P' || this.input.charAt(this.pos + 1) == 'p')
					&& this.input.charAt(this.pos + 2) == '"') {
				// #P"foo/bar" is a pathname literal: the string that follows is the
				// namestring and the reader builds the pathname VALUE (an instance over
				// the fixed LispLayout.PATHNAME) from the pair. #P not followed by a
				// string falls through to symbol reading.
				add(tokens, new Token.PathnameOpen(), tokenStart);
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
				add(tokens, new Token.BitVectorToken(this.input.substring(this.pos + 2, probe)), tokenStart);
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
				add(tokens, new Token.FloatArrayOpen(true), tokenStart);
				this.pos += 3;
			}
			else if (c == '#' && this.pos + 2 < this.input.length()
					&& (this.input.charAt(this.pos + 1) == 'd' || this.input.charAt(this.pos + 1) == 'D')
					&& this.input.charAt(this.pos + 2) == '(') {
				// #d( opens a packed double-float array literal (e.g., #d(1.0 2.0 3.0));
				// same nested-list contents and inferred rank as #f(, but the wider (f64)
				// backing. #d not followed by '(' falls through to symbol reading below.
				add(tokens, new Token.FloatArrayOpen(false), tokenStart);
				this.pos += 3;
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				// #nA( opens a rank-n array literal (e.g., #2A((1 2) (3 4))), and #0A
				// opens the rank-0 one, whose single datum follows WITHOUT parens
				// (#0A5, #0ANIL, #0A(1 2) -- an array holding the list). Anything else
				// after #<digits> falls through to symbol reading, preserving the
				// previous tokenization.
				int probe = this.pos + 1;
				while (probe < this.input.length() && isDigit(this.input.charAt(probe))) {
					probe++;
				}
				int arrayRank = -1;
				if (probe < this.input.length()
						&& (this.input.charAt(probe) == 'A' || this.input.charAt(probe) == 'a')) {
					try {
						arrayRank = Integer.parseInt(this.input.substring(this.pos + 1, probe));
					}
					catch (NumberFormatException overflow) {
						throw err("Invalid array rank: " + this.input.substring(this.pos, probe));
					}
					// A rank-0 literal is #0A<datum> -- no parens to consume, the reader
					// takes the next object whole. Every other rank opens with '('.
					if (arrayRank > 0 && !(probe + 1 < this.input.length() && this.input.charAt(probe + 1) == '(')) {
						arrayRank = -1;
					}
				}
				if (arrayRank >= 0) {
					add(tokens, new Token.ArrayOpen(arrayRank), tokenStart);
					this.pos = arrayRank == 0 ? probe + 1 : probe + 2;
				}
				else if (probe + 1 < this.input.length() && this.input.charAt(probe) == '@'
						&& this.input.charAt(probe + 1) == '(') {
					// #N@( is ironclad's s-box literal (its array-reader dispatch
					// macro): a (make-array LEN :element-type '(unsigned-byte N)
					// :initial-contents '(...)) form. For the packed widths (8/16/32)
					// it reads into the packed integer-vector representation -- the
					// same value the ironclad form evaluates to now that make-array
					// packs those element types; any other width reads as a plain
					// vector literal.
					int width;
					try {
						width = Integer.parseInt(this.input.substring(this.pos + 1, probe));
					}
					catch (NumberFormatException overflow) {
						throw err("Invalid packed width: " + this.input.substring(this.pos, probe));
					}
					add(tokens, width == 8 || width == 16 || width == 32 ? new Token.IntVectorOpen(width)
							: new Token.VectorOpen(), tokenStart);
					this.pos = probe + 2;
				}
				else if (probe < this.input.length()
						&& (this.input.charAt(probe) == 'L' || this.input.charAt(probe) == 'l')) {
					// #nL: iterate's numbered-argument lambda with the arity spelled
					// out. Lowered by the reader over the datum that follows.
					int nArgs;
					try {
						nArgs = Integer.parseInt(this.input.substring(this.pos + 1, probe));
					}
					catch (NumberFormatException overflow) {
						throw err("Invalid #L argument count: " + this.input.substring(this.pos, probe));
					}
					add(tokens, new Token.SharpL(nArgs), tokenStart);
					this.pos = probe + 1;
				}
				else if (probe < this.input.length()
						&& (this.input.charAt(probe) == '=' || this.input.charAt(probe) == '#')) {
					// #n= labels the next datum, #n# references it.
					int label;
					try {
						label = Integer.parseInt(this.input.substring(this.pos + 1, probe));
					}
					catch (NumberFormatException overflow) {
						throw err("Invalid reader label: " + this.input.substring(this.pos, probe));
					}
					add(tokens, this.input.charAt(probe) == '=' ? new Token.LabelDef(label) : new Token.LabelRef(label),
							tokenStart);
					this.pos = probe + 1;
				}
				else {
					add(tokens, readSymbol(), tokenStart);
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && isRadixMarker(this.input.charAt(this.pos + 1))) {
				add(tokens, readRadixNumber(), tokenStart);
			}
			else if (c == '.') {
				if (this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
					// ".4" is the float 0.4 (CLHS 2.3.1), not a symbol: a decimal point
					// followed by a digit always starts a number, so "(a .5)" reads as
					// (A 0.5) -- a dotted pair must spell the dot bare, "(a . 5)".
					add(tokens, readNumber(), tokenStart);
				}
				else if (this.pos + 1 >= this.input.length() || !isSymbolChar(this.input.charAt(this.pos + 1))) {
					add(tokens, new Token.Dot(), tokenStart);
					this.pos++;
				}
				else {
					add(tokens, readSymbol(), tokenStart);
				}
			}
			else if (c == '"') {
				add(tokens, readString(), tokenStart);
			}
			else if (isDigit(c)) {
				add(tokens, readNumber(), tokenStart);
			}
			else if (c == '-' && this.pos + 1 < this.input.length()
					&& (isDigit(this.input.charAt(this.pos + 1)) || startsDotNumber(this.pos + 1))) {
				add(tokens, readNumber(), tokenStart);
			}
			else if (c == '+' && this.pos + 1 < this.input.length()
					&& (isDigit(this.input.charAt(this.pos + 1)) || startsDotNumber(this.pos + 1))) {
				// An explicitly positive number literal (+347): the sign is consumed and
				// the digits parse as usual. A '+' followed by anything else (a symbol
				// like +limit+ or the function +) stays a symbol.
				this.pos++;
				add(tokens, readNumber(), tokenStart);
			}
			else {
				add(tokens, readSymbol(), tokenStart);
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
		boolean isFloat = false;
		if (startsDotNumber(this.pos)) {
			// ".4": a leading decimal point with digits after it is always a float
			// (CLHS 2.3.1). The dispatcher only routes here when a digit follows.
			this.pos++; // consume '.'
			isFloat = true;
			while (this.pos < this.input.length() && isDigit(this.input.charAt(this.pos))) {
				this.pos++;
			}
		}
		else {
			// Integer digits, allowing ',' as a grouping separator when it sits
			// between two digits (e.g., "1,000" -> 1000). A comma not followed by a
			// digit is not consumed, so token boundaries are otherwise unchanged.
			consumeDigitsWithGrouping();
			// Fractional part: '.' followed by at least one digit (e.g., "1.5").
			if (this.pos < this.input.length() && this.input.charAt(this.pos) == '.'
					&& this.pos + 1 < this.input.length() && isDigit(this.input.charAt(this.pos + 1))) {
				this.pos++; // consume '.'
				while (this.pos < this.input.length() && isDigit(this.input.charAt(this.pos))) {
					this.pos++;
				}
				isFloat = true;
			}
		}
		// Exponent part: a Common Lisp float marker e/s/f/d/l (case-insensitive)
		// followed by an optional sign and at least one digit (e.g., "1d0",
		// "1e0", "1.5f3", "-2d-3"). rontolisp has a single float type, so every
		// marker collapses to the same LispDouble (the single/double distinction
		// of Common Lisp is not preserved).
		if (consumedExponent()) {
			isFloat = true;
		}
		else if (!isFloat && this.pos < this.input.length() && this.input.charAt(this.pos) == '.'
				&& exponentEndsAt(this.pos + 1) >= 0) {
			// "1.e5": an exponent marker may follow the decimal point directly.
			this.pos++; // consume '.'
			consumedExponent();
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
		// Trailing decimal point: "1." is the DECIMAL INTEGER 1 (CLHS 2.3.1) --
		// the dot is a marker consumed with the token, not part of the value.
		boolean trailingDot = false;
		if (!isFloat && this.pos < this.input.length() && this.input.charAt(this.pos) == '.') {
			trailingDot = true;
			this.pos++;
		}
		// If a symbol character follows, treat the entire token as a symbol
		// (e.g., "1+" -> Symbol("1+"), "1d0x" -> Symbol("1d0x")). A dot joins the
		// symbol text once one has been consumed as trailing or the number is
		// already a float -- "1.." and "1.2.3" are invalid numbers, and the
		// symbol fallback is this lexer's answer to those ("1+" being the
		// canonical one), never a silently split token.
		if (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))
				&& (this.input.charAt(this.pos) != '.' || trailingDot || isFloat)) {
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
			return numberFallbackSymbol(this.input.substring(start, this.pos));
		}
		if (isFloat) {
			return new Token.DoubleToken(
					Double.parseDouble(normalizeExponentMarker(stripGrouping(this.input.substring(start, this.pos)))));
		}
		String digits = stripGrouping(this.input.substring(start, trailingDot ? this.pos - 1 : this.pos));
		try {
			return new Token.NumberToken(Long.parseLong(digits));
		}
		catch (NumberFormatException overflow) {
			// Literal does not fit in a long: promote to an arbitrary-precision integer.
			return new Token.BigIntegerToken(new java.math.BigInteger(digits));
		}
	}

	// True when a '.' at `pos` starts the digit run of a number: ".4", the tail
	// of "-.5"/"+.25" (the dispatcher checks the char after the sign).
	private boolean startsDotNumber(int pos) {
		return pos < this.input.length() && this.input.charAt(pos) == '.' && pos + 1 < this.input.length()
				&& isDigit(this.input.charAt(pos + 1));
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
			throw err("Invalid digits after #" + marker + ": "
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
		int end = exponentEndsAt(this.pos);
		if (end < 0) {
			return false;
		}
		this.pos = end;
		return true;
	}

	// The index just past a valid exponent suffix (marker + optional sign +
	// digits) starting at `pos`, or -1 when none starts there.
	private int exponentEndsAt(int pos) {
		if (pos >= this.input.length() || !isExponentMarker(this.input.charAt(pos))) {
			return -1;
		}
		int probe = pos + 1;
		if (probe < this.input.length() && (this.input.charAt(probe) == '+' || this.input.charAt(probe) == '-')) {
			probe++;
		}
		if (probe >= this.input.length() || !isDigit(this.input.charAt(probe))) {
			return -1;
		}
		while (probe < this.input.length() && isDigit(this.input.charAt(probe))) {
			probe++;
		}
		return probe;
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
		int literalStart = this.pos;
		this.pos += 2; // skip "#\"
		if (this.pos >= this.input.length()) {
			throw errAt(literalStart, "Unexpected end of input after #\\");
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
		return new Token.CharToken(charByName(token, literalStart));
	}

	private int charByName(String name, int literalStart) {
		return switch (name.toLowerCase(java.util.Locale.ROOT)) {
			case "space" -> ' ';
			case "newline", "linefeed", "lf" -> '\n';
			case "tab" -> '\t';
			case "return", "cr" -> '\r';
			case "page" -> '\f';
			case "backspace" -> '\b';
			case "vt", "vertical-tab" -> 11;
			case "bell", "bel" -> 7;
			case "nul", "null" -> 0;
			case "rubout", "delete", "del" -> 127;
			case "escape", "altmode", "esc" -> 27;
			default -> unicodeCharByName(name, literalStart);
		};
	}

	// The long spelling: a Unicode character NAME, which is how a portable source names
	// a character with no short name (#\No-break_space, #\Ideographic_space). The name is
	// written with underscores for the spaces in the UCD name, and matched
	// case-insensitively. Source is read by this lexer on every backend -- the table is
	// the host JDK's, and none of it travels into a compiled program -- so a name that
	// reads here reads on all four.
	private int unicodeCharByName(String name, int literalStart) {
		try {
			return Character.codePointOf(name.replace('_', ' ').toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw errAt(literalStart, "Unknown character name: #\\" + name);
		}
	}

	private Token.SymbolToken readSymbol() {
		// CL single escape: a backslash makes the NEXT character part of the symbol
		// name verbatim, even a terminating one -- real libraries name locals like
		// \(-pos (parse-number). The backslash itself is dropped from the name.
		// CL multiple escape: |...| makes every enclosed character part of the name
		// verbatim, whitespace and terminating characters included (|when used|);
		// the pipes themselves are dropped and a backslash still escapes inside.
		// The reader upcases unescaped characters like CL's :upcase readtable case
		// -- escaped ones stay verbatim. There is no fold to a lowercase canonical
		// spelling: the uppercase name IS canonical (foo and FOO both read as FOO).
		StringBuilder sb = new StringBuilder();
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (c == '\\' && this.pos + 1 < this.input.length()) {
				sb.append(this.input.charAt(this.pos + 1));
				this.pos += 2;
				continue;
			}
			if (c == '|') {
				int escapeStart = this.pos;
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
					throw errAt(escapeStart, "Unterminated |...| symbol escape");
				}
				this.pos++; // closing |
				continue;
			}
			if (!isSymbolChar(c)) {
				break;
			}
			sb.append(Character.toUpperCase(c));
			this.pos++;
		}
		return new Token.SymbolToken(sb.toString());
	}

	// A token that started as a number but fell back to a symbol (e.g. "1+"). These
	// runs come straight from the raw input with no escape processing, so the whole
	// token upcases like every other symbol.
	private Token.SymbolToken numberFallbackSymbol(String raw) {
		return new Token.SymbolToken(raw.toUpperCase(java.util.Locale.ROOT));
	}

	private Token.StringToken readString() {
		int start = this.pos;
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
			throw errAt(start, "Unterminated string literal");
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
			throw err("Unexpected end of input in feature expression");
		}
		char c = this.input.charAt(this.pos);
		if (c == '(') {
			this.pos++;
			List<LispVal> items = new ArrayList<>();
			while (true) {
				skipInterTokenSpace();
				if (this.pos >= this.input.length()) {
					throw err("Unexpected end of input in feature expression, expected ')'");
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
		throw err("Invalid feature expression starting with '" + c + "'");
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
		int start = this.pos;
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
		throw errAt(start, "Unterminated block comment");
	}

	// Re-lexes a #. datum that was skipped at the raw character level. Returns null when
	// the datum uses syntax the lexer does not support (the caller falls back to the nil
	// placeholder) or lexes to nothing (a fully #+/#- suppressed datum).
	@Nullable private List<LocatedToken> tryTokenizeReadEvalDatum(String datum) {
		try {
			List<LocatedToken> datumTokens = new LispLexer(datum, this.features, this.readEvalMode)
				.tokenizeWithPositions();
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
				throw err("Unexpected end of input, expected a form to skip");
			}
			if (this.input.charAt(this.pos) == ')') {
				// A failing conditional guarded the last form(s) before ')': the nested
				// conditional yielded nothing, so there is nothing more to skip.
				if (consumedConditional) {
					return;
				}
				throw err("Unexpected ')' where a form to skip was expected");
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
			// following list (a glued '(' only occurs in array literals here) or a
			// directly following string (#P"..." -- without this the namestring would
			// surface as a datum of its own).
			this.pos++;
			while (this.pos < this.input.length() && isSymbolChar(this.input.charAt(this.pos))) {
				this.pos++;
			}
			if (this.pos < this.input.length() && this.input.charAt(this.pos) == '(') {
				skipDelimitedList();
			}
			else if (this.pos < this.input.length() && this.input.charAt(this.pos) == '"') {
				skipStringRaw();
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
		int start = this.pos;
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
		throw errAt(start, "Unexpected end of input in a skipped form, expected ')'");
	}

	private void skipStringRaw() {
		int start = this.pos;
		this.pos++; // skip opening "
		while (this.pos < this.input.length() && this.input.charAt(this.pos) != '"') {
			if (this.input.charAt(this.pos) == '\\' && this.pos + 1 < this.input.length()) {
				this.pos++;
			}
			this.pos++;
		}
		if (this.pos >= this.input.length()) {
			throw errAt(start, "Unterminated string literal");
		}
		this.pos++; // skip closing "
	}

	private void skipCharLiteralRaw() {
		int start = this.pos;
		this.pos += 2; // skip "#\"
		if (this.pos >= this.input.length()) {
			throw errAt(start, "Unexpected end of input after #\\");
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
