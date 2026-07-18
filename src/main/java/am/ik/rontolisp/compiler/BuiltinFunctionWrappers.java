package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Generates synthetic {@code (setq name (lambda ...))} wrapper defuns for built-in
 * operators. These wrappers allow built-in operators like {@code +}, {@code car} to be
 * used as first-class function values (passed to {@code map}, {@code reduce},
 * {@code funcall}).
 *
 * <p>
 * The wrapper body uses the operator in call position, where {@code compileCons} inlines
 * it directly. User defuns with the same name take priority since wrappers are only
 * injected for names not already defined by the user.
 */
public final class BuiltinFunctionWrappers {

	private BuiltinFunctionWrappers() {
	}

	/**
	 * Built-in operators that the WASM backend cannot compile. The WASM compiler passes
	 * these to {@link #generate(Set, Set)} so that no wrapper defun referencing them is
	 * injected. Now EMPTY -- the last members ({@code asin} / {@code acos} / {@code atan}
	 * / {@code sinh} / {@code cosh}) left when the WASM backend gained software
	 * approximations for every transcendental built-in ({@code WasmExpCompiler} /
	 * {@code WasmLogCompiler} / {@code WasmTanhCompiler} / {@code WasmSinCosCompiler} /
	 * {@code WasmAtanCompiler} / {@code WasmSinhCoshCompiler}), so every {@code #'}
	 * first-class value is supported. Kept as the seam for any future built-in a backend
	 * cannot compile.
	 */
	public static final Set<String> WASM_UNSUPPORTED = Set.of();

	/**
	 * Hash-table operator wrappers whose compiled bodies reference runtime helpers (JVM)
	 * or inline code (WASM) that each backend emits only when the program actually uses a
	 * hash table. They are injected as first-class wrappers only when the program uses
	 * any hash-table operator, so the wrapper and its helpers stay gated together (see
	 * the {@code wrapperExcludes} handling in {@code Jvm/WasmLispCompiler}).
	 * {@code %puthash} is internal and intentionally excluded; {@code make-hash-table}
	 * takes keyword arguments and is exposed here only in its 0-arg default-table form.
	 */
	public static final Set<String> HASH_FUNCTIONS = Set.of(LispNames.MAKE_HASH_TABLE, LispNames.GETHASH,
			LispNames.REMHASH, LispNames.CLRHASH, LispNames.HASH_TABLE_COUNT, LispNames.HASH_TABLE_P,
			LispNames.MAPHASH);

	/**
	 * Fill-pointer array wrappers, gated like {@link #HASH_FUNCTIONS}: their compiled
	 * bodies reference the array runtime helpers (JVM) that are emitted only when the
	 * program uses an array operator, so each backend injects them only for programs that
	 * do. {@code %set-fill-pointer} is internal and intentionally excluded;
	 * {@code vector-push-extend} is exposed in its 2-argument form (no extension).
	 */
	public static final Set<String> ARRAY_FILL_POINTER_FUNCTIONS = Set.of(LispNames.FILL_POINTER,
			LispNames.ARRAY_HAS_FILL_POINTER_P, LispNames.ADJUSTABLE_ARRAY_P, LispNames.ARRAY_ELEMENT_TYPE,
			LispNames.VECTOR_PUSH, LispNames.VECTOR_POP, LispNames.VECTOR_PUSH_EXTEND, LispNames.ADJUST_ARRAY,
			LispNames.ARRAY_DISPLACEMENT, LispNames.MAKE_ARRAY);

	/**
	 * Signal-operator wrappers ({@code #'error}/{@code #'cerror}/{@code #'signal}/
	 * {@code #'warn}), injected only when the program takes the operator as a first-class
	 * value (see {@link #referencesFunctionValue}) so ordinary programs stay
	 * byte-identical. Lite semantics: the wrapper forwards the datum only -- initargs and
	 * format arguments after it are dropped, so a symbol datum signals a plain condition
	 * naming the class rather than a typed instance with slots (the interpreter keeps the
	 * full designator protocol).
	 */
	public static final Set<String> SIGNAL_FUNCTIONS = Set.of(LispNames.ERROR, LispNames.CERROR, LispNames.SIGNAL,
			LispNames.WARN);

