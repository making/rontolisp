package am.ik.rontolisp.scheme;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The Scheme reader: source text to DATUMS, case-sensitively. It is its own reader rather
 * than a mode of {@code LispLexer} because nearly every token rule differs -- identifiers
 * keep their case, {@code #t}/{@code #f} are booleans, {@code #;} comments out a datum,
 * {@code |} is not an escape -- and bending the Common Lisp lexer would put every one of
 * those behind a flag on the hot path of the language the project is actually about.
 *
 * <p>
 * A datum is an ordinary {@link LispVal}, so the lowering walks the same types every
 * other pass does: an identifier is a {@link LispSymbol} holding its spelling VERBATIM
 * (mangling is the lowering's job), the empty list is {@link LispNil}, a vector a
 * {@link LispArray}, a bytevector an 8-bit {@link LispIntVector}, and the two booleans
 * are the symbols {@link #TRUE} / {@link #FALSE}, compared by IDENTITY
 * ({@link #isBoolean}): {@code |#t|} is an identifier spelled like one, an equal but
 * distinct symbol.
 *
 * <p>
 * Every list's head cons is recorded with {@link SourceProvenance}, like
 * {@code LispReader.readExpr} does, so a lowering that inherits positions keeps
 * {@code file:line:column} in compile errors ({@code .kb/source-positions.md}). With no
 * recording scope open -- the interpreter reading a named file -- that head cons is a
 * {@link LocatedCons} instead, which the lowering keeps through its rewrites so an
 * uncaught condition can say where it happened ("Phase 4" there).
 */
final class SchemeReader {

	/** The datum {@code #t} / {@code #true}. */
	static final LispSymbol TRUE = new LispSymbol("#t");

	/** The datum {@code #f} / {@code #false}. */
	static final LispSymbol FALSE = new LispSymbol("#f");

	private static final LispSymbol DOT = new LispSymbol(".");

	private static final LispSymbol CLOSE = new LispSymbol(")");

	private static final List<String> INFINITIES_AND_NANS = List.of("+inf.0", "-inf.0", "+nan.0", "-nan.0");

	private static final Map<String, Integer> CHARACTER_NAMES = Map.ofEntries(Map.entry("alarm", 0x07),
			Map.entry("backspace", 0x08), Map.entry("delete", 0x7f), Map.entry("escape", 0x1b),
			Map.entry("newline", 0x0a), Map.entry("null", 0x00), Map.entry("nul", 0x00), Map.entry("return", 0x0d),
			Map.entry("space", 0x20), Map.entry("tab", 0x09), Map.entry("linefeed", 0x0a));

	/**
	 * Whether the datum is one of the two booleans the reader answers, rather than an
	 * identifier spelled {@code |#t|} or {@code |#f|}.
	 * @param datum a datum
	 * @return {@code true} for {@link #TRUE} and {@link #FALSE} themselves
	 */
	static boolean isBoolean(LispVal datum) {
		return datum == TRUE || datum == FALSE;
	}

	private final String input;

	private final @Nullable String file;

	private final SourceProvenance.@Nullable Unit unit;

	// The file every list's head cons is located in (LocatedCons), or null: a named
	// file read with no compile-path scope open, i.e. the interpreter's.
	private final @Nullable String runtimeFile;

	// The offset each line starts at, built on the first located datum.
	private int @Nullable [] lineStarts;

	// Every recorded cons, always: a syntax error names its position on the interpreter
	// too, where SourceProvenance records nothing.
	private final Map<LispCons, Integer> offsets = new IdentityHashMap<>();

	private int pos;

	private int firstDatumOffset;

	// R7RS 7.1.1 <directive>: #!fold-case / #!no-fold-case, toggled while reading this
	// file. Off by default; folds identifiers and character NAMES as string-foldcase
	// does (SchemeCharacters.foldcase), never string literals or the character itself.
	private boolean foldCase;

	// The readers of the files this one's program named -- an include, a library found
	// by name -- so a datum read from one of them is positioned in ITS file.
	private final List<SchemeReader> others = new ArrayList<>();

	SchemeReader(String input, @Nullable String file) {
		this.input = input;
		this.file = file;
		this.unit = SourceProvenance.isRecording() ? new SourceProvenance.Unit(file, input) : null;
		this.runtimeFile = this.unit == null ? file : null;
	}

	/**
	 * The file this reader reads, as the program named it.
	 * @return the path, or {@code null} for a buffer with no file
	 */
	@Nullable String file() {
		return this.file;
	}

	/**
	 * Reads another file of the same program, whose datums this reader then positions: an
	 * {@code include}d file, a library file found by name.
	 * @param text the file's contents
	 * @param path the file
	 * @param foldCase whether identifiers are folded from the start ({@code include-ci})
	 * @return the other file's reader, its datums read
	 */
	SchemeReader other(String text, String path, boolean foldCase) {
		SchemeReader other = new SchemeReader(text, path);
		other.foldCase = foldCase;
		this.others.add(other);
		return other;
	}

	/**
	 * Where a datum this reader (or one of its {@link #other} readers) produced stands in
	 * the source.
	 * @param datum a datum
	 * @return its position, or {@code null} for an atom or a cons the reader did not
	 * build
	 */
	@Nullable SourceLocation locate(LispVal datum) {
		Integer offset = datum instanceof LispCons cons ? this.offsets.get(cons) : null;
		if (offset != null) {
			return SourceLocation.at(this.file, offset, this.input);
		}
		for (SchemeReader other : this.others) {
			SourceLocation located = other.locate(datum);
			if (located != null) {
				return located;
			}
		}
		return null;
	}

	/**
	 * Records a cons a later pass built as standing where the original stands, so an
	 * error in it is positioned too.
	 * @param original a datum this reader produced (or one already inherited)
	 * @param rewritten what replaces it
	 */
	void inherit(LispCons original, LispCons rewritten) {
		Integer offset = this.offsets.get(original);
		if (offset != null) {
			this.offsets.putIfAbsent(rewritten, offset);
			return;
		}
		for (SchemeReader other : this.others) {
			other.inherit(original, rewritten);
		}
	}

	/**
	 * Reads every datum in the input.
	 * @return the top-level datums, in order
	 */
	List<LispVal> readAll() {
		List<LispVal> datums = new ArrayList<>();
		while (true) {
			skipAtmosphere();
			if (this.pos >= this.input.length()) {
				return datums;
			}
			int start = this.pos;
			LispVal datum = readDatum();
			if (datum == CLOSE) {
				throw error("unexpected ')'", start);
			}
			if (datum == DOT) {
				throw error("unexpected '.'", start);
			}
			if (datums.isEmpty()) {
				this.firstDatumOffset = start;
			}
			datums.add(datum);
		}
	}

	/**
	 * Where the first top-level datum {@link #readAll} read stands, an atom included; the
	 * start of the input when there was none.
	 * @return the position
	 */
	SourceLocation locateFirstDatum() {
		return SourceLocation.at(this.file, this.firstDatumOffset, this.input);
	}

	// Reads one datum, or the CLOSE / DOT sentinel a list reader is waiting for.
	private LispVal readDatum() {
		skipAtmosphere();
		if (this.pos >= this.input.length()) {
			throw eof("unexpected end of input", this.pos);
		}
		int start = this.pos;
		char c = this.input.charAt(this.pos);
		switch (c) {
			case '(' -> {
				this.pos++;
				return readList(start);
			}
			case ')' -> {
				this.pos++;
				return CLOSE;
			}
			case '[', ']', '{', '}' -> throw error("'" + c + "' is not a delimiter in R7RS; use parentheses", start);
			case '\'' -> {
				this.pos++;
				return abbreviation("quote", start);
			}
			case '`' -> {
				this.pos++;
				return abbreviation("quasiquote", start);
			}
			case ',' -> {
				this.pos++;
				if (this.pos < this.input.length() && this.input.charAt(this.pos) == '@') {
					this.pos++;
					return abbreviation("unquote-splicing", start);
				}
				return abbreviation("unquote", start);
			}
			case '"' -> {
				this.pos++;
				return readString(start);
			}
			case '|' -> {
				this.pos++;
				return readVerticalLineIdentifier(start);
			}
			case '#' -> {
				return readHash(start);
			}
			default -> {
				return readAtom(start);
			}
		}
	}

	private LispVal abbreviation(String operator, int start) {
		LispVal datum = readDatum();
		if (datum == CLOSE || datum == DOT) {
			throw error("a datum must follow the abbreviation", start);
		}
		return recorded(new LispSymbol(operator), new LispCons(datum, LispNil.INSTANCE), start);
	}

	private LispVal readList(int start) {
		List<LispVal> elements = new ArrayList<>();
		LispVal tail = LispNil.INSTANCE;
		while (true) {
			skipAtmosphere();
			if (this.pos >= this.input.length()) {
				throw eof("unclosed '('", start);
			}
			int at = this.pos;
			LispVal datum = readDatum();
			if (datum == CLOSE) {
				break;
			}
			if (datum == DOT) {
				if (elements.isEmpty()) {
					throw error("a dotted pair needs a datum before the '.'", at);
				}
				tail = readDatum();
				if (tail == CLOSE || tail == DOT) {
					throw error("a dotted pair needs a datum after the '.'", at);
				}
				skipAtmosphere();
				if (this.pos >= this.input.length()) {
					throw eof("unclosed '('", start);
				}
				if (readDatum() != CLOSE) {
					throw error("more than one datum after the '.'", at);
				}
				break;
			}
			elements.add(datum);
		}
		if (elements.isEmpty()) {
			return tail;
		}
		LispVal rest = tail;
		for (int i = elements.size() - 1; i >= 1; i--) {
			rest = new LispCons(elements.get(i), rest);
		}
		return recorded(elements.get(0), rest, start);
	}

	// A list's head cons, recorded as starting at `start`: in the compile path's table,
	// and for the interpreter built as a located cell.
	private LispCons recorded(LispVal car, LispVal cdr, int start) {
		LispCons head = this.runtimeFile != null ? new LocatedCons(car, cdr, this.runtimeFile, lineOf(start))
				: new LispCons(car, cdr);
		this.offsets.put(head, start);
		if (this.unit != null) {
			SourceProvenance.record(head, this.unit, start);
		}
		return head;
	}

	// The 1-based line an offset is on, through a line-start index built once per read.
	private int lineOf(int offset) {
		int[] starts = this.lineStarts;
		if (starts == null) {
			List<Integer> found = new ArrayList<>();
			found.add(0);
			for (int i = 0; i < this.input.length(); i++) {
				if (this.input.charAt(i) == '\n') {
					found.add(i + 1);
				}
			}
			starts = found.stream().mapToInt(Integer::intValue).toArray();
			this.lineStarts = starts;
		}
		int index = java.util.Arrays.binarySearch(starts, offset);
		return (index >= 0 ? index : -index - 2) + 1;
	}

	private LispVal readHash(int start) {
		if (this.pos + 1 >= this.input.length()) {
			throw eof("a lone '#'", start);
		}
		char next = this.input.charAt(this.pos + 1);
		if (next == '(') {
			this.pos += 2;
			return readVector(start);
		}
		if (next == '\\') {
			this.pos += 2;
			return readCharacter(start);
		}
		String token = token();
		return switch (token) {
			case "#t", "#true" -> TRUE;
			case "#f", "#false" -> FALSE;
			default -> {
				if (token.equalsIgnoreCase("#u8") && this.pos < this.input.length()
						&& this.input.charAt(this.pos) == '(') {
					this.pos++;
					yield readBytevector(start);
				}
				LispVal number = token.length() > 2 ? prefixedNumber(token) : null;
				if (number == null) {
					throw error("unsupported '#' syntax: " + token, start);
				}
				yield number;
			}
		};
	}

	private LispVal readVector(int start) {
		List<LispVal> elements = new ArrayList<>();
		while (true) {
			skipAtmosphere();
			if (this.pos >= this.input.length()) {
				throw eof("unclosed '#('", start);
			}
			int at = this.pos;
			LispVal datum = readDatum();
			if (datum == CLOSE) {
				break;
			}
			if (datum == DOT) {
				throw error("a vector cannot be dotted", at);
			}
			elements.add(datum);
		}
		return new LispArray(new int[] { elements.size() }, elements.toArray(new LispVal[0]));
	}

	// #u8( byte* ): an (unsigned-byte 8) packed vector, the representation every backend
	// already has for one. An element is an exact integer in 0..255; anything else is
	// refused here rather than masked by the pack.
	private LispVal readBytevector(int start) {
		List<Long> bytes = new ArrayList<>();
		while (true) {
			skipAtmosphere();
			if (this.pos >= this.input.length()) {
				throw eof("unclosed '#u8('", start);
			}
			int at = this.pos;
			LispVal datum = readDatum();
			if (datum == CLOSE) {
				break;
			}
			if (datum == DOT) {
				throw error("a bytevector cannot be dotted", at);
			}
			if (!(datum instanceof LispInteger(long value)) || value < 0 || value > 255) {
				String spelled = datum instanceof LispSymbol symbol ? symbol.name() : datum.print();
				throw error("a bytevector element must be a byte (0-255): " + spelled, at);
			}
			bytes.add(value);
		}
		byte[] data = new byte[bytes.size()];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) (long) bytes.get(i);
		}
		return LispIntVector.wrapOctets(data);
	}

	private LispVal readCharacter(int start) {
		if (this.pos >= this.input.length()) {
			throw eof("a character must follow '#\\'", start);
		}
		int first = this.input.codePointAt(this.pos);
		this.pos += Character.charCount(first);
		int nameStart = this.pos;
		while (this.pos < this.input.length() && !isDelimiter(this.input.charAt(this.pos))) {
			this.pos++;
		}
		if (this.pos == nameStart) {
			return new LispChar(first);
		}
		String name = new StringBuilder().appendCodePoint(first).append(this.input, nameStart, this.pos).toString();
		// #!fold-case folds the NAME, not the character it names (an unadorned #\A is
		// untouched -- the single-codepoint case above never reaches here).
		String lookup = this.foldCase ? SchemeCharacters.foldcase(name) : name;
		Integer named = CHARACTER_NAMES.get(lookup);
		if (named != null) {
			return new LispChar(named);
		}
		if (lookup.charAt(0) == 'x') {
			try {
				return new LispChar(Integer.parseInt(lookup.substring(1), 16));
			}
			catch (NumberFormatException ex) {
				// falls through to the unknown-name error
			}
		}
		throw error("unknown character name: #\\" + name, start);
	}

	private LispVal readString(int start) {
		StringBuilder value = new StringBuilder();
		while (true) {
			if (this.pos >= this.input.length()) {
				throw eof("unterminated string", start);
			}
			char c = this.input.charAt(this.pos++);
			if (c == '"') {
				return LispString.literal(value.toString());
			}
			if (c != '\\') {
				value.append(c);
				continue;
			}
			if (this.pos >= this.input.length()) {
				throw eof("unterminated string", start);
			}
			char escape = this.input.charAt(this.pos++);
			switch (escape) {
				case 'n' -> value.append('\n');
				case 't' -> value.append('\t');
				case 'r' -> value.append('\r');
				case 'a' -> value.append((char) 0x07);
				case 'b' -> value.append('\b');
				case '"' -> value.append('"');
				case '\\' -> value.append('\\');
				case '|' -> value.append('|');
				case 'x' -> value.appendCodePoint(hexEscape(start));
				default -> {
					if (!lineContinuation()) {
						throw error("unknown string escape: \\" + escape, this.pos - 2);
					}
				}
			}
		}
	}

	// \xHH...; inside a string, the 'x' already consumed.
	private int hexEscape(int start) {
		int end = this.input.indexOf(';', this.pos);
		if (end < 0) {
			throw eof("unterminated \\x escape", start);
		}
		try {
			int codePoint = Integer.parseInt(this.input.substring(this.pos, end), 16);
			this.pos = end + 1;
			return codePoint;
		}
		catch (NumberFormatException ex) {
			throw error("malformed \\x escape", this.pos - 2);
		}
	}

	// \<intraline whitespace>*<newline><intraline whitespace>* contributes nothing.
	// The scan starts AT the character after the backslash, which is already consumed.
	private boolean lineContinuation() {
		int scan = this.pos - 1;
		while (scan < this.input.length() && (this.input.charAt(scan) == ' ' || this.input.charAt(scan) == '\t')) {
			scan++;
		}
		if (scan >= this.input.length() || (this.input.charAt(scan) != '\n' && this.input.charAt(scan) != '\r')) {
			return false;
		}
		if (this.input.charAt(scan) == '\r' && scan + 1 < this.input.length() && this.input.charAt(scan + 1) == '\n') {
			scan++;
		}
		scan++;
		while (scan < this.input.length() && (this.input.charAt(scan) == ' ' || this.input.charAt(scan) == '\t')) {
			scan++;
		}
		this.pos = scan;
		return true;
	}

	private LispVal readAtom(int start) {
		String token = token();
		if (token.equals(".")) {
			return DOT;
		}
		LispVal number = number(token, 10, null);
		if (number != null) {
			return number;
		}
		return new LispSymbol(this.foldCase ? SchemeCharacters.foldcase(token) : token);
	}

	// |...|: any characters, with \| \\ \" \xHH; and the mnemonic escapes of a string.
	// Never case-folded (Gauche leaves |ABC| alone under #!fold-case), and never a number
	// or a dot: |1| and |.| are symbols.
	private LispVal readVerticalLineIdentifier(int start) {
		StringBuilder name = new StringBuilder();
		while (true) {
			if (this.pos >= this.input.length()) {
				throw eof("unterminated '|' identifier", start);
			}
			char c = this.input.charAt(this.pos++);
			if (c == '|') {
				return new LispSymbol(name.toString());
			}
			if (c != '\\') {
				name.append(c);
				continue;
			}
			if (this.pos >= this.input.length()) {
				throw eof("unterminated '|' identifier", start);
			}
			char escape = this.input.charAt(this.pos++);
			switch (escape) {
				case 'n' -> name.append('\n');
				case 't' -> name.append('\t');
				case 'r' -> name.append('\r');
				case 'a' -> name.append((char) 0x07);
				case 'b' -> name.append('\b');
				case '|', '\\', '"' -> name.append(escape);
				case 'x' -> name.appendCodePoint(hexEscape(start));
				default -> throw error("unknown identifier escape: \\" + escape, this.pos - 2);
			}
		}
	}

	private String token() {
		int start = this.pos;
		while (this.pos < this.input.length() && !isDelimiter(this.input.charAt(this.pos))) {
			this.pos++;
		}
		return this.input.substring(start, this.pos);
	}

	private static boolean isDelimiter(char c) {
		return Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == ';' || c == '\'' || c == '`'
				|| c == ',' || c == '|';
	}

	// R7RS <prefix R>: a <radix R> (#x/#b/#o/#d) and an <exactness> (#e/#i), in either
	// order, each at most once. Unconsumed once a pair is unrecognized or repeats a kind
	// already seen -- the leftover '#' then fails digit()/infnan below, so an invalid
	// prefix answers null exactly like an invalid number does.
	private static @Nullable LispVal prefixedNumber(String token) {
		int radix = 10;
		boolean radixSet = false;
		Boolean toInexact = null;
		int index = 0;
		while (index + 1 < token.length() && token.charAt(index) == '#') {
			char c = Character.toLowerCase(token.charAt(index + 1));
			int candidate = switch (c) {
				case 'x' -> 16;
				case 'b' -> 2;
				case 'o' -> 8;
				case 'd' -> 10;
				default -> 0;
			};
			if (candidate != 0) {
				if (radixSet) {
					break;
				}
				radix = candidate;
				radixSet = true;
				index += 2;
				continue;
			}
			if (c == 'e' || c == 'i') {
				if (toInexact != null) {
					break;
				}
				toInexact = c == 'i';
				index += 2;
				continue;
			}
			break;
		}
		return index == 0 ? null : number(token.substring(index), radix, toInexact);
	}

	// toInexact: TRUE for #i (the exact result converts to a flonum), FALSE for #e (a
	// decimal converts to the exact rational/integer it spells rather than a flonum),
	// null when the caller passed neither -- the pre-existing behavior, unchanged.
	private static @Nullable LispVal number(String token, int radix, @Nullable Boolean toInexact) {
		if (token.isEmpty()) {
			return null;
		}
		if (INFINITIES_AND_NANS.contains(token.toLowerCase(Locale.ROOT))) {
			// R7RS <infnan>, in any radix; case is insignificant in a number. A NaN's
			// sign is not kept: every NaN is written +nan.0. An exactness prefix cannot
			// apply to an infinity or a NaN (Gauche leaves it as itself), so toInexact
			// is ignored here.
			if (Character.toLowerCase(token.charAt(1)) == 'n') {
				return new LispDouble(Double.NaN);
			}
			return new LispDouble(token.charAt(0) == '+' ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
		}
		char first = token.charAt(0);
		boolean signed = first == '+' || first == '-';
		if (token.length() == (signed ? 1 : 0)) {
			return null;
		}
		char lead = token.charAt(signed ? 1 : 0);
		if (Character.digit(lead, radix) < 0 && !(radix == 10 && lead == '.')) {
			return null;
		}
		int slash = token.indexOf('/');
		try {
			if (slash > 0) {
				BigInteger denominator = new BigInteger(token.substring(slash + 1), radix);
				if (denominator.signum() <= 0 || token.charAt(slash + 1) == '+') {
					return null;
				}
				LispVal ratio = LispRatio.valueOf(new BigInteger(unsignedPlus(token.substring(0, slash)), radix),
						denominator);
				return applyExactness(ratio, toInexact);
			}
			if (radix != 10 || isDigits(token, signed ? 1 : 0)) {
				return applyExactness(integer(new BigInteger(unsignedPlus(token), radix)), toInexact);
			}
			if (!isDecimal(token, signed ? 1 : 0)) {
				return null;
			}
			if (Boolean.FALSE.equals(toInexact)) {
				// #e over decimal/exponent syntax: the exact rational the digits spell,
				// built from the unscaled value and scale so no double rounding ever
				// touches it (BigDecimal.doubleValue() would).
				return exactDecimal(token);
			}
			// Correctly rounded like BigDecimal.doubleValue(), which would drop the sign
			// of -0.0.
			return new LispDouble(Double.parseDouble(token));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static LispVal exactDecimal(String token) {
		BigDecimal decimal = new BigDecimal(token);
		BigInteger unscaled = decimal.unscaledValue();
		int scale = decimal.scale();
		return scale <= 0 ? integer(unscaled.multiply(BigInteger.TEN.pow(-scale)))
				: LispRatio.valueOf(unscaled, BigInteger.TEN.pow(scale));
	}

	// #i converts an already-exact result to the nearest double; #e (or no exactness
	// prefix) leaves an already-exact result exactly as it is.
	private static LispVal applyExactness(LispVal exact, @Nullable Boolean toInexact) {
		if (!Boolean.TRUE.equals(toInexact)) {
			return exact;
		}
		return new LispDouble(switch (exact) {
			case LispInteger(long value) -> (double) value;
			case LispBigInteger(BigInteger value) -> value.doubleValue();
			case LispRatio ratio -> ratio.doubleValue();
			default -> throw new IllegalStateException("not an exact number: " + exact);
		});
	}

	private static String unsignedPlus(String token) {
		return token.charAt(0) == '+' ? token.substring(1) : token;
	}

	private static LispVal integer(BigInteger value) {
		return value.bitLength() < 64 ? new LispInteger(value.longValueExact()) : new LispBigInteger(value);
	}

	private static boolean isDigits(String token, int from) {
		for (int i = from; i < token.length(); i++) {
			if (token.charAt(i) < '0' || token.charAt(i) > '9') {
				return false;
			}
		}
		return true;
	}

	// digits* [. digits*] [e [sign] digits+], with at least one digit in the mantissa.
	private static boolean isDecimal(String token, int from) {
		int i = from;
		int mantissaDigits = 0;
		while (i < token.length() && Character.isDigit(token.charAt(i))) {
			i++;
			mantissaDigits++;
		}
		if (i < token.length() && token.charAt(i) == '.') {
			i++;
			while (i < token.length() && Character.isDigit(token.charAt(i))) {
				i++;
				mantissaDigits++;
			}
		}
		if (mantissaDigits == 0) {
			return false;
		}
		if (i < token.length() && (token.charAt(i) == 'e' || token.charAt(i) == 'E')) {
			i++;
			if (i < token.length() && (token.charAt(i) == '+' || token.charAt(i) == '-')) {
				i++;
			}
			int exponentStart = i;
			while (i < token.length() && Character.isDigit(token.charAt(i))) {
				i++;
			}
			if (i == exponentStart) {
				return false;
			}
		}
		return i == token.length();
	}

	// Whitespace, ; line comments, #| nested block comments |# and #; datum comments.
	private void skipAtmosphere() {
		while (this.pos < this.input.length()) {
			char c = this.input.charAt(this.pos);
			if (Character.isWhitespace(c)) {
				this.pos++;
			}
			else if (c == ';') {
				while (this.pos < this.input.length() && this.input.charAt(this.pos) != '\n') {
					this.pos++;
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '|') {
				skipBlockComment();
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == ';') {
				int start = this.pos;
				this.pos += 2;
				LispVal skipped = readDatum();
				if (skipped == CLOSE || skipped == DOT) {
					throw error("a datum must follow '#;'", start);
				}
			}
			else if (c == '#' && this.pos + 1 < this.input.length() && this.input.charAt(this.pos + 1) == '!') {
				readDirective();
			}
			else {
				return;
			}
		}
	}

	// #!fold-case / #!no-fold-case: an R7RS <directive>, part of <atmosphere> like a
	// comment -- it produces no datum, only the side effect of toggling case folding
	// for the rest of this file (or until the counterpart directive).
	private void readDirective() {
		int start = this.pos;
		this.pos += 2;
		String word = token();
		switch (word) {
			case "fold-case" -> this.foldCase = true;
			case "no-fold-case" -> this.foldCase = false;
			default -> throw error("unsupported '#' syntax: #!" + word, start);
		}
	}

	private void skipBlockComment() {
		int start = this.pos;
		int depth = 0;
		while (this.pos + 1 < this.input.length()) {
			char c = this.input.charAt(this.pos);
			char next = this.input.charAt(this.pos + 1);
			if (c == '#' && next == '|') {
				depth++;
				this.pos += 2;
			}
			else if (c == '|' && next == '#') {
				depth--;
				this.pos += 2;
				if (depth == 0) {
					return;
				}
			}
			else {
				this.pos++;
			}
		}
		throw eof("unterminated '#|' comment", start);
	}

	private LispReadException error(String message, int offset) {
		return new LispReadException(message, SourceLocation.at(this.file, offset, this.input));
	}

	private LispReadException eof(String message, int offset) {
		return new LispReadException(message, SourceLocation.at(this.file, offset, this.input), true);
	}

}
