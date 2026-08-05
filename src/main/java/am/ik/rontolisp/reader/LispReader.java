package am.ik.rontolisp.reader;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;

/**
 * Parser for Lisp expressions. Converts a list of tokens into LispVal AST nodes.
 */
public final class LispReader {

	private final List<Token> tokens;

	/** The source offset where each {@link #tokens} entry starts, aligned by index. */
	private final int[] offsets;

	private final Features features;

	/** The full source text, used to resolve an offset to a line/column on an error. */
	private final String input;

	/** The origin file, or {@code null} when unknown; used to prefix read errors. */
	private final @Nullable String file;

	/**
	 * The source unit every cons of this read is recorded against, or {@code null} when
	 * the thread is not recording provenance (the interpreter, a runtime {@code read}).
	 * Built once per read: recording is compile-path only, so a non-recording read never
	 * even allocates it.
	 */
	private final SourceProvenance.@Nullable Unit unit;

	private int pos;

	private LispReader(List<LocatedToken> tokens, Features features, String input, @Nullable String file,
			boolean recordProvenance) {
		this.tokens = tokens.stream().map(LocatedToken::token).toList();
		this.offsets = tokens.stream().mapToInt(LocatedToken::offset).toArray();
		this.features = features;
		this.input = input;
		this.file = file;
		this.unit = recordProvenance && SourceProvenance.isRecording() ? new SourceProvenance.Unit(file, input) : null;
		this.pos = 0;
	}

	/**
	 * A {@link LispReadException} whose message is prefixed with the position of the
	 * current token (or the end of input when the reader has run past it).
	 * @param message the error message
	 * @return the positioned exception
	 */
	private LispReadException err(String message) {
		return errAtToken(this.pos, message);
	}

