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

/**
 * Parser for Lisp expressions. Converts a list of tokens into LispVal AST nodes.
 */
public final class LispReader {

	private final List<Token> tokens;

	private final Features features;

	private int pos;

	private LispReader(List<Token> tokens, Features features) {
		this.tokens = tokens;
		this.features = features;
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
		return readAll(input, features, LispLexer.ReadEvalMode.ERROR);
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
		return readAll(input, features, LispLexer.ReadEvalMode.SKIP_UNREADABLE);
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
		return readAll(input, features, LispLexer.ReadEvalMode.MARKER);
	}

	private static List<LispVal> readAll(String input, Features features, LispLexer.ReadEvalMode readEvalMode) {
		List<Token> tokens = new LispLexer(input, features, readEvalMode).tokenize();
		LispReader reader = new LispReader(tokens, features);
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
	static boolean parsesAsExpressions(List<Token> tokens, Features features) {
		try {
			LispReader reader = new LispReader(tokens, features);
			while (reader.pos < reader.tokens.size()) {
				reader.readExpr();
			}
			return true;
		}
		catch (LispReadException ex) {
			return false;
		}
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
			case Token.ArrayOpen array -> readArray(array.rank());
			case Token.FloatArrayOpen open -> readFloatArray(open.single());
			case Token.Quote ignored -> readQuote();
			case Token.FunctionQuote ignored -> readFunctionQuote();
			case Token.Backquote ignored -> readBackquote();
			case Token.Unquote ignored -> throw new LispReadException("Comma is illegal outside of backquote");
			case Token.UnquoteSplicing ignored -> throw new LispReadException(",@ is illegal outside of backquote");
			case Token.RightParen ignored -> throw new LispReadException("Unexpected ')'");
			case Token.Dot ignored -> throw new LispReadException("Unexpected '.'");
			case Token.Eof ignored -> throw new LispReadException("Unexpected end of input");
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
					throw new LispReadException(
							"#" + ref.label() + "# references an undefined (or circular) reader label");
				}
				yield datum;
			}
		};
	}

	/** The datums recorded by {@code #n=} reader labels, shared across the read. */
	private final java.util.Map<Integer, LispVal> labels = new java.util.HashMap<>();

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
		if ("most-positive-fixnum".equals(name) || "most-negative-fixnum".equals(name)) {
			// The fixnum range constants, read as self-evaluating integers like pi.
			// The value is backend-dependent (fixed at read time like *features*):
			// WASM fixnums are unboxed i31 references, the interpreter and the JVM
			// backend use Java longs.
			boolean wasm = this.features.contains("rontolisp-wasm");
			long value = name.startsWith("most-positive") ? (wasm ? (1L << 30) - 1 : Long.MAX_VALUE)
					: (wasm ? -(1L << 30) : Long.MIN_VALUE);
			return new LispInteger(value);
		}
		if (LispNames.ARRAY_DIMENSION_LIMIT.equals(name)) {
			// The array-dimension-limit constant, read like most-positive-fixnum: the
			// interpreter/JVM value matches the interpreter's global binding, WASM stays
			// inside the i31 fixnum range.
			boolean wasm = this.features.contains("rontolisp-wasm");
			return new LispInteger(wasm ? (1L << 30) - 1 : 2147483639L);
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
				list = new LispCons(new LispSymbol(":" + names.get(i)), list);
			}
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(list, LispNil.INSTANCE));
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
		LispVal tail = LispNil.INSTANCE;
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			if (this.tokens.get(this.pos) instanceof Token.Dot) {
				// Dotted pair: (a . b) puts b directly in the final cdr.
				if (elements.isEmpty()) {
					throw new LispReadException("Nothing appears before '.' in list");
				}
				this.pos++; // consume '.'
				tail = readExpr();
				if (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
					throw new LispReadException("More than one object follows '.' in list");
				}
				break;
			}
			elements.add(readExpr());
		}
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input, expected ')'");
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

	// Reads a rank-n array literal #nA((...) ...) into a self-evaluating LispArray.
	// The contents are nested lists of depth n whose leaves are the elements in
	// row-major order; every list at the same depth must have the same length
	// (ragged contents are a read error), matching Common Lisp.
	private LispVal readArray(int rank) {
		if (rank < 1) {
			throw new LispReadException("#" + rank + "A: array rank must be >= 1");
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

	// Reads the elements of a #(...)/#nA(...)/#f(...) literal up to the closing ')'.
	private List<LispVal> readGroupedElements() {
		List<LispVal> rows = new ArrayList<>();
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			rows.add(readExpr());
		}
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		return rows;
	}

	// The dimension sizes for a rank-n literal, taken from the first-element chain; an
	// empty level makes every remaining dimension 0 (e.g., #2A() has dimensions (0 0)).
	private static int[] arrayDimensions(List<LispVal> rows, int rank, String label) {
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
	private static double coerceFloatLeaf(LispVal leaf) {
		return switch (leaf) {
			case LispDouble d -> d.value();
			case LispInteger i -> (double) i.value();
			case LispBigInteger b -> b.value().doubleValue();
			case LispRatio r -> r.doubleValue();
			default -> throw new LispReadException("packed float array: expected a number, got " + leaf.print());
		};
	}

	// Validates one level of #nA/#f contents against the expected dimension and appends
	// the elements to `out` in row-major order.
	private void flattenArrayContents(List<LispVal> items, int depth, int[] dims, String label, List<LispVal> out) {
		if (items.size() != dims[depth]) {
			throw new LispReadException(
					label + ": ragged contents, expected " + dims[depth] + " elements, got " + items.size());
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
	private static List<LispVal> arrayLevelContents(LispVal level, String label) {
		if (level instanceof LispNil) {
			return List.of();
		}
		if (!(level instanceof LispCons)) {
			throw new LispReadException(label + ": expected a nested list, got " + level.print());
		}
		List<LispVal> items = new ArrayList<>();
		LispVal tail = level;
		while (tail instanceof LispCons cons) {
			items.add(cons.car());
			tail = cons.cdr();
		}
		if (!(tail instanceof LispNil)) {
			throw new LispReadException(label + ": contents must be proper lists");
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
				// A (%read-eval datum) marker (the #. wrapping in marker read mode) must
				// not be split into template list-construction code: keep it whole as a
				// call, so instantiating the template evaluates the datum -- read-time
				// evaluation deferred to macro-expansion time.
				if (this.pos + 1 < this.tokens.size() && this.tokens.get(this.pos + 1) instanceof Token.SymbolToken sym
						&& LispNames.READ_EVAL.equals(sym.name())) {
					yield new TemplateElement(readExpr(), false);
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
		TemplateElement tail = null;
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			if (this.tokens.get(this.pos) instanceof Token.Dot) {
				// Dotted tail: `(a . ,b) lowers to nested cons forms.
				if (elements.isEmpty()) {
					throw new LispReadException("Nothing appears before '.' in backquote template");
				}
				this.pos++; // consume '.'
				tail = readTemplateElement();
				if (tail.splicing()) {
					throw new LispReadException(",@ cannot follow '.' in a backquote template");
				}
				if (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
					throw new LispReadException("More than one object follows '.' in backquote template");
				}
				break;
			}
			elements.add(readTemplateElement());
		}
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input, expected ')'");
		}
		this.pos++; // consume ')'
		return buildTemplateList(elements, tail);
	}

	private static LispVal buildTemplateList(List<TemplateElement> elements, @Nullable TemplateElement tail) {
		if (tail != null) {
			if (elements.stream().anyMatch(TemplateElement::splicing)) {
				throw new LispReadException(",@ cannot be combined with a dotted tail in a backquote template");
			}
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
			throw new LispReadException("Unexpected end of input in backquote template");
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
		List<LispVal> elements = new ArrayList<>();
		LispVal tail = LispNil.INSTANCE;
		while (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
			if (this.tokens.get(this.pos) instanceof Token.Dot) {
				if (elements.isEmpty()) {
					throw new LispReadException("Nothing appears before '.' in backquote template");
				}
				this.pos++; // consume '.'
				tail = readRawTemplate();
				if (this.pos < this.tokens.size() && !(this.tokens.get(this.pos) instanceof Token.RightParen)) {
					throw new LispReadException("More than one object follows '.' in backquote template");
				}
				break;
			}
			elements.add(readRawTemplate());
		}
		if (this.pos >= this.tokens.size()) {
			throw new LispReadException("Unexpected end of input, expected ')'");
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
			throw new LispReadException(",@ has no enclosing list in a backquote template");
		}
		if (head == BQ_COMMA_DOT) {
			throw new LispReadException(",. has no enclosing list in a backquote template");
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
					throw new LispReadException("Malformed ,");
				}
				segments.add(bqExpandEscaped(cadr(p)));
				return cons(BQ_APPEND, fromList(segments));
			}
			if (ph == BQ_COMMA_AT) {
				throw new LispReadException("Dotted ,@ in a backquote template");
			}
			if (ph == BQ_COMMA_DOT) {
				throw new LispReadException("Dotted ,. in a backquote template");
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
		if (nullOrQuoted(item) && nullOrQuoted(result)) {
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

	// Appends two proper lists (used only on constant fold paths).
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
