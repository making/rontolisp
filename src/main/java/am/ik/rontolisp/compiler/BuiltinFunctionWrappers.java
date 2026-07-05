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
	 * Built-in operators that the WASM backend cannot compile (transcendental functions
	 * have no native WASM instruction). The WASM compiler passes these to
	 * {@link #generate(Set, Set)} so that no wrapper defun referencing them is injected.
	 * {@code exp} is omitted because the WASM backend emits a software approximation for
	 * it (see {@code WasmExpCompiler}), so {@code #'exp} is supported.
	 */
	public static final Set<String> WASM_UNSUPPORTED = Set.of(LispNames.LOG, LispNames.SIN, LispNames.COS,
			LispNames.TAN, LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH, LispNames.COSH,
			LispNames.TANH);

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
			LispNames.ARRAY_DISPLACEMENT);

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

	private static final List<WrapperDef> WRAPPER_DEFS = List.of(
			// Arithmetic: +/-/*// are variadic in CL, so their wrappers accept any arity
			// (fixed-arity wrappers returned nil on JVM / trapped on WASM for a
			// mismatched
			// funcall/apply -- see .todo/64). - and / keep their one-argument semantics.
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
			binary(LispNames.FIND_IF_NOT), binary(LispNames.POSITION), binary(LispNames.POSITION_IF),
			binary(LispNames.POSITION_IF_NOT), binary(LispNames.COUNT), binary(LispNames.COUNT_IF),
			binary(LispNames.ASSOC), binary(LispNames.ASSOC_IF), binary(LispNames.RASSOC), ternary(LispNames.ACONS),
			binary(LispNames.PAIRLIS), unary(LispNames.COPY_ALIST), binary(LispNames.GETF),
			unary(LispNames.REMOVE_DUPLICATES), variadicNconc(), unary(LispNames.IDENTITY), unary(LispNames.COPY_LIST),
			unary(LispNames.NREVERSE), unary(LispNames.MAKE_LIST), binary(LispNames.UNION),
			binary(LispNames.INTERSECTION), binary(LispNames.SET_DIFFERENCE), binary(LispNames.ADJOIN),
			binary(LispNames.EVERY), binary(LispNames.SOME), binary(LispNames.REMOVE), binary(LispNames.REMOVE_IF),
			binary(LispNames.REMOVE_IF_NOT), binary(LispNames.DELETE), binary(LispNames.DELETE_IF),
			binary(LispNames.DELETE_IF_NOT), ternary(LispNames.SUBSTITUTE), ternary(LispNames.NSUBSTITUTE),
			binary(LispNames.MAPCAN), binary(LispNames.SORT),
			// Predicates (arity 1)
			unary(LispNames.NULL), unary(LispNames.NOT), unary(LispNames.ATOM),
			// Type predicates (arity 1)
			unary(LispNames.NUMBERP), unary(LispNames.INTEGERP), unary(LispNames.FLOATP), unary(LispNames.SYMBOLP),
			unary(LispNames.STRINGP), unary(LispNames.LISTP), unary(LispNames.CONSP), unary(LispNames.KEYWORDP),
			unary(LispNames.FUNCTIONP), unary(LispNames.VALUES_LIST),
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
			ternary(LispNames.DPB),
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
			unary(LispNames.STRING_CAPITALIZE), binary(LispNames.SUBSEQ), binary(LispNames.STRING_EQ),
			binary(LispNames.STRING_EQUAL), binary(LispNames.STRING_TRIM), binary(LispNames.STRING_LEFT_TRIM),
			binary(LispNames.STRING_RIGHT_TRIM),
			// Character operations
			binary(LispNames.CHAR), binary(LispNames.SCHAR), unary(LispNames.CHAR_CODE), unary(LispNames.CODE_CHAR),
			unary(LispNames.CHAR_UPCASE), unary(LispNames.CHAR_DOWNCASE), unary(LispNames.CHARACTERP),
			unary(LispNames.ALPHA_CHAR_P), unary(LispNames.DIGIT_CHAR_P), binary(LispNames.CHAR_EQ),
			binary(LispNames.CHAR_LT), binary(LispNames.CHAR_LE),
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
			binary(LispNames.ADJUST_ARRAY), unary(LispNames.ARRAY_DISPLACEMENT),
			// terpri: 0-arity
			new WrapperDef(LispNames.TERPRI, List.of(), List.of(call(LispNames.TERPRI))),
			// fresh-line: 0-arity
			new WrapperDef(LispNames.FRESH_LINE, List.of(), List.of(call(LispNames.FRESH_LINE))),
			// read-line: 0-arity
			new WrapperDef(LispNames.READ_LINE, List.of(), List.of(call(LispNames.READ_LINE))),
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