	/**
	 * A {@link LispReadException} positioned at the given token index (or at the end of
	 * input when the index is past the last token). A list that is never closed is only
	 * detected at end of input, so it reports the index of its OPENING token instead: in
	 * a big spliced library the last line of the file says nothing about which paren is
	 * unbalanced.
	 * @param tokenIndex the index into {@link #tokens} to report
	 * @param message the error message
	 * @return the positioned exception
	 */
	private LispReadException errAtToken(int tokenIndex, String message) {
		SourceLocation location = tokenIndex >= 0 && tokenIndex < this.offsets.length
				? SourceLocation.at(this.file, this.offsets[tokenIndex], this.input)
				: SourceLocation.at(this.file, this.input.length(), this.input);
		return new LispReadException(message, location);
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
	 * Read a single expression from the input string with the given feature set (the
	 * runtime {@code read}/{@code read-from-string} built-ins pass the evaluator's
	 * per-run features so the upcase reader mode applies there too).
	 * @param input the source code string
	 * @param features the active reader features
	 * @return the parsed expression
	 */
	public static LispVal readFromString(String input, Features features) {
		List<LispVal> exprs = readAllFromString(input, features);
		if (exprs.isEmpty()) {
			return LispNil.INSTANCE;
		}
		return exprs.get(0);
	}

	/**
	 * Read a single expression from the input string, wrapping each {@code #.}
	 * read-time-eval datum in a {@code (%read-eval datum)} marker (see
	 * {@link #readAllWithReadEvalMarkers}). The runtime {@code read} /
	 * {@code read-from-string} built-ins use this when the evaluator has installed its
	 * marker resolver, so {@code #.} in runtime-read data evaluates like CL's
	 * {@code read} under a true {@code *read-eval*}.
	 * @param input the source code string
	 * @param features the active reader features
	 * @return the parsed expression
	 */
	public static LispVal readFromStringWithReadEvalMarkers(String input, Features features) {
		List<LispVal> exprs = readAllWithReadEvalMarkers(input, features);
		if (exprs.isEmpty()) {
			return LispNil.INSTANCE;
		}
		return exprs.get(0);
	}

	/**
	 * Read all expressions from the input string with the interpreter feature set.
	 * @param input the source code string
	 * @return the list of parsed expressions
	 */
	public static List<LispVal> readAllFromString(String input) {
		return readAllFromString(input, Features.INTERPRETER);
	}

	/**
	 * Read all expressions from the input string. The feature set drives the
	 * {@code #+}/{@code #-} conditionals and the {@code *features*} substitution, so the
	 * compile path passes the target backend's features.
	 * @param input the source code string
	 * @param features the active reader features
	 * @return the list of parsed expressions
	 */
	public static List<LispVal> readAllFromString(String input, Features features) {
		return readAll(input, features, LispLexer.ReadEvalMode.ERROR, null);
	}

	/**
	 * Read all expressions from the input string, prefixing any read error with the given
	 * origin file (the entry source or a loaded file). The feature set drives the
	 * {@code #+}/{@code #-} conditionals and the {@code *features*} substitution.
	 * @param input the source code string
	 * @param features the active reader features
	 * @param file the origin file, or {@code null} when unknown
	 * @return the list of parsed expressions
	 */
	public static List<LispVal> readAllFromString(String input, Features features, @Nullable String file) {
		return readAll(input, features, LispLexer.ReadEvalMode.ERROR, file);
	}

	/**
	 * Read all expressions from the input string, skipping {@code #.} read-time-eval
	 * forms with a warning instead of erroring. Used for {@code .asd} files, whose
	 * leading {@code #.} version guards would otherwise make the whole file unreadable.
	 * @param input the source code string
	 * @param features the active reader features
	 * @return the list of parsed expressions
	 */
	public static List<LispVal> readAllSkippingReadEval(String input, Features features) {
		return readAll(input, features, LispLexer.ReadEvalMode.SKIP_UNREADABLE, null);
	}

	/**
	 * Read all expressions from the input string, skipping {@code #.} read-time-eval
	 * forms with a warning instead of erroring. Used for {@code .asd} files, whose
	 * leading {@code #.} version guards would otherwise make the whole file unreadable.
	 * @param input the source code string
	 * @param features the active reader features
	 * @param file the origin file, or {@code null} when unknown
	 * @return the list of parsed expressions
	 */
	public static List<LispVal> readAllSkippingReadEval(String input, Features features, @Nullable String file) {
		return readAll(input, features, LispLexer.ReadEvalMode.SKIP_UNREADABLE, file);
	}

	/**
	 * Read all expressions from the input string, wrapping each {@code #.} read-time-eval
	 * datum in a {@code (%read-eval datum)} marker instead of erroring. The consumer (the
	 * evaluator's {@code load}) resolves each marker by evaluating the datum just before
	 * the top-level form containing it is evaluated, so a marker sees the definitions of
	 * every preceding top-level form -- CL's read-eval timing for a form-at-a-time load.
	 * An unreadable datum is a read error.
	 * @param input the source code string
	 * @param features the active reader features
	 * @return the list of parsed expressions
	 */
	public static List<LispVal> readAllWithReadEvalMarkers(String input, Features features) {
		return readAll(input, features, LispLexer.ReadEvalMode.MARKER, null);
	}

	/**
	 * Read all expressions from the input string, wrapping each {@code #.} read-time-eval
	 * datum in a {@code (%read-eval datum)} marker instead of erroring. The consumer (the
	 * evaluator's {@code load}) resolves each marker by evaluating the datum just before
	 * the top-level form containing it is evaluated, so a marker sees the definitions of
	 * every preceding top-level form -- CL's read-eval timing for a form-at-a-time load.
	 * An unreadable datum is a read error.
	 * @param input the source code string
	 * @param features the active reader features
	 * @param file the origin file, or {@code null} when unknown
	 * @return the list of parsed expressions
	 */
	public static List<LispVal> readAllWithReadEvalMarkers(String input, Features features, @Nullable String file) {
		return readAll(input, features, LispLexer.ReadEvalMode.MARKER, file);
	}

	private static List<LispVal> readAll(String input, Features features, LispLexer.ReadEvalMode readEvalMode,
			@Nullable String file) {
		List<LocatedToken> tokens = new LispLexer(input, features, readEvalMode, file).tokenizeWithPositions();
		LispReader reader = new LispReader(tokens, features, input, file, true);
		List<LispVal> result = new ArrayList<>();
		while (reader.pos < reader.tokens.size()) {
			result.add(reader.readExpr());
		}
		return result;
	}

	/**
	 * Returns whether {@code tokens} parse cleanly as a sequence of expressions. The
	 * tolerant lexer uses this to validate a re-lexed {@code #.} datum before wrapping it
	 * in a {@code %read-eval} marker: a datum that lexes but does not parse (e.g. an
	 * unquote outside a backquote) must fall back to the skipped-with-warning placeholder
	 * instead of poisoning the whole file's token stream.
	 * @param tokens the tokens to validate
	 * @param features the active reader features
	 * @return {@code true} if the tokens parse as complete expressions
	 */
	static boolean parsesAsExpressions(List<LocatedToken> tokens, Features features) {
		try {
			// A validation-only parse: it holds no source text of its own and its
			// conses are thrown away, so it must not put a bogus position in the
			// provenance table (an entry there is first-write-wins).
			LispReader reader = new LispReader(tokens, features, "", null, false);
			while (reader.pos < reader.tokens.size()) {
				reader.readExpr();
			}
			return true;
		}
		catch (LispReadException ex) {
			return false;
		}
	}

	/**
	 * Reads one datum and, when this thread is recording provenance, records the cons it
	 * produced against the offset the datum STARTS at. Only the outermost cons of a datum
	 * is recorded here -- every nested one is produced by its own {@code readExpr} call,
	 * so a list's elements each get their own position while the cells that merely chain
	 * them share their element's line, which is where an error about that element points
	 * anyway.
	 * @return the datum
	 */
	private LispVal readExpr() {
		if (this.unit == null) {
			return readDatum();
		}
		int start = this.pos;
		LispVal datum = readDatum();
		if (datum instanceof LispCons cons) {
			// A datum that returns has consumed at least one token, so `start` indexes a
			// real one; the end-of-input fallback only exists so a future caller cannot
			// turn a diagnostic into an AIOOBE.
			SourceProvenance.record(cons, this.unit,
					start < this.offsets.length ? this.offsets[start] : this.input.length());
		}
		return datum;
	}

	private LispVal readDatum() {
		if (this.pos >= this.tokens.size()) {
			throw err("Unexpected end of input");
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
			case Token.BitVectorToken bits -> readBitVector(bits.bits());
			case Token.ArrayOpen array -> readArray(array.rank());
			case Token.StructOpen ignored -> readStruct();
			case Token.FloatArrayOpen open -> readFloatArray(open.single());
			case Token.IntVectorOpen open -> readIntVector(open.width());
			case Token.Quote ignored -> readQuote();
			case Token.FunctionQuote ignored -> readFunctionQuote();
			case Token.Backquote ignored -> readBackquote();
			case Token.Unquote ignored -> throw err("Comma is illegal outside of backquote");
			case Token.UnquoteSplicing ignored -> throw err(",@ is illegal outside of backquote");
			case Token.RightParen ignored -> throw err("Unexpected ')'");
			case Token.Dot ignored -> throw err("Unexpected '.'");
			case Token.Eof ignored -> throw err("Unexpected end of input");
			case Token.LabelDef def -> {
				// #n=: record the next datum under the label. Lite: no circular
				// structures -- a #n# inside the labeled datum itself is unresolvable.
				LispVal datum = readExpr();
				this.labels.put(def.label(), datum);
				yield datum;
			}
			case Token.LabelRef ref -> {
				LispVal datum = this.labels.get(ref.label());
				if (datum == null) {
					throw err("#" + ref.label() + "# references an undefined (or circular) reader label");
				}
				yield datum;
			}
		};
	}

	/** The datums recorded by {@code #n=} reader labels, shared across the read. */
	private final java.util.Map<Integer, LispVal> labels = new java.util.HashMap<>();

	private LispVal readRatio(Token.RatioToken ratio) {
		if (ratio.denominator().signum() == 0) {
			throw err("Division by zero in ratio literal: " + ratio.numerator() + "/0");
		}
		// Normalization may demote to an integer (e.g., "4/2" reads as 2).
		return LispRatio.valueOf(ratio.numerator(), ratio.denominator());
	}

	private LispVal readSymbol(Token.SymbolToken sym) {
		String name = sym.name();
		if ("NIL".equals(name)) {
			return LispNil.INSTANCE;
		}
		if ("T".equals(name)) {
			return LispTrue.INSTANCE;
		}
		if ("PI".equals(name)) {
			// The mathematical constant pi, read as a self-evaluating double like
			// nil/t. This gives all three backends parity for free.
			return new LispDouble(Math.PI);
		}
		if ("MOST-POSITIVE-FIXNUM".equals(name) || "MOST-NEGATIVE-FIXNUM".equals(name)) {
			// The fixnum range constants, read as self-evaluating integers like pi.
			// The value is backend-dependent (fixed at read time like *features*):
			// WASM fixnums are unboxed i31 references, the interpreter and the JVM
			// backend use Java longs.
			boolean wasm = this.features.contains("rontolisp-wasm");
			long value = name.startsWith("MOST-POSITIVE") ? (wasm ? (1L << 30) - 1 : Long.MAX_VALUE)
					: (wasm ? -(1L << 30) : Long.MIN_VALUE);
			return new LispInteger(value);
		}
		if (LispNames.ARRAY_DIMENSION_LIMIT.equals(name) || LispNames.ARRAY_TOTAL_SIZE_LIMIT.equals(name)) {
			// The array-dimension-limit constant, read like most-positive-fixnum: the
			// interpreter/JVM value matches the interpreter's global binding, WASM stays
			// inside the i31 fixnum range.
			boolean wasm = this.features.contains("rontolisp-wasm");
			return new LispInteger(wasm ? (1L << 30) - 1 : 2147483639L);
		}
		if (LispNames.CHAR_CODE_LIMIT.equals(name)) {
			// The char-code-limit constant, read like array-dimension-limit. char-code
			// returns full Unicode code points on every backend (and the value fits the
			// WASM i31 fixnum range), so one value serves all of them.
			return new LispInteger(0x110000);
		}
		if (LispNames.INTERNAL_TIME_UNITS_PER_SECOND.equals(name)) {
			// The internal-time-units-per-second constant, read like char-code-limit:
			// every backend's get-internal-real-time counts milliseconds.
			return new LispInteger(1000);
		}
		if (LispNames.LAMBDA_LIST_KEYWORDS.equals(name)) {
			// The lambda-list-keywords constant, substituted like *features*: a quoted
			// list of the &-symbols (alexandria's parse-ordinary-lambda-list walks it).
			// &whole/&environment are included -- they name positions the reader knows,
			// even where a consumer's support for them is partial.
			LispVal list = LispNil.INSTANCE;
			List<String> keywords = List.of("&ALLOW-OTHER-KEYS", "&AUX", "&BODY", "&ENVIRONMENT", "&KEY", "&OPTIONAL",
					"&REST", "&WHOLE");
			for (int i = keywords.size() - 1; i >= 0; i--) {
				list = new LispCons(new LispSymbol(keywords.get(i)), list);
			}
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(list, LispNil.INSTANCE));
		}
		if (LispNames.FEATURES_VAR.equals(name) && this.features.substituteFeaturesVar()) {
			// The active feature list, substituted at read time like pi: a quoted
			// list of keywords, so a compiled program's feature set is fixed at compile
			// time -- (setq *features* ...) is not supported there. The interpreter
			// skips the substitution and binds *features* as a global variable instead
			// (see Features.substituteFeaturesVar).
			LispVal list = LispNil.INSTANCE;
			List<String> names = this.features.names();
			for (int i = names.size() - 1; i >= 0; i--) {
				list = new LispCons(new LispSymbol(":" + names.get(i).toUpperCase(java.util.Locale.ROOT)), list);
			}
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(list, LispNil.INSTANCE));
		}
		LispVal sourceLiteral = sourceLiteral(name);
		if (sourceLiteral != null) {
			return sourceLiteral;
		}
		return new LispSymbol(name);
	}

	/**
	 * The value of {@code rontolisp:current-file} / {@code rontolisp:current-line} at
	 * THIS symbol's own position, or {@code null} when the name is neither.
	 *
	 * <p>
	 * Substituted here, next to {@code pi} and {@code *features*}, because the reader is
	 * the only place that knows where each occurrence stands AND is shared by the
	 * interpreter and all four backends -- so one implementation gives them identical
	 * values by construction, and no backend sees anything but a string and an integer.
	 * The consequence is that these are READ-time literals: inside a {@code defmacro}
	 * template they name the macro's own definition site, not its call site, so a logging
	 * macro takes them as arguments at the call site (see
	 * {@code .kb/source-positions.md}).
	 * @param name the symbol name as read (upcased, package prefix intact)
	 * @return the literal, or {@code null} when the symbol is not one of the two
	 */
	private @Nullable LispVal sourceLiteral(String name) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		// Qualified spellings only (rontolisp: / rontolisp:: / the rl: nickname): the
		// reader runs before any in-package tracking, so a bare CURRENT-FILE cannot be
		// known to mean this one -- and a namespaced symbol is the point (no new CL
		// surface).
		if (qualified == null
				|| !LispNames.RONTOLISP_PKG.equals(PackageRegistry.canonicalBuiltinName(qualified.pkg()))) {
			return null;
		}
		if (LispNames.CURRENT_FILE.equals(qualified.member())) {
			return this.file == null ? LispNil.INSTANCE : new LispString(this.file);
		}
		if (LispNames.CURRENT_LINE.equals(qualified.member())) {
			// pos - 1 is this symbol's own token: readDatum consumed it before
			// dispatching.
			return new LispInteger(SourceLocation.at(this.file, this.offsets[this.pos - 1], this.input).line());
		}
		return null;
	}

	private LispVal readList() {
		// The '(' was consumed by the caller, so it is the token just behind us -- the
		// position an unclosed list must report.
		int open = this.pos - 1;
		if (this.pos >= this.tokens.size()) {
			throw errAtToken(open, "Unexpected end of input, expected ')'");
		}
		if (this.tokens.get(this.pos) instanceof Token.RightParen) {
			this.pos++; // consume ')'
			return LispNil.INSTANCE;
		}
		List<LispVal> elements = new ArrayList<>();
		LispVal tail = LispNil.INSTANCE;
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			if (this.tokens.get(this.pos) instanceof Token.Dot) {
				// Dotted pair: (a . b) puts b directly in the final cdr.
				if (elements.isEmpty()) {
					throw err("Nothing appears before '.' in list");
				}
				this.pos++; // consume '.'
				tail = readExpr();
				if (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
					throw err("More than one object follows '.' in list");
				}
				break;
			}
			elements.add(readExpr());
		}
		if (this.pos >= this.tokens.size()) {
			throw errAtToken(open, "Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		// Build cons chain from right to left
		LispVal result = tail;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

	// Reads a rank-1 vector literal #(e1 e2 ... en) into a self-evaluating LispArray.
	// The elements are read as ordinary data (not evaluated), matching Common Lisp.
	private LispVal readVector() {
		List<LispVal> elements = readGroupedElements();
		return new LispArray(new int[] { elements.size() }, elements.toArray(new LispVal[0]));
	}

	// Builds a #*1010 bit-vector literal: the general vector holding the integers 0/1
	// (rontolisp has no packed bit representation, so sbit/aref read it uniformly).
	private LispVal readBitVector(String bits) {
		LispVal[] elements = new LispVal[bits.length()];
		for (int i = 0; i < bits.length(); i++) {
			elements[i] = new LispInteger(bits.charAt(i) - '0');
		}
		return new LispArray(new int[] { elements.length }, elements);
	}

	// Reads a rank-n array literal #nA((...) ...) into a self-evaluating LispArray.
	// The contents are nested lists of depth n whose leaves are the elements in
	// row-major order; every list at the same depth must have the same length
	// (ragged contents are a read error), matching Common Lisp.
	private LispVal readArray(int rank) {
		if (rank < 1) {
			throw err("#" + rank + "A: array rank must be >= 1");
		}
		List<LispVal> rows = readGroupedElements();
		String label = "#" + rank + "A";
		int[] dims = arrayDimensions(rows, rank, label);
		List<LispVal> flat = new ArrayList<>();
		flattenArrayContents(rows, 0, dims, label, flat);
		return new LispArray(dims, flat.toArray(new LispVal[0]));
	}

	// Reads a #f(...) / #d(...) packed float-array literal into a self-evaluating packed
	// array: #f( yields a single-float (LispSingleFloatArray, f32 backing) and #d( a
	// double-float (LispDoubleFloatArray, f64 backing). Every leaf is coerced to a double
	// (narrowed to a float for #f), and the rank is inferred from the nesting depth
	// (numpy
	// style): #f(1.0 2.0) is rank-1, #f((1.0 2.0) (3.0 4.0)) is rank-2, and so on. Leaves
	// must be real numbers and every level must be uniformly shaped (ragged or mixed
	// number/list contents are a read error), reusing the #nA validation. #f = "float"
	// (single), #d = "double"; free in CL, not Scheme's #f=false.
	private LispVal readFloatArray(boolean single) {
		String label = single ? "#f" : "#d";
		List<LispVal> rows = readGroupedElements();
		int rank = inferFloatArrayRank(rows);
		int[] dims = arrayDimensions(rows, rank, label);
		List<LispVal> flat = new ArrayList<>();
		flattenArrayContents(rows, 0, dims, label, flat);
		if (single) {
			float[] data = new float[flat.size()];
			for (int i = 0; i < data.length; i++) {
				data[i] = (float) coerceFloatLeaf(flat.get(i));
			}
			return new LispSingleFloatArray(data, dims);
		}
		double[] data = new double[flat.size()];
		for (int i = 0; i < data.length; i++) {
			data[i] = coerceFloatLeaf(flat.get(i));
		}
		return new LispDoubleFloatArray(data, dims);
	}

	// Reads a #N@(...) packed integer-vector literal (ironclad's array-reader syntax)
	// into
	// a self-evaluating rank-1 LispIntVector of the given width. Leaves must be integers;
	// each is masked to the width (the packed store semantics).
	private LispVal readIntVector(int width) {
		List<LispVal> elements = readGroupedElements();
		long[] data = new long[elements.size()];
		for (int i = 0; i < data.length; i++) {
			LispVal leaf = elements.get(i);
			data[i] = switch (leaf) {
				case LispInteger n -> n.value();
				case am.ik.rontolisp.LispBigInteger b -> b.value().longValue();
				default -> throw err("#" + width + "@: expected an integer element, got " + leaf.print());
			};
		}
		return new am.ik.rontolisp.LispIntVector(width, data);
	}

	// Reads a #S(NAME :SLOT value ...) structure literal into a LispStructLiteral
	// carrier.
	// Everything the reader can decide on its own is decided here -- the type name must
	// be
	// a symbol and the slot names/values must pair up -- while whether the type exists
	// and
	// whether it has those slots needs the ClosRegistry, so it is left to the fold
	// (StructLiteralFolder), which runs per top-level form on every backend. Contents are
	// read as DATA: (make-point) inside a #S stays the literal list, matching Common
	// Lisp.
	private LispVal readStruct() {
		List<LispVal> items = readGroupedElements();
		if (items.isEmpty()) {
			throw err("#S(): a structure literal needs a type name");
		}
		if (!(items.get(0) instanceof LispSymbol nameSym)) {
			throw err("#S: expected a structure type name, got " + items.get(0).print());
		}
		String typeName = nameSym.name();
		if ((items.size() - 1) % 2 != 0) {
			throw err("#S(" + typeName + " ...): odd number of slot name/value items in a structure literal");
		}
		List<String> slotNames = new ArrayList<>();
		List<LispVal> slotValues = new ArrayList<>();
		for (int i = 1; i < items.size(); i += 2) {
			if (!(items.get(i) instanceof LispSymbol slotSym)) {
				throw err("#S(" + typeName + " ...): expected a slot name, got " + items.get(i).print());
			}
			slotNames.add(slotSym.name());
			slotValues.add(items.get(i + 1));
		}
		return new am.ik.rontolisp.LispStructLiteral(typeName, slotNames, slotValues);
	}

	// Reads the elements of a #(...)/#nA(...)/#f(...) literal up to the closing ')'.
	private List<LispVal> readGroupedElements() {
		int open = this.pos - 1;
		List<LispVal> rows = new ArrayList<>();
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			rows.add(readExpr());
		}
		if (this.pos >= this.tokens.size()) {
			throw errAtToken(open, "Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		return rows;
	}

	// The dimension sizes for a rank-n literal, taken from the first-element chain; an
	// empty level makes every remaining dimension 0 (e.g., #2A() has dimensions (0 0)).
	private int[] arrayDimensions(List<LispVal> rows, int rank, String label) {
		int[] dims = new int[rank];
		dims[0] = rows.size();
		List<LispVal> level = rows;
		for (int d = 1; d < rank; d++) {
			level = level.isEmpty() ? List.of() : arrayLevelContents(level.get(0), label);
			dims[d] = level.size();
		}
		return dims;
	}

	// The rank of a #f literal: 1 when the top level holds numbers (leaves), one more per
	// nested-list level. An empty top level (#f()) is rank-1; an empty nested level caps
	// the rank there (#f(()) is rank-2 with dimensions (1 0)).
	private static int inferFloatArrayRank(List<LispVal> rows) {
		int rank = 1;
		if (!rows.isEmpty()) {
			LispVal probe = rows.get(0);
			while (probe instanceof LispCons || probe instanceof LispNil) {
				rank++;
				if (probe instanceof LispCons cons) {
					probe = cons.car();
				}
				else {
					break; // empty nested level: cannot descend further
				}
			}
		}
		return rank;
	}

	// Coerces a #f/#d leaf to a double, or a read error when it is not a real number.
	private double coerceFloatLeaf(LispVal leaf) {
		return switch (leaf) {
			case LispDouble d -> d.value();
			case LispInteger i -> (double) i.value();
			case LispBigInteger b -> b.value().doubleValue();
			case LispRatio r -> r.doubleValue();
			default -> throw err("packed float array: expected a number, got " + leaf.print());
		};
	}

	// Validates one level of #nA/#f contents against the expected dimension and appends
	// the elements to `out` in row-major order.
	private void flattenArrayContents(List<LispVal> items, int depth, int[] dims, String label, List<LispVal> out) {
		if (items.size() != dims[depth]) {
			throw err(label + ": ragged contents, expected " + dims[depth] + " elements, got " + items.size());
		}
		if (depth == dims.length - 1) {
			out.addAll(items);
			return;
		}
		for (LispVal item : items) {
			flattenArrayContents(arrayLevelContents(item, label), depth + 1, dims, label, out);
		}
	}

	// Converts one nested level of #nA/#f contents (a proper list or nil) to its
	// elements.
	private List<LispVal> arrayLevelContents(LispVal level, String label) {
		if (level instanceof LispNil) {
			return List.of();
		}
		if (!(level instanceof LispCons)) {
			throw err(label + ": expected a nested list, got " + level.print());
		}
		List<LispVal> items = new ArrayList<>();
		LispVal tail = level;
		while (tail instanceof LispCons cons) {
			items.add(cons.car());
			tail = cons.cdr();
		}
		if (!(tail instanceof LispNil)) {
			throw err(label + ": contents must be proper lists");
		}
		return items;
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
	// A non-nested template uses the optimized single-level expander below
	// (readTemplateElement/readTemplateList). A template that contains an inner
	// backquote is routed through the CLtL2/Steele nested-backquote algorithm
	// (bqCompletelyProcess) which fully expands every level at read time into the
	// same list/append/cons/quote primitives -- an inner ` increments the
	// quasiquote level, ,/,@ decrement it, only unquotes that reach level 0 are
	// evaluated, deeper ones are re-quoted as list-building code (so no runtime
	// quasiquote marker is left). Because expansion happens entirely in the reader,
	// the runtime `read` of compiled programs does not understand the backquote
	// character.

	/** One expanded template element and whether it splices into the enclosing list. */
	private record TemplateElement(LispVal form, boolean splicing) {
	}

	private boolean rawSawNestedBackquote;

	private LispVal readBackquote() {
		// First read the template as a raw marker tree to detect whether it nests an
		// inner backquote. Non-nested templates keep the optimized single-level
		// expansion (and its exact output shape); nested ones go through CLtL2.
		int save = this.pos;
		this.rawSawNestedBackquote = false;
		LispVal raw = readRawTemplate();
		if (this.rawSawNestedBackquote) {
			return bqCompletelyProcess(raw);
		}
		this.pos = save;
		TemplateElement element = readTemplateElement();
		if (element.splicing()) {
			throw err(",@ must appear inside a list in a backquote template");
		}
		return element.form();
	}

	private TemplateElement readTemplateElement() {
		if (this.pos >= this.tokens.size()) {
			throw err("Unexpected end of input in backquote template");
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
			case Token.Backquote ignored -> throw err("Nested backquote is not supported");
			case Token.LeftParen ignored -> {
				// A (%read-eval datum) marker (the #. wrapping in marker read mode) must
				// not be split into template list-construction code: keep it whole as a
				// call, RENAMED to the %read-eval-template variant -- the load-time
				// substitution then knows the value is template DATA and wraps it in
				// quote (a raw cons value in construction-code position would be
				// evaluated as a call: cl-postgres's `#.*optimize*` inside a declare
				// template). An unresolved occurrence still evaluates as the identity,
				// deferring the read-time evaluation to macro-expansion time.
				if (this.pos + 1 < this.tokens.size() && this.tokens.get(this.pos + 1) instanceof Token.SymbolToken sym
						&& LispNames.READ_EVAL.equals(sym.name())) {
					LispVal marker = readExpr();
					if (marker instanceof LispCons markerCons) {
						marker = new LispCons(new LispSymbol(LispNames.READ_EVAL_TEMPLATE), markerCons.cdr());
					}
					yield new TemplateElement(marker, false);
				}
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
		LispVal quoteSym = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(operator), LispNil.INSTANCE));
		if (inner.splicing()) {
			// ',@xs is the template (quote ,@xs), i.e. construction code
			// (cons 'quote xs): with the customary single-element splice the result
			// reads back as 'x (trivia level0's `(equal ,*what* ',@args)).
			return new LispCons(new LispSymbol(LispNames.CONS),
					new LispCons(quoteSym, new LispCons(inner.form(), LispNil.INSTANCE)));
		}
		return new LispCons(new LispSymbol(LispNames.LIST),
				new LispCons(quoteSym, new LispCons(inner.form(), LispNil.INSTANCE)));
	}

	private LispVal readTemplateList() {
		int open = this.pos - 1;
		List<TemplateElement> elements = new ArrayList<>();
		TemplateElement tail = null;
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			if (this.tokens.get(this.pos) instanceof Token.Dot) {
				// Dotted tail: `(a . ,b) lowers to nested cons forms.
				if (elements.isEmpty()) {
					throw err("Nothing appears before '.' in backquote template");
				}
				this.pos++; // consume '.'
				tail = readTemplateElement();
				if (tail.splicing()) {
					throw err(",@ cannot follow '.' in a backquote template");
				}
				if (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
					throw err("More than one object follows '.' in backquote template");
				}
				break;
			}
			elements.add(readTemplateElement());
		}
		if (this.pos >= this.tokens.size()) {
			throw errAtToken(open, "Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		return buildTemplateList(elements, tail);
	}

	private static LispVal buildTemplateList(List<TemplateElement> elements, @Nullable TemplateElement tail) {
		boolean anySplicing = elements.stream().anyMatch(TemplateElement::splicing);
		if (tail != null && !anySplicing) {
			// (cons f1 (cons f2 ... tail))
			LispVal result = tail.form();
			for (int i = elements.size() - 1; i >= 0; i--) {
				result = properList(new LispSymbol(LispNames.CONS), List.of(elements.get(i).form(), result));
			}
			return result;
		}
		if (elements.isEmpty()) {
			return LispNil.INSTANCE;
		}
		if (!anySplicing) {
			// (list f1 ... fn)
			return properList(new LispSymbol(LispNames.LIST), elements.stream().map(TemplateElement::form).toList());
		}
		// (append seg1 ... segk [tail]): each splicing element is its own segment, runs
		// of non-splicing elements collapse into (list f...) segments, and a dotted
		// tail is the last append argument (CLHS 2.4.6.1: `(x1 ... xn . tail) is
		// (append [x1] ... [xn] tail) -- trivia level2's
		// `((,head ,@(mappend #'car pairs) . ,(cdr (last args))))).
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
		if (tail != null) {
			segments.add(tail.form());
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

	// --- Nested backquote: CLtL2/Steele algorithm -------------------------------
	//
	// A faithful port of the public-domain backquote implementation from CLtL2
	// Appendix C (Guy L. Steele Jr.). The template is first read into a raw marker
	// tree (readRawTemplate), then bqCompletelyProcess lowers it to list/append/
	// cons/list*/quote forms. Nested backquotes and multiple comma levels are
	// handled by the recursive BACKQUOTE case (double processing): every level is
	// expanded at read time, so nothing survives to run time.
	//
	// The tokens below are unique sentinel symbols compared by identity (==), never
	// by name, so they cannot clash with user symbols of the same spelling.

	private static final LispSymbol BQ_COMMA = new LispSymbol("%bq-comma");

	private static final LispSymbol BQ_COMMA_AT = new LispSymbol("%bq-comma-at");

	private static final LispSymbol BQ_COMMA_DOT = new LispSymbol("%bq-comma-dot");

	private static final LispSymbol BQ_BACKQUOTE = new LispSymbol("%bq-backquote");

	private static final LispSymbol BQ_LIST = new LispSymbol("%bq-list");

	private static final LispSymbol BQ_APPEND = new LispSymbol("%bq-append");

	private static final LispSymbol BQ_LIST_STAR = new LispSymbol("%bq-list*");

	private static final LispSymbol BQ_NCONC = new LispSymbol("%bq-nconc");

	private static final LispSymbol BQ_CLOBBERABLE = new LispSymbol("%bq-clobberable");

	private static final LispSymbol BQ_QUOTE = new LispSymbol("%bq-quote");

	// Reads one datum of a backquote template into the raw marker representation,
	// preserving `/,/,@ as BACKQUOTE/COMMA/COMMA-AT marker conses so the CLtL2
	// processor can walk the structure. Sets rawSawNestedBackquote if it reads an
	// inner backquote (the signal that the optimized path cannot be used).
	private LispVal readRawTemplate() {
		if (this.pos >= this.tokens.size()) {
			throw err("Unexpected end of input in backquote template");
		}
		Token token = this.tokens.get(this.pos);
		switch (token) {
			case Token.Backquote ignored -> {
				this.pos++;
				this.rawSawNestedBackquote = true;
				return list2(BQ_BACKQUOTE, readRawTemplate());
			}
			case Token.Unquote ignored -> {
				this.pos++;
				return list2(BQ_COMMA, readRawTemplate());
			}
			case Token.UnquoteSplicing ignored -> {
				this.pos++;
				return list2(BQ_COMMA_AT, readRawTemplate());
			}
			case Token.LeftParen ignored -> {
				this.pos++;
				return readRawList();
			}
			case Token.Quote ignored -> {
				this.pos++;
				return list2(new LispSymbol(LispNames.QUOTE), readRawTemplate());
			}
			case Token.FunctionQuote ignored -> {
				this.pos++;
				return list2(new LispSymbol(LispNames.FUNCTION), readRawTemplate());
			}
			// Any other token (atoms, vectors, arrays) is constant template data:
			// read it with the ordinary reader. Commas inside a vector literal are
			// therefore unsupported, matching the single-level path.
			default -> {
				return readExpr();
			}
		}
	}

	// Reads the elements of a raw template list, preserving a dotted tail.
	private LispVal readRawList() {
		int open = this.pos - 1;
		List<LispVal> elements = new ArrayList<>();
		LispVal tail = LispNil.INSTANCE;
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			if (this.tokens.get(this.pos) instanceof Token.Dot) {
				if (elements.isEmpty()) {
					throw err("Nothing appears before '.' in backquote template");
				}
				this.pos++; // consume '.'
				tail = readRawTemplate();
				if (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
					throw err("More than one object follows '.' in backquote template");
				}
				break;
			}
			elements.add(readRawTemplate());
		}
		if (this.pos >= this.tokens.size()) {
			throw errAtToken(open, "Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		LispVal result = tail;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

	// bq-completely-process: process then simplify then strip sentinel tokens.
	private LispVal bqCompletelyProcess(LispVal x) {
		return bqRemoveTokens(bqSimplify(bqProcess(x)));
	}

	// bq-process: remove one level of backquote, producing code in terms of the
	// sentinel list/append markers. The BACKQUOTE case recursively completely
	// processes an inner backquote and then re-processes the result, which is how
	// a deeper comma level survives one round and is consumed by the next.
	private LispVal bqProcess(LispVal x) {
		if (!(x instanceof LispCons)) {
			return list2(BQ_QUOTE, x);
		}
		LispVal head = car(x);
		if (head == BQ_BACKQUOTE) {
			return bqProcess(bqCompletelyProcess(cadr(x)));
		}
		if (head == BQ_COMMA) {
			return bqExpandEscaped(cadr(x));
		}
		if (head == BQ_COMMA_AT) {
			throw err(",@ has no enclosing list in a backquote template");
		}
		if (head == BQ_COMMA_DOT) {
			throw err(",. has no enclosing list in a backquote template");
		}
		List<LispVal> segments = new ArrayList<>();
		LispVal p = x;
		while (true) {
			if (!(p instanceof LispCons)) {
				segments.add(list2(BQ_QUOTE, p));
				return cons(BQ_APPEND, fromList(segments));
			}
			LispVal ph = car(p);
			if (ph == BQ_COMMA) {
				if (!(cddr(p) instanceof LispNil)) {
					throw err("Malformed ,");
				}
				segments.add(bqExpandEscaped(cadr(p)));
				return cons(BQ_APPEND, fromList(segments));
			}
			if (ph == BQ_COMMA_AT) {
				throw err("Dotted ,@ in a backquote template");
			}
			if (ph == BQ_COMMA_DOT) {
				throw err("Dotted ,. in a backquote template");
			}
			segments.add(bracket(car(p)));
			p = cdr(p);
		}
	}

	// bracket: process one list element into an append segment.
	private LispVal bracket(LispVal x) {
		if (!(x instanceof LispCons)) {
			return list2(BQ_LIST, bqProcess(x));
		}
		LispVal head = car(x);
		if (head == BQ_COMMA) {
			return list2(BQ_LIST, bqExpandEscaped(cadr(x)));
		}
		if (head == BQ_COMMA_AT) {
			return bqExpandEscaped(cadr(x));
		}
		if (head == BQ_COMMA_DOT) {
			return list2(BQ_CLOBBERABLE, bqExpandEscaped(cadr(x)));
		}
		return list2(BQ_LIST, bqProcess(x));
	}

	// Fully expands any inner backquote found in code that has escaped to level 0
	// (a comma argument). Unlike CLtL2 -- which leaves an inner backquote as a live
	// macro call to be expanded later -- our runtime has no backquote, so escaped
	// inner backquotes are expanded here. Comma markers belonging to an outer level
	// are preserved for the enclosing bqProcess pass.
	private LispVal bqExpandEscaped(LispVal x) {
		if (!(x instanceof LispCons cons)) {
			return x;
		}
		if (cons.car() == BQ_BACKQUOTE) {
			return bqCompletelyProcess(cadr(x));
		}
		return new LispCons(bqExpandEscaped(cons.car()), bqExpandEscaped(cons.cdr()));
	}

	private boolean bqSplicingFrob(LispVal x) {
		return x instanceof LispCons && (car(x) == BQ_COMMA_AT || car(x) == BQ_COMMA_DOT);
	}

	private boolean bqFrob(LispVal x) {
		return x instanceof LispCons && (car(x) == BQ_COMMA || car(x) == BQ_COMMA_AT || car(x) == BQ_COMMA_DOT);
	}

	// bq-simplify: fold nested list/append markers into flatter list/list*/append.
	private LispVal bqSimplify(LispVal x) {
		if (!(x instanceof LispCons)) {
			return x;
		}
		LispVal simplified = (car(x) == BQ_QUOTE) ? x : bqMaptreeSimplify(x);
		if (!(simplified instanceof LispCons) || car(simplified) != BQ_APPEND) {
			return simplified;
		}
		return bqSimplifyArgs(simplified);
	}

	// maptree of bqSimplify over the arguments of a marker form (car unchanged).
	private LispVal bqMaptreeSimplify(LispVal x) {
		if (!(x instanceof LispCons cons)) {
			return bqSimplify(x);
		}
		LispVal a = bqSimplify(cons.car());
		LispVal d = bqMaptreeSimplify(cons.cdr());
		if (a == cons.car() && d == cons.cdr()) {
			return x;
		}
		return new LispCons(a, d);
	}

	private LispVal bqSimplifyArgs(LispVal x) {
		// Iterate over the reversed argument list, attaching each to the result.
		List<LispVal> args = new ArrayList<>();
		LispVal p = cdr(x);
		while (p instanceof LispCons cons) {
			args.add(cons.car());
			p = cons.cdr();
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = args.size() - 1; i >= 0; i--) {
			LispVal arg = args.get(i);
			if (!(arg instanceof LispCons)) {
				result = bqAttachAppend(BQ_APPEND, arg, result);
			}
			else if (car(arg) == BQ_LIST && !anySplicingFrob(cdr(arg))) {
				result = bqAttachConses(cdr(arg), result);
			}
			else if (car(arg) == BQ_LIST_STAR && !anySplicingFrob(cdr(arg))) {
				LispVal butlast = butLast(cdr(arg));
				LispVal last = lastElem(arg);
				result = bqAttachConses(butlast, bqAttachAppend(BQ_APPEND, last, result));
			}
			else if (car(arg) == BQ_QUOTE && cadr(arg) instanceof LispCons && !bqFrob(cadr(arg))
					&& cddr(arg) instanceof LispNil) {
				result = bqAttachConses(list1(list2(BQ_QUOTE, car(cadr(arg)))), result);
			}
			else if (car(arg) == BQ_CLOBBERABLE) {
				result = bqAttachAppend(BQ_NCONC, cadr(arg), result);
			}
			else {
				result = bqAttachAppend(BQ_APPEND, arg, result);
			}
		}
		return result;
	}

	private boolean anySplicingFrob(LispVal list) {
		LispVal p = list;
		while (p instanceof LispCons cons) {
			if (bqSplicingFrob(cons.car())) {
				return true;
			}
			p = cons.cdr();
		}
		return false;
	}

	private boolean nullOrQuoted(LispVal x) {
		return x instanceof LispNil || (x instanceof LispCons && car(x) == BQ_QUOTE);
	}

	private LispVal bqAttachAppend(LispSymbol op, LispVal item, LispVal result) {
		// The constant fold is an `append`, so the ITEM (a non-final argument) has to be
		// a PROPER list. It is not when the template had a dotted tail: bq-process emits
		// (bq-append (bq-list 'p) 'q) for `(p . q), and folding 'q as a list would walk
		// it to nothing and silently drop the tail. Falling through instead leaves the
		// quoted tail as the result, which the bq-attach-conses below then conses onto --
		// where a non-list tail IS legal, because there it is `append`'s LAST argument.
		if (nullOrQuoted(item) && nullOrQuoted(result) && isProperList(quotedValue(item))) {
			return list2(BQ_QUOTE, appendLists(quotedValue(item), quotedValue(result)));
		}
		if (result instanceof LispNil || isQuoteNil(result)) {
			return bqSplicingFrob(item) ? list2(op, item) : item;
		}
		if (result instanceof LispCons && car(result) == op) {
			return cons(car(result), cons(item, cdr(result)));
		}
		return listOf3(op, item, result);
	}

	private LispVal bqAttachConses(LispVal items, LispVal result) {
		if (everyNullOrQuoted(items) && nullOrQuoted(result)) {
			List<LispVal> vals = new ArrayList<>();
			LispVal p = items;
			while (p instanceof LispCons cons) {
				vals.add(quotedValue(cons.car()));
				p = cons.cdr();
			}
			return list2(BQ_QUOTE, appendLists(fromList(vals), quotedValue(result)));
		}
		if (result instanceof LispNil || isQuoteNil(result)) {
			return cons(BQ_LIST, items);
		}
		if (result instanceof LispCons && (car(result) == BQ_LIST || car(result) == BQ_LIST_STAR)) {
			return cons(car(result), appendLists(items, cdr(result)));
		}
		return cons(BQ_LIST_STAR, appendLists(items, list1(result)));
	}

	// bq-remove-tokens: replace the sentinel markers with the real Lisp operators
	// (list/append/nconc/list*/quote), turning a 2-argument list* into cons.
	private LispVal bqRemoveTokens(LispVal x) {
		if (x == BQ_LIST) {
			return new LispSymbol(LispNames.LIST);
		}
		if (x == BQ_APPEND) {
			return new LispSymbol(LispNames.APPEND);
		}
		if (x == BQ_NCONC) {
			return new LispSymbol(LispNames.NCONC);
		}
		if (x == BQ_LIST_STAR) {
			return new LispSymbol(LispNames.LIST_STAR);
		}
		if (x == BQ_QUOTE) {
			return new LispSymbol(LispNames.QUOTE);
		}
		if (!(x instanceof LispCons)) {
			return x;
		}
		if (car(x) == BQ_CLOBBERABLE) {
			return bqRemoveTokens(cadr(x));
		}
		if (car(x) == BQ_LIST_STAR && cddr(x) instanceof LispCons && cdr(cddr(x)) instanceof LispNil) {
			// (list* a b) -> (cons a b)
			return cons(new LispSymbol(LispNames.CONS), bqMaptreeRemove(cdr(x)));
		}
		return bqMaptreeRemove(x);
	}

	private LispVal bqMaptreeRemove(LispVal x) {
		if (!(x instanceof LispCons cons)) {
			return bqRemoveTokens(x);
		}
		LispVal a = bqRemoveTokens(cons.car());
		LispVal d = bqMaptreeRemove(cons.cdr());
		if (a == cons.car() && d == cons.cdr()) {
			return x;
		}
		return new LispCons(a, d);
	}

	// --- small cons/list helpers for the CLtL2 port -----------------------------

	private static LispVal car(LispVal x) {
		return ((LispCons) x).car();
	}

	private static LispVal cdr(LispVal x) {
		return ((LispCons) x).cdr();
	}

	private static LispVal cadr(LispVal x) {
		return car(cdr(x));
	}

	private static LispVal cddr(LispVal x) {
		return cdr(cdr(x));
	}

	private static LispCons cons(LispVal a, LispVal d) {
		return new LispCons(a, d);
	}

	private static LispCons list1(LispVal a) {
		return new LispCons(a, LispNil.INSTANCE);
	}

	private static LispCons list2(LispVal a, LispVal b) {
		return new LispCons(a, new LispCons(b, LispNil.INSTANCE));
	}

	private static LispCons listOf3(LispVal a, LispVal b, LispVal c) {
		return new LispCons(a, new LispCons(b, new LispCons(c, LispNil.INSTANCE)));
	}

	private static LispVal fromList(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

	// The value inside a (BQ_QUOTE v) form, or nil for nil.
	private LispVal quotedValue(LispVal x) {
		if (x instanceof LispNil) {
			return LispNil.INSTANCE;
		}
		return cadr(x);
	}

	private boolean isQuoteNil(LispVal x) {
		return x instanceof LispCons && car(x) == BQ_QUOTE && cadr(x) instanceof LispNil && cddr(x) instanceof LispNil;
	}

	private boolean everyNullOrQuoted(LispVal list) {
		LispVal p = list;
		while (p instanceof LispCons cons) {
			if (!nullOrQuoted(cons.car())) {
				return false;
			}
			p = cons.cdr();
		}
		return true;
	}

	// True for nil and for a cons chain ending in nil (the shape `append` requires of
	// every argument but the last).
	private boolean isProperList(LispVal x) {
		LispVal p = x;
		while (p instanceof LispCons cons) {
			p = cons.cdr();
		}
		return p instanceof LispNil;
	}

	// Appends a proper list to any object: like CL's two-argument append, so a non-list
	// B yields a DOTTED result (used only on constant fold paths).
	private LispVal appendLists(LispVal a, LispVal b) {
		List<LispVal> items = new ArrayList<>();
		LispVal p = a;
		while (p instanceof LispCons cons) {
			items.add(cons.car());
			p = cons.cdr();
		}
		LispVal result = b;
		for (int i = items.size() - 1; i >= 0; i--) {
			result = new LispCons(items.get(i), result);
		}
		return result;
	}

	// All but the last element of a proper list.
	private LispVal butLast(LispVal list) {
		List<LispVal> items = new ArrayList<>();
		LispVal p = list;
		while (p instanceof LispCons cons && cons.cdr() instanceof LispCons) {
			items.add(cons.car());
			p = cons.cdr();
		}
		return fromList(items);
	}

	// The last element of a proper (non-empty) list.
	private LispVal lastElem(LispVal list) {
		LispVal p = list;
		while (p instanceof LispCons cons && cons.cdr() instanceof LispCons) {
			p = cons.cdr();
		}
		return car(p);
	}

}