	/**
	 * Wrappers injected only when the program takes the operator as a first-class value
	 * (see {@link #referencesFunctionValue}), so ordinary programs stay byte-identical:
	 * the signal operators plus {@code format}. The {@code #'format} wrapper renders
	 * through the shared runtime control renderer (the same fallback {@code expandFormat}
	 * uses for computed control strings, so the runtime directive subset applies); a nil
	 * destination returns the string, any other destination is written to with one
	 * {@code write-string} call and yields nil ({@code t} designates standard output
	 * there).
	 */
	public static final Set<String> REFERENCE_GATED_FUNCTIONS;
	static {
		Set<String> gated = new java.util.HashSet<>(SIGNAL_FUNCTIONS);
		gated.add(LispNames.FORMAT);
		REFERENCE_GATED_FUNCTIONS = Set.copyOf(gated);
	}

	/**
	 * Whether the expression takes the named operator as a first-class function value --
	 * a {@code (function name)} form (the {@code #'name} reader shape).
	 * @param expr the expression to scan
	 * @param name the operator name
	 * @return {@code true} when a {@code (function name)} reference occurs
	 */
	public static boolean referencesFunctionValue(LispVal expr, String name) {
		if (!(expr instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol op && LispNames.FUNCTION.equals(op.name())
				&& cons.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol sym
				&& name.equals(sym.name())) {
			return true;
		}
		return referencesFunctionValue(cons.car(), name) || referencesFunctionValue(cons.cdr(), name);
	}

	/**
	 * Generates wrapper defuns for built-in operators that are not already defined by the
	 * user.
	 * @param userDefinedNames names already defined by user defuns
	 * @return list of {@code (setq name (lambda ...))} expressions
	 */
	public static List<LispVal> generate(Set<String> userDefinedNames) {
		return generate(userDefinedNames, Set.of());
	}

	/**
	 * Generates wrapper defuns for built-in operators that are not already defined by the
	 * user and are not in the excluded set.
	 * @param userDefinedNames names already defined by user defuns
	 * @param excludedNames operator names to skip (e.g. functions a backend cannot
	 * compile)
	 * @return list of {@code (setq name (lambda ...))} expressions
	 */
	public static List<LispVal> generate(Set<String> userDefinedNames, Set<String> excludedNames) {
		List<LispVal> wrappers = new ArrayList<>();
		for (WrapperDef def : WRAPPER_DEFS) {
			if (!userDefinedNames.contains(def.name) && !excludedNames.contains(def.name)) {
				wrappers.add(def.toSetqLambda());
			}
		}
		return wrappers;
	}

	private record WrapperDef(String name, List<String> params, List<LispVal> body) {

		LispVal toSetqLambda() {
			// Build (setq name (lambda (params...) body...))
			LispVal paramList = listToCons(params.stream().map(p -> (LispVal) new LispSymbol(p)).toList());
			List<LispVal> lambdaParts = new ArrayList<>();
			lambdaParts.add(new LispSymbol(LispNames.LAMBDA));
			lambdaParts.add(paramList);
			lambdaParts.addAll(body);
			LispVal lambda = listToCons(lambdaParts);
			return listToCons(List.of(new LispSymbol(LispNames.SETQ), new LispSymbol(name), lambda));
		}

	}

	// Helper to build a call expression: (op args...)
	private static LispVal call(String op, String... args) {
		List<LispVal> parts = new ArrayList<>();
		parts.add(new LispSymbol(op));
		for (String arg : args) {
			parts.add(new LispSymbol(arg));
		}
		return listToCons(parts);
	}

	// Helper to build a call with LispVal args
	private static LispVal callV(String op, LispVal... args) {
		List<LispVal> parts = new ArrayList<>();
		parts.add(new LispSymbol(op));
		for (LispVal arg : args) {
			parts.add(arg);
		}
		return listToCons(parts);
	}

	private static WrapperDef unary(String name) {
		return new WrapperDef(name, List.of("a"), List.of(call(name, "a")));
	}

	private static WrapperDef binary(String name) {
		return new WrapperDef(name, List.of("a", "b"), List.of(call(name, "a", "b")));
	}

	private static WrapperDef ternary(String name) {
		return new WrapperDef(name, List.of("a", "b", "c"), List.of(call(name, "a", "b", "c")));
	}

	// Builds the inner two-argument fold lambda (lambda (a x) (op a x)). The op sits in
	// call position, so the compilers inline the primitive (the surrounding (setq op
	// (lambda ...)) wrapper only rebinds the variable namespace, not the function one).
	private static LispVal foldLambda(String op) {
		return listToCons(List.of(new LispSymbol(LispNames.LAMBDA),
				listToCons(List.of(new LispSymbol("a"), new LispSymbol("x"))), call(op, "a", "x")));
	}

	// (reduce (lambda (a x) (op a x)) list :initial-value init)
	private static LispVal foldReduce(String op, LispVal list, LispVal init) {
		return listToCons(List.of(new LispSymbol(LispNames.REDUCE), foldLambda(op), list,
				new LispSymbol(LispNames.INITIAL_VALUE_KEYWORD), init));
	}

	// Variadic wrapper for an associative operator with an identity (e.g. + -> 0, * ->
	// 1):
	// (lambda (&rest r) (reduce (lambda (a x) (op a x)) r :initial-value identity)).
	private static WrapperDef variadicIdentity(String name, LispVal identity) {
		return new WrapperDef(name, List.of(LispNames.LAMBDA_REST, "r"),
				List.of(foldReduce(name, new LispSymbol("r"), identity)));
	}

	// Variadic wrapper for min/max (needs at least one argument; a single argument
	// returns itself): (lambda (&rest r) (reduce (lambda (a x) (op a x)) (cdr r)
	// :initial-value (car r))). Zero args fold over nil with init nil, yielding nil.
	private static WrapperDef variadicNonEmpty(String name) {
		return new WrapperDef(name, List.of(LispNames.LAMBDA_REST, "r"),
				List.of(foldReduce(name, call(LispNames.CDR, "r"), call(LispNames.CAR, "r"))));
	}

	// Variadic wrapper for nconc: (lambda (&rest r) (if r (reduce (lambda (a x) (nconc a
	// x)) r) nil)). A left fold over the 2-arg nconc yields correct CL semantics -- each
	// pair links the accumulator's last cdr to the next argument and the fold returns the
	// first non-nil argument; reduce returns a lone element unchanged, and the guard maps
	// zero args to nil.
	private static WrapperDef variadicNconc() {
		LispVal reduce = listToCons(
				List.of(new LispSymbol(LispNames.REDUCE), foldLambda(LispNames.NCONC), new LispSymbol("r")));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.IF), new LispSymbol("r"), reduce, LispNil.INSTANCE));
		return new WrapperDef(LispNames.NCONC, List.of(LispNames.LAMBDA_REST, "r"), List.of(body));
	}

	// Variadic wrapper for - and /, which have distinct one-argument semantics
	// ((- x) = -x, (/ x) = 1/x) from the multi-argument left fold:
	// (lambda (&rest r) (if (cdr r) (reduce ... (cdr r) :initial-value (car r))
	// (op unaryLeft (car r)))).
	private static WrapperDef variadicUnaryLeft(String name, LispVal unaryLeft) {
		LispVal multi = foldReduce(name, call(LispNames.CDR, "r"), call(LispNames.CAR, "r"));
		LispVal single = callV(name, unaryLeft, call(LispNames.CAR, "r"));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.CDR, "r"), multi, single));
		return new WrapperDef(name, List.of(LispNames.LAMBDA_REST, "r"), List.of(body));
	}

	// (getf kw :indicator) -- runtime keyword extraction from the wrapper's rest list.
	private static LispVal getfKw(String indicator) {
		return callV(LispNames.GETF, new LispSymbol("kw"), new LispSymbol(indicator));
	}

	// (if (getf kw :indicator) (getf kw :indicator) default) -- getf is pure, so the
	// double extraction is safe.
	private static LispVal getfKwOr(String indicator, LispVal dflt) {
		return listToCons(List.of(new LispSymbol(LispNames.IF), getfKw(indicator), getfKw(indicator), dflt));
	}

	// #'name
	private static LispVal sharpQuote(String fn) {
		return callV(LispNames.FUNCTION, new LispSymbol(fn));
	}

	// Variadic wrapper for the position family: the runtime keywords are re-extracted
	// with getf and fed back into the call-position expansion, so first-class use
	// through apply supports the full :test/:test-not/:key/:start/:end/:from-end set
	// (e.g. cl-utilities' split-sequence does (apply #'position item seq :end r ...)).
	// A :test-not is normalized to a complemented :test; the -if/-if-not variants take
	// no :test/:test-not (CL semantics).
	private static WrapperDef positionFamily(String name, boolean item) {
		List<LispVal> callParts = new ArrayList<>();
		callParts.add(new LispSymbol(name));
		callParts.add(new LispSymbol("a"));
		callParts.add(new LispSymbol("seq"));
		if (item) {
			callParts.add(new LispSymbol(LispNames.TEST_KEYWORD));
			callParts.add(listToCons(List.of(new LispSymbol(LispNames.IF), getfKw(LispNames.TEST_NOT_KEYWORD),
					callV(LispNames.COMPLEMENT, getfKw(LispNames.TEST_NOT_KEYWORD)),
					getfKwOr(LispNames.TEST_KEYWORD, sharpQuote(LispNames.EQL)))));
		}
		callParts.add(new LispSymbol(LispNames.KEY_KEYWORD));
		callParts.add(getfKwOr(LispNames.KEY_KEYWORD, sharpQuote(LispNames.IDENTITY)));
		callParts.add(new LispSymbol(LispNames.START_KEYWORD));
		callParts.add(getfKwOr(LispNames.START_KEYWORD, new LispInteger(0)));
		callParts.add(new LispSymbol(LispNames.END_KEYWORD));
		callParts.add(getfKw(LispNames.END_KEYWORD));
		callParts.add(new LispSymbol(LispNames.FROM_END_KEYWORD));
		callParts.add(getfKw(LispNames.FROM_END_KEYWORD));
		return new WrapperDef(name, List.of("a", "seq", LispNames.LAMBDA_REST, "kw"), List.of(listToCons(callParts)));
	}

	// Variadic wrapper for make-array (gated with the fill-pointer array group):
	// runtime keywords are re-extracted with getf. A :displaced-to argument selects
	// the bare-view shape (a displaced array cannot combine with the other options),
	// everything else the general shape; :element-type is accepted and ignored, like
	// the call position. Enables cl-utilities' copy-array idiom
	// (apply #'make-array (list* dims options...)).
	private static WrapperDef variadicMakeArray() {
		LispVal displaced = listToCons(List.of(new LispSymbol(LispNames.MAKE_ARRAY), new LispSymbol("dims"),
				new LispSymbol(LispNames.DISPLACED_TO_KEYWORD), getfKw(LispNames.DISPLACED_TO_KEYWORD),
				new LispSymbol(LispNames.DISPLACED_INDEX_OFFSET_KEYWORD),
				getfKwOr(LispNames.DISPLACED_INDEX_OFFSET_KEYWORD, new LispInteger(0))));
		LispVal general = listToCons(List.of(new LispSymbol(LispNames.MAKE_ARRAY), new LispSymbol("dims"),
				new LispSymbol(LispNames.ADJUSTABLE_KEYWORD), getfKw(LispNames.ADJUSTABLE_KEYWORD),
				new LispSymbol(LispNames.FILL_POINTER_KEYWORD), getfKw(LispNames.FILL_POINTER_KEYWORD),
				new LispSymbol(LispNames.INITIAL_ELEMENT_KEYWORD), getfKw(LispNames.INITIAL_ELEMENT_KEYWORD)));
		LispVal body = listToCons(
				List.of(new LispSymbol(LispNames.IF), getfKw(LispNames.DISPLACED_TO_KEYWORD), displaced, general));
		return new WrapperDef(LispNames.MAKE_ARRAY, List.of("dims", LispNames.LAMBDA_REST, "kw"), List.of(body));
	}

	// Variadic wrapper for stable-sort: only :key is supported, extracted at runtime
	// like the position family.
	private static WrapperDef variadicStableSort() {
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.STABLE_SORT), new LispSymbol("seq"),
				new LispSymbol("pred"), new LispSymbol(LispNames.KEY_KEYWORD),
				getfKwOr(LispNames.KEY_KEYWORD, sharpQuote(LispNames.IDENTITY))));
		return new WrapperDef(LispNames.STABLE_SORT, List.of("seq", "pred", LispNames.LAMBDA_REST, "kw"),
				List.of(body));
	}

	// Signal-operator wrapper: (lambda (datum &rest r) (op datum)) -- the datum-only
	// lite forwarding described on SIGNAL_FUNCTIONS.
	private static WrapperDef signalDatum(String name, String delegate) {
		return new WrapperDef(name, List.of("datum", LispNames.LAMBDA_REST, "r"), List.of(call(delegate, "datum")));
	}

	// cerror's wrapper additionally drops the leading continue format control.
	private static WrapperDef cerrorWrapper() {
		return new WrapperDef(LispNames.CERROR, List.of("cfc", "datum", LispNames.LAMBDA_REST, "r"),
				List.of(call(LispNames.ERROR, "datum")));
	}

	// #'format wrapper (gated by REFERENCE_GATED_FUNCTIONS): renders through the shared
	// runtime control renderer, then dispatches on the destination -- nil returns the
	// string, anything else (the t designator or a stream handle) gets one write-string
	// call and nil.
	private static WrapperDef formatWrapper() {
		LispSymbol strVar = new LispSymbol("__fmt_str");
		LispVal rendered = listToCons(List.of(new LispSymbol(LispNames.FUNCALL),
				am.ik.rontolisp.LispMacroExpander.formatRuntimeLambda(), new LispSymbol("ctrl"), new LispSymbol("r")));
		LispVal bindings = listToCons(List.of((LispVal) listToCons(List.of(strVar, rendered))));
		LispVal writeForm = listToCons(List.of(new LispSymbol(LispNames.PROGN),
				callV(LispNames.WRITE_STRING, strVar, new LispSymbol("dest")), LispNil.INSTANCE));
		LispVal ifForm = listToCons(
				List.of(new LispSymbol(LispNames.IF), call(LispNames.NULL, "dest"), strVar, writeForm));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET), bindings, ifForm));
		return new WrapperDef(LispNames.FORMAT, List.of("dest", "ctrl", LispNames.LAMBDA_REST, "r"), List.of(body));
	}

	private static final List<WrapperDef> WRAPPER_DEFS = List.of(
			// Signal operators and format (gated by REFERENCE_GATED_FUNCTIONS in the
			// backend compilers)
			signalDatum(LispNames.ERROR, LispNames.ERROR), signalDatum(LispNames.SIGNAL, LispNames.SIGNAL),
			signalDatum(LispNames.WARN, LispNames.WARN), cerrorWrapper(), formatWrapper(),
			// Arithmetic: +/-/*// are variadic in CL, so their wrappers accept any arity
			// (a fixed-arity wrapper returned nil on the JVM / trapped on WASM when
			// funcall/apply passed a different argument count). - and / keep their
			// one-argument semantics.
			variadicIdentity(LispNames.ADD, new LispInteger(0)), variadicUnaryLeft(LispNames.SUB, new LispInteger(0)),
			variadicIdentity(LispNames.MUL, new LispInteger(1)), variadicUnaryLeft(LispNames.DIV, new LispInteger(1)),
			binary(LispNames.MOD), binary(LispNames.REM),
			// Comparison (arity 2)
			binary(LispNames.EQ), binary(LispNames.LT), binary(LispNames.GT), binary(LispNames.LE),
			binary(LispNames.GE), binary(LispNames.NE),
			// List/utility (arity 2)
			binary(LispNames.CONS), binary(LispNames.EQ_GENERAL), binary(LispNames.EQL), binary(LispNames.EQUAL),
			// min/max are variadic (need at least one argument)
			variadicNonEmpty(LispNames.MIN), variadicNonEmpty(LispNames.MAX), binary(LispNames.NTHCDR),
			binary(LispNames.APPEND),
			// List access (arity 1; first/rest/second/... compile via macro expansion)
			unary(LispNames.CAR), unary(LispNames.CDR), unary(LispNames.FIRST), unary(LispNames.REST),
			unary(LispNames.SECOND), unary(LispNames.THIRD), unary(LispNames.FOURTH), binary(LispNames.NTH),
			// Sequence operations (compiled via macro expansion in call position)
			unary(LispNames.LENGTH), unary(LispNames.REVERSE), unary(LispNames.LAST), unary(LispNames.BUTLAST),
			binary(LispNames.MEMBER), binary(LispNames.MEMBER_IF), binary(LispNames.FIND), binary(LispNames.FIND_IF),
			binary(LispNames.FIND_IF_NOT), positionFamily(LispNames.POSITION, true),
			positionFamily(LispNames.POSITION_IF, false), positionFamily(LispNames.POSITION_IF_NOT, false),
			binary(LispNames.COUNT), binary(LispNames.COUNT_IF), binary(LispNames.ASSOC), binary(LispNames.ASSOC_IF),
			binary(LispNames.RASSOC), ternary(LispNames.ACONS), binary(LispNames.PAIRLIS), unary(LispNames.COPY_ALIST),
			binary(LispNames.GETF), unary(LispNames.REMOVE_DUPLICATES), variadicNconc(), unary(LispNames.IDENTITY),
			unary(LispNames.COPY_LIST), unary(LispNames.NREVERSE), unary(LispNames.MAKE_LIST), binary(LispNames.UNION),
			binary(LispNames.INTERSECTION), binary(LispNames.SET_DIFFERENCE), binary(LispNames.ADJOIN),
			binary(LispNames.EVERY), binary(LispNames.SOME), binary(LispNames.REMOVE), binary(LispNames.REMOVE_IF),
			binary(LispNames.REMOVE_IF_NOT), binary(LispNames.DELETE), binary(LispNames.DELETE_IF),
			binary(LispNames.DELETE_IF_NOT), ternary(LispNames.SUBSTITUTE), ternary(LispNames.NSUBSTITUTE),
			binary(LispNames.MAPCAN), binary(LispNames.SORT), variadicStableSort(), unary(LispNames.COPY_SEQ),
			// funcall is variadic: (lambda (f &rest r) (apply f r)) -- e.g.
			// cl-utilities' compose folds with (reduce #'funcall fns ...).
			new WrapperDef(LispNames.FUNCALL, List.of("f", LispNames.LAMBDA_REST, "r"),
					List.of(callV(LispNames.APPLY, new LispSymbol("f"), new LispSymbol("r")))),
			// Predicates (arity 1)
			unary(LispNames.NULL), unary(LispNames.NOT), unary(LispNames.ATOM),
			// Type predicates (arity 1)
			unary(LispNames.NUMBERP), unary(LispNames.INTEGERP), unary(LispNames.FLOATP), unary(LispNames.SYMBOLP),
			unary(LispNames.STRINGP), unary(LispNames.LISTP), unary(LispNames.CONSP), unary(LispNames.KEYWORDP),
			unary(LispNames.FUNCTIONP), unary(LispNames.VALUES_LIST), unary(LispNames.VECTORP),
			// Type conversion (arity 1)
			unary(LispNames.FLOAT), unary(LispNames.TRUNCATE), unary(LispNames.FLOOR), unary(LispNames.CEILING),
			unary(LispNames.ROUND),
			// Math/IO/list (arity 1)
			unary(LispNames.ABS), unary(LispNames.PRINT), unary(LispNames.PRIN1), unary(LispNames.PRINC),
			unary(LispNames.PRINC_TO_STRING), unary(LispNames.PRIN1_TO_STRING),
			// list is variadic: the rest list IS the result
			new WrapperDef(LispNames.LIST, List.of(LispNames.LAMBDA_REST, "r"), List.of(new LispSymbol("r"))),
			// Math functions (arity 1)
			unary(LispNames.SQRT), unary(LispNames.ISQRT), unary(LispNames.SIGNUM), unary(LispNames.EXP),
			unary(LispNames.LOG), unary(LispNames.SIN), unary(LispNames.COS), unary(LispNames.TAN),
			unary(LispNames.ASIN), unary(LispNames.ACOS), unary(LispNames.ATAN), unary(LispNames.SINH),
			unary(LispNames.COSH), unary(LispNames.TANH), unary(LispNames.RANDOM),
			// Math functions (arity 2)
			binary(LispNames.EXPT), binary(LispNames.GCD), binary(LispNames.LCM),
			// Bitwise integer operations
			binary(LispNames.LOGAND), binary(LispNames.LOGIOR), binary(LispNames.LOGXOR), unary(LispNames.LOGNOT),
			binary(LispNames.ASH), unary(LispNames.INTEGER_LENGTH), binary(LispNames.LOGBITP),
			// Byte-field operations (macro-lowered to list/car/ash/logand/logior/lognot)
			binary(LispNames.BYTE), unary(LispNames.BYTE_SIZE), unary(LispNames.BYTE_POSITION), binary(LispNames.LDB),
			ternary(LispNames.DPB), binary(LispNames.MASK_FIELD), binary(LispNames.SCALE_FLOAT),
			// Lite stream/type introspection stubs (macro-lowered; slot-boundp and
			// slot-makunbound are omitted -- their expansions need a literal slot name)
			unary(LispNames.FILE_POSITION), unary(LispNames.FILE_LENGTH), unary(LispNames.PATHNAMEP),
			unary(LispNames.INPUT_STREAM_P), unary(LispNames.OUTPUT_STREAM_P), unary(LispNames.STREAM_ELEMENT_TYPE),
			unary(LispNames.CLASS_OF), unary(LispNames.SIMPLE_CONDITION_FORMAT_CONTROL),
			unary(LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS),
			new WrapperDef(LispNames.MAKE_BROADCAST_STREAM, List.of(), List.of(call(LispNames.MAKE_BROADCAST_STREAM))),
			// 1+ and 1-: body is (+ a 1) and (- a 1)
			new WrapperDef(LispNames.ONE_PLUS, List.of("a"),
					List.of(callV(LispNames.ADD, new LispSymbol("a"), new LispInteger(1)))),
			new WrapperDef(LispNames.ONE_MINUS, List.of("a"),
					List.of(callV(LispNames.SUB, new LispSymbol("a"), new LispInteger(1)))),
			// zerop: (= a 0)
			new WrapperDef(LispNames.ZEROP, List.of("a"),
					List.of(callV(LispNames.EQ, new LispSymbol("a"), new LispInteger(0)))),
			// plusp: (> a 0)
			new WrapperDef(LispNames.PLUSP, List.of("a"),
					List.of(callV(LispNames.GT, new LispSymbol("a"), new LispInteger(0)))),
			// minusp: (< a 0)
			new WrapperDef(LispNames.MINUSP, List.of("a"),
					List.of(callV(LispNames.LT, new LispSymbol("a"), new LispInteger(0)))),
			// evenp: (= (mod a 2) 0)
			new WrapperDef(LispNames.EVENP, List.of("a"),
					List.of(callV(LispNames.EQ, callV(LispNames.MOD, new LispSymbol("a"), new LispInteger(2)),
							new LispInteger(0)))),
			// oddp: (not (= (mod a 2) 0))
			new WrapperDef(LispNames.ODDP, List.of("a"),
					List.of(callV(LispNames.NOT,
							callV(LispNames.EQ, callV(LispNames.MOD, new LispSymbol("a"), new LispInteger(2)),
									new LispInteger(0))))),
			// String operations
			unary(LispNames.STRING), unary(LispNames.STRING_UPCASE), unary(LispNames.STRING_DOWNCASE),
			unary(LispNames.STRING_CAPITALIZE), unary(LispNames.MAKE_STRING), binary(LispNames.REPLACE),
			binary(LispNames.SUBSEQ), binary(LispNames.STRING_EQ), binary(LispNames.STRING_EQUAL),
			binary(LispNames.STRING_TRIM), binary(LispNames.STRING_LEFT_TRIM), binary(LispNames.STRING_RIGHT_TRIM),
			// Character operations
			binary(LispNames.CHAR), binary(LispNames.SCHAR), unary(LispNames.CHAR_CODE), unary(LispNames.CODE_CHAR),
			unary(LispNames.CHAR_UPCASE), unary(LispNames.CHAR_DOWNCASE), unary(LispNames.CHARACTERP),
			unary(LispNames.ALPHA_CHAR_P), unary(LispNames.LOWER_CASE_P), unary(LispNames.UPPER_CASE_P),
			unary(LispNames.CONSTANTP), unary(LispNames.STREAMP), unary(LispNames.DIGIT_CHAR_P),
			binary(LispNames.CHAR_EQ), binary(LispNames.CHAR_LT), binary(LispNames.CHAR_LE),
			// parse-integer / read-from-string: their compiled bodies pull in runtime
			// helpers emitted only when the program uses the operator, so each backend
			// excludes these wrappers (via excludedNames) unless the program references
			// the
			// symbol -- keeping the wrapper and its helper gated together.
			unary(LispNames.PARSE_INTEGER), unary(LispNames.READ_FROM_STRING),
			// Hash-table operators: gated like parse-integer/read-from-string (see
			// HASH_FUNCTIONS). gethash here is the 2-arg form (no default);
			// make-hash-table
			// is the 0-arg default-table form; %puthash is internal and omitted.
			new WrapperDef(LispNames.MAKE_HASH_TABLE, List.of(), List.of(call(LispNames.MAKE_HASH_TABLE))),
			binary(LispNames.GETHASH), binary(LispNames.REMHASH), unary(LispNames.CLRHASH),
			unary(LispNames.HASH_TABLE_COUNT), unary(LispNames.HASH_TABLE_P), binary(LispNames.MAPHASH),
			// Fill-pointer array operators: gated like the hash-table group (see
			// ARRAY_FILL_POINTER_FUNCTIONS). vector-push-extend is the 2-arg form;
			// %set-fill-pointer is internal and omitted.
			unary(LispNames.FILL_POINTER), unary(LispNames.ARRAY_HAS_FILL_POINTER_P),
			unary(LispNames.ADJUSTABLE_ARRAY_P), unary(LispNames.ARRAY_ELEMENT_TYPE), binary(LispNames.VECTOR_PUSH),
			unary(LispNames.VECTOR_POP), binary(LispNames.VECTOR_PUSH_EXTEND),
			// adjust-array is the 2-arg (no keyword) form; array-displacement yields
			// its primary value (the target) -- the offset needs a direct mv consumer.
			binary(LispNames.ADJUST_ARRAY), unary(LispNames.ARRAY_DISPLACEMENT), variadicMakeArray(),
			// terpri: 0-arity
			new WrapperDef(LispNames.TERPRI, List.of(), List.of(call(LispNames.TERPRI))),
			// fresh-line: 0-arity
			new WrapperDef(LispNames.FRESH_LINE, List.of(), List.of(call(LispNames.FRESH_LINE))),
			// read-line: 0-arity
			new WrapperDef(LispNames.READ_LINE, List.of(), List.of(call(LispNames.READ_LINE))),
			new WrapperDef(LispNames.READ_CHAR, List.of(), List.of(call(LispNames.READ_CHAR))),
			// gensym: 0-arity (the literal-prefix form cannot be a first-class value;
			// macroexpand/macroexpand-1 have no wrapper at all -- the macro table does
			// not exist at runtime in compiled output)
			new WrapperDef(LispNames.GENSYM, List.of(), List.of(call(LispNames.GENSYM))),
			// values: variadic; with no runtime multiple-value representation the
			// function value yields its primary value ((car nil) is nil for zero args)
			new WrapperDef(LispNames.VALUES, List.of(LispNames.LAMBDA_REST, "r"), List.of(call(LispNames.CAR, "r"))),
			// write-string: the 1-arg (standard output) form; write-to-string is a
			// prin1-to-string alias
			unary(LispNames.WRITE_STRING),
			new WrapperDef(LispNames.WRITE_TO_STRING, List.of("a"), List.of(call(LispNames.PRIN1_TO_STRING, "a"))),
			// symbol runtime API: only the pure string<->symbol converters get wrappers.
			// find-symbol folds at compile time (literal-only, like symbol-function) and
			// boundp/fboundp/symbol-value need the eval runtime, which is only emitted
			// when the program calls them directly -- so none of those four can be a
			// first-class value in compiled output (macroexpand precedent).
			unary(LispNames.SYMBOL_NAME), unary(LispNames.MAKE_SYMBOL), unary(LispNames.INTERN));

	private static LispVal listToCons(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
