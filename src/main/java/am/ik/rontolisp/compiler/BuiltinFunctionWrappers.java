package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
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
	 * {@code make-string} is on the list because it lowers to the character-vector
	 * {@code make-array} ({@code .kb/adjustable-arrays.md}); both backends' array gate
	 * names it, so the wrapper is injected exactly when the runtime it calls is emitted.
	 */
	public static final Set<String> ARRAY_FILL_POINTER_FUNCTIONS = Set.of(LispNames.FILL_POINTER,
			LispNames.ARRAY_HAS_FILL_POINTER_P, LispNames.ADJUSTABLE_ARRAY_P, LispNames.ARRAY_ELEMENT_TYPE,
			LispNames.VECTOR_PUSH, LispNames.VECTOR_POP, LispNames.VECTOR_PUSH_EXTEND, LispNames.ADJUST_ARRAY,
			LispNames.ARRAY_DISPLACEMENT, LispNames.MAKE_ARRAY, LispNames.AREF, LispNames.MAKE_STRING);

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
		gated.add(LispNames.CONCATENATE);
		gated.add(LispNames.OPEN);
		// The read family whose end of file SIGNALS: their wrappers construct the
		// end-of-file condition, which is machinery a program that never takes them as
		// values should not carry (and which the mayCreateInstances gate would not see
		// coming -- it scans the source program, not the injected wrappers).
		gated.add(LispNames.READ_CHAR);
		gated.add(LispNames.PEEK_CHAR);
		gated.add(LispNames.READ_BYTE);
		// #'class-of resolves through the generated %find-class metaobject runtime,
		// which LispMacroExpander injects only for a program that references class-of
		// (or find-class) itself -- the injected wrapper body is outside that scan.
		gated.add(LispNames.CLASS_OF);
		REFERENCE_GATED_FUNCTIONS = Set.copyOf(gated);
	}

	/**
	 * The wrappers whose BODY calls {@code apply}: the {@code map*} family, {@code every}
	 * / {@code some} and {@code funcall} itself. All of them forward a runtime number of
	 * arguments, which is what {@code apply} is for.
	 *
	 * <p>
	 * They are injected ungated, but a backend that GATES its {@code apply} runtime on
	 * the source program using {@code apply} would not see them -- the gate scans the
	 * program, not the injected wrappers. On WASM that gate is {@code usesEval}, and
	 * missing it did not fail loudly: {@code _apply} degrades to a stub answering nil, so
	 * {@code (funcall #'mapcar #'list '(1 2) '(3 4))} answered {@code (NIL NIL)} in a
	 * program that used {@code apply} nowhere else. Use
	 * {@link #referencesApplyingWrapper(LispVal)} in such a gate.
	 */
	public static final Set<String> APPLY_USING_FUNCTIONS = Set.of(LispNames.MAPCAR, LispNames.MAPC, LispNames.MAPCAN,
			LispNames.MAPLIST, LispNames.MAPCON, LispNames.MAPL, LispNames.EVERY, LispNames.SOME, LispNames.FUNCALL);

	/**
	 * Whether the expression takes any {@link #APPLY_USING_FUNCTIONS} member as a
	 * first-class function value, i.e. whether an injected wrapper body calling
	 * {@code apply} can actually be reached at run time.
	 * @param expr the expression to scan
	 * @return true when one of those wrappers is reachable
	 */
	public static boolean referencesApplyingWrapper(LispVal expr) {
		for (String name : APPLY_USING_FUNCTIONS) {
			if (referencesFunctionValue(expr, name)) {
				return true;
			}
		}
		return false;
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

	// The #'aref wrapper body: fold the subscript list into the row-major index.
	// (do ((rm 0) (ds (array-dimensions a)) (is idx))
	// ((null is) (row-major-aref a rm))
	// (setq rm (+ (* rm (car ds)) (car is)))
	// (setq ds (cdr ds))
	// (setq is (cdr is)))
	private static LispVal arefFoldBody() {
		LispVal bindings = listToCons(List.of(callV("rm", new LispInteger(0)),
				callV("ds", call(LispNames.ARRAY_DIMENSIONS, "a")), callV("is", new LispSymbol("idx"))));
		LispVal exit = listToCons(List.of(call(LispNames.NULL, "is"), call(LispNames.ROW_MAJOR_AREF, "a", "rm")));
		LispVal step = callV(LispNames.SETQ, new LispSymbol("rm"), callV(LispNames.ADD,
				callV(LispNames.MUL, new LispSymbol("rm"), call(LispNames.CAR, "ds")), call(LispNames.CAR, "is")));
		LispVal stepDs = callV(LispNames.SETQ, new LispSymbol("ds"), call(LispNames.CDR, "ds"));
		LispVal stepIs = callV(LispNames.SETQ, new LispSymbol("is"), call(LispNames.CDR, "is"));
		return listToCons(List.of(new LispSymbol(LispNames.DO), bindings, exit, step, stepDs, stepIs));
	}

	/**
	 * What a map* family wrapper does with the value of each call. See mapFamilyWrapper.
	 */
	private enum MapAccumulation {

		COLLECT, CONCATENATE, DISCARD

	}

	/**
	 * The first-class wrapper for one member of the {@code map*} family. In CALL position
	 * every member takes any number of lists, but the expansion needs that count
	 * STATICALLY, so a fixed-arity wrapper cannot forward the extra lists -- and dropping
	 * them silently returned a one-list result for {@code (apply #'mapcar f lists)},
	 * alexandria's {@code mappend}/{@code map-product} shape (the interpreter answered
	 * correctly, both compile backends did not). The wrapper therefore walks the
	 * list-of-lists itself, here in its {@code mapcar} instance:
	 *
	 * <pre>
	 * (lambda (f l &amp;rest more)
	 *   (if (null more)
	 *       (mapcar f l)                              ; one list: the primitive
	 *       (do ((ls (cons l more)) (acc nil))         ; N lists: shortest-list walk
	 *           ((member nil ls) (reverse acc))
	 *         (setq acc (cons (apply f (mapcar (lambda (x) (car x)) ls)) acc))
	 *         (setq ls (mapcar (lambda (x) (cdr x)) ls)))))
	 * </pre>
	 *
	 * The inner {@code mapcar}s are single-list, so they compile as the primitive;
	 * {@code (member nil ls)} is exactly "some list is exhausted", CL's termination rule
	 * for proper lists.
	 *
	 * <p>
	 * The six members differ only in the two axes below, which is why they share one
	 * wrapper: {@code mapcar}/{@code maplist} collect the values,
	 * {@code mapcan}/{@code mapcon} concatenate them, {@code mapc}/{@code mapl} discard
	 * them and answer the first list; and the {@code -l}/{@code -list}/{@code -con}
	 * members hand the function the successive cdrs themselves rather than their cars.
	 * @param name the operator, called in the single-list case and named by the wrapper
	 * @param tails whether the function receives the successive cdrs ({@code maplist})
	 * rather than their cars ({@code mapcar})
	 * @param accumulation what to do with each call's value
	 */
	private static WrapperDef mapFamilyWrapper(String name, boolean tails, MapAccumulation accumulation) {
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(callV("ls", callV(LispNames.CONS, new LispSymbol("l"), new LispSymbol("more"))));
		if (accumulation != MapAccumulation.DISCARD) {
			bindings.add(callV("acc", LispNil.INSTANCE));
		}
		LispVal result = switch (accumulation) {
			case COLLECT -> call(LispNames.REVERSE, "acc");
			case CONCATENATE -> new LispSymbol("acc");
			case DISCARD -> new LispSymbol("l");
		};
		LispVal exit = listToCons(List.of(callV(LispNames.MEMBER, LispNil.INSTANCE, new LispSymbol("ls")), result));
		// The arguments of one call: the cursors themselves, or their cars.
		LispVal callArgs = tails ? new LispSymbol("ls")
				: callV(LispNames.MAPCAR, projection(LispNames.CAR), new LispSymbol("ls"));
		LispVal apply = callV(LispNames.APPLY, new LispSymbol("f"), callArgs);
		LispVal step = switch (accumulation) {
			case COLLECT ->
				callV(LispNames.SETQ, new LispSymbol("acc"), callV(LispNames.CONS, apply, new LispSymbol("acc")));
			case CONCATENATE ->
				callV(LispNames.SETQ, new LispSymbol("acc"), callV(LispNames.APPEND, new LispSymbol("acc"), apply));
			case DISCARD -> apply;
		};
		LispVal advance = callV(LispNames.SETQ, new LispSymbol("ls"),
				callV(LispNames.MAPCAR, projection(LispNames.CDR), new LispSymbol("ls")));
		LispVal walk = listToCons(List.of(new LispSymbol(LispNames.DO), listToCons(bindings), exit, step, advance));
		LispVal dispatch = listToCons(
				List.of(new LispSymbol(LispNames.IF), call(LispNames.NULL, "more"), call(name, "f", "l"), walk));
		return new WrapperDef(name, List.of("f", "l", LispNames.LAMBDA_REST, "more"), List.of(dispatch));
	}

	/**
	 * The {@code #'every} / {@code #'some} wrapper. Like the {@code map*} family, the
	 * sequence count is a RUNTIME property here, so a fixed-arity wrapper cannot forward
	 * the extra sequences -- and unlike the {@code map*} family the arguments are
	 * SEQUENCES, so each is coerced to a list before the lockstep walk.
	 *
	 * <pre>
	 * (lambda (p s &amp;rest more)
	 *   (if (null more)
	 *       (every p s)                                        ; one sequence: the primitive
	 *       (do ((ss (mapcar (lambda (x) (coerce x 'list)) (cons s more)))
	 *            (r nil))
	 *           ((member nil ss) t)                            ; shortest-sequence exit
	 *         (setq r (apply p (mapcar (lambda (x) (car x)) ss)))
	 *         (if r nil (return nil))                          ; some: (if r (return r) nil)
	 *         (setq ss (mapcar (lambda (x) (cdr x)) ss)))))
	 * </pre>
	 * @param name the operator, called in the single-sequence case and named by the
	 * wrapper
	 * @param every true for {@code every}, false for {@code some}
	 */
	private static WrapperDef everySomeWrapper(String name, boolean every) {
		LispVal asLists = callV(LispNames.MAPCAR, coerceToListLambda(),
				callV(LispNames.CONS, new LispSymbol("s"), new LispSymbol("more")));
		List<LispVal> bindings = List.of(callV("ss", asLists), callV("r", LispNil.INSTANCE));
		LispVal exit = listToCons(List.of(callV(LispNames.MEMBER, LispNil.INSTANCE, new LispSymbol("ss")),
				every ? LispTrue.INSTANCE : LispNil.INSTANCE));
		LispVal apply = callV(LispNames.APPLY, new LispSymbol("p"),
				callV(LispNames.MAPCAR, projection(LispNames.CAR), new LispSymbol("ss")));
		LispVal record = callV(LispNames.SETQ, new LispSymbol("r"), apply);
		LispVal check = every
				? listToCons(List.of(new LispSymbol(LispNames.IF), new LispSymbol("r"), LispNil.INSTANCE,
						listToCons(List.of(new LispSymbol(LispNames.RETURN), LispNil.INSTANCE))))
				: listToCons(List.of(new LispSymbol(LispNames.IF), new LispSymbol("r"),
						listToCons(List.of(new LispSymbol(LispNames.RETURN), new LispSymbol("r"))), LispNil.INSTANCE));
		LispVal advance = callV(LispNames.SETQ, new LispSymbol("ss"),
				callV(LispNames.MAPCAR, projection(LispNames.CDR), new LispSymbol("ss")));
		LispVal walk = listToCons(
				List.of(new LispSymbol(LispNames.DO), listToCons(bindings), exit, record, check, advance));
		LispVal dispatch = listToCons(
				List.of(new LispSymbol(LispNames.IF), call(LispNames.NULL, "more"), call(name, "p", "s"), walk));
		return new WrapperDef(name, List.of("p", "s", LispNames.LAMBDA_REST, "more"), List.of(dispatch));
	}

	// (lambda (x) (coerce x 'list)) -- a string or vector sequence becomes a list of its
	// elements, a list passes through.
	private static LispVal coerceToListLambda() {
		return listToCons(List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of((LispVal) new LispSymbol("x"))),
				coerceTo("x", "LIST")));
	}

	// (lambda (x) (op x)) -- spelled inline rather than as #'car / #'cdr so the wrapper
	// body does not depend on another wrapper's setq having run first.
	private static LispVal projection(String op) {
		return listToCons(List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of((LispVal) new LispSymbol("x"))),
				call(op, "x")));
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

	// (name a &optional b) dispatching on b's presence -- the CL (last list &optional n)
	// shape. The dispatch is on b being non-nil, which is exact here: an omitted count
	// and an explicit nil one are not both legal, and 0 -- the one count that could be
	// mistaken for "absent" in another language -- is true in Lisp.
	private static WrapperDef unaryOptionalSecond(String name) {
		LispVal dispatch = listToCons(
				List.of(new LispSymbol(LispNames.IF), new LispSymbol("b"), call(name, "a", "b"), call(name, "a")));
		return new WrapperDef(name, List.of("a", LispNames.LAMBDA_OPTIONAL, "b"), List.of(dispatch));
	}

	// (name a b &optional c) dispatching on c's presence -- the CL subseq shape, whose
	// wrapper must accept the optional end (cl-ppcre funcalls #'subseq with 3 args).
	// Also getf's default, where the nil dispatch is exact: an omitted default and an
	// explicit nil one both answer nil on a miss.
	private static WrapperDef binaryOptionalThird(String name) {
		LispVal dispatch = listToCons(List.of(new LispSymbol(LispNames.IF), new LispSymbol("c"),
				call(name, "a", "b", "c"), call(name, "a", "b")));
		return new WrapperDef(name, List.of("a", "b", LispNames.LAMBDA_OPTIONAL, "c"), List.of(dispatch));
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
		// getf is plain data matching, and applied keyword plists read UPCASED under
		// the reader premise while these wrapper ASTs are authored lowercase: probe
		// the lowercase spelling first, then the upcased twin (getf is pure).
		LispVal lower = callV(LispNames.GETF, new LispSymbol("kw"), new LispSymbol(indicator));
		String upper = indicator.toUpperCase(java.util.Locale.ROOT);
		if (upper.equals(indicator)) {
			return lower;
		}
		LispVal upperGet = callV(LispNames.GETF, new LispSymbol("kw"), new LispSymbol(upper));
		return listToCons(List.of(new LispSymbol(LispNames.IF), lower, lower, upperGet));
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

	// Variadic wrapper for string= / string-equal: a plain two-argument call (the hot
	// :test #'string= shape) stays a direct call, and a first-class call carrying the
	// bounding-index keywords re-extracts them with getf and compares the designated
	// substrings -- the same subseq lowering the compilers apply to a literal call
	// (LispMacroExpander.expandStringComparisonBounds), so #'string= agrees with
	// string= and with the interpreter's Java-side keyword parsing.
	private static WrapperDef stringEquality(String name) {
		LispVal boundedA = callV(LispNames.SUBSEQ, new LispSymbol("a"),
				getfKwOr(LispNames.START1_KEYWORD, new LispInteger(0)), getfKw(LispNames.END1_KEYWORD));
		LispVal boundedB = callV(LispNames.SUBSEQ, new LispSymbol("b"),
				getfKwOr(LispNames.START2_KEYWORD, new LispInteger(0)), getfKw(LispNames.END2_KEYWORD));
		LispVal bounded = listToCons(List.of(new LispSymbol(name), boundedA, boundedB));
		LispVal body = listToCons(
				List.of(new LispSymbol(LispNames.IF), new LispSymbol("kw"), bounded, call(name, "a", "b")));
		return new WrapperDef(name, List.of("a", "b", LispNames.LAMBDA_REST, "kw"), List.of(body));
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
		LispVal rendered = am.ik.rontolisp.macro.FormatRenderer.call(new LispSymbol("ctrl"), new LispSymbol("r"));
		LispVal bindings = listToCons(List.of((LispVal) listToCons(List.of(strVar, rendered))));
		LispVal writeForm = listToCons(List.of(new LispSymbol(LispNames.PROGN),
				callV(LispNames.WRITE_STRING, strVar, new LispSymbol("dest")), LispNil.INSTANCE));
		LispVal ifForm = listToCons(
				List.of(new LispSymbol(LispNames.IF), call(LispNames.NULL, "dest"), strVar, writeForm));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET), bindings, ifForm));
		return new WrapperDef(LispNames.FORMAT, List.of("dest", "ctrl", LispNames.LAMBDA_REST, "r"), List.of(body));
	}

	// #'concatenate wrapper (gated by REFERENCE_GATED_FUNCTIONS): the result type is a
	// RUNTIME value here, so the family dispatch that ConcatenateForms performs at
	// expansion time is re-done with member over the designator (its head, for a compound
	// spec like '(vector (unsigned-byte 8))). Every family accepts any sequence argument,
	// exactly like the call-position lowering: the string family normalizes each argument
	// before the %string-concat fold, the list / vector families walk elements through
	// (coerce x 'list). An unsupported designator signals, matching the interpreter.
	private static WrapperDef concatenateWrapper() {
		LispSymbol head = new LispSymbol("__cc_head");
		LispVal headOfSpec = listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.CONSP, "type"),
				call(LispNames.CAR, "type"), new LispSymbol("type")));
		// (reduce (lambda (a x) (%string-concat a (if (stringp x) x (coerce x 'string))))
		// seqs :initial-value ""): the same "any character sequence" contract the
		// call-position lowering gets from %seq-string, spelled inline here because the
		// wrapper must stand alone (its own injection is gated separately).
		LispVal step = listToCons(
				List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(new LispSymbol("a"), new LispSymbol("x"))),
						callV(LispNames.STRING_CONCAT, new LispSymbol("a"),
								listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.STRINGP, "x"),
										new LispSymbol("x"), coerceTo("x", "STRING"))))));
		LispVal strings = listToCons(List.of(new LispSymbol(LispNames.REDUCE), step, new LispSymbol("seqs"),
				new LispSymbol(LispNames.INITIAL_VALUE_KEYWORD), new LispString("")));
		LispVal vector = listToCons(List.of(new LispSymbol(LispNames.COERCE), concatenatedElements(),
				listToCons(List.of(new LispSymbol(LispNames.QUOTE), new LispSymbol("VECTOR")))));
		LispVal unsupported = listToCons(
				List.of(new LispSymbol(LispNames.ERROR), new LispString("concatenate: unsupported result type")));
		LispVal dispatch = listToCons(
				List.of(new LispSymbol(LispNames.IF),
						memberOf(head, "STRING", "SIMPLE-STRING", "BASE-STRING", "SIMPLE-BASE-STRING"), strings,
						listToCons(List.of(new LispSymbol(LispNames.IF), memberOf(head, "LIST", "CONS"),
								concatenatedElements(),
								listToCons(List.of(
										new LispSymbol(LispNames.IF), memberOf(head, "VECTOR", "SIMPLE-VECTOR", "ARRAY",
												"SIMPLE-ARRAY", "BIT-VECTOR", "SIMPLE-BIT-VECTOR"),
										vector, unsupported))))));
		LispVal bindings = listToCons(List.of((LispVal) listToCons(List.of(head, headOfSpec))));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET), bindings, dispatch));
		return new WrapperDef(LispNames.CONCATENATE, List.of("type", LispNames.LAMBDA_REST, "seqs"), List.of(body));
	}

	// %seq-string (gated by ConcatenateForms.needsSeqString in the backend compilers):
	// one
	// character sequence as a string. It exists so the concatenate 'string lowering can
	// normalize an argument with a CALL rather than an inlined (coerce x 'string) loop --
	// json.lisp / url.lisp alone hold dozens of concatenate sites, and no single emitted
	// body may grow without bound (.kb/wasm-function-body-size.md). A string passes
	// through untouched, so the fast path is one stringp test.
	private static WrapperDef seqStringWrapper() {
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.STRINGP, "x"),
				new LispSymbol("x"), coerceTo("x", "STRING")));
		return new WrapperDef(LispNames.SEQ_STRING, List.of("x"), List.of(body));
	}

	// Every argument's elements, in order, in a FRESH list:
	// (append (reduce (lambda (a x) (append a (coerce x 'list))) seqs :initial-value nil)
	// nil) -- the outer append is what copies the last argument too. Built per use so the
	// two dispatch arms never share one AST node.
	private static LispVal concatenatedElements() {
		LispVal step = listToCons(
				List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(new LispSymbol("a"), new LispSymbol("x"))),
						callV(LispNames.APPEND, new LispSymbol("a"), coerceTo("x", "LIST"))));
		LispVal reduced = listToCons(List.of(new LispSymbol(LispNames.REDUCE), step, new LispSymbol("seqs"),
				new LispSymbol(LispNames.INITIAL_VALUE_KEYWORD), LispNil.INSTANCE));
		return callV(LispNames.APPEND, reduced, LispNil.INSTANCE);
	}

	// (coerce <var> '<type>)
	private static LispVal coerceTo(String var, String type) {
		return listToCons(List.of(new LispSymbol(LispNames.COERCE), new LispSymbol(var),
				listToCons(List.of(new LispSymbol(LispNames.QUOTE), new LispSymbol(type)))));
	}

	// (member x '(name...))
	private static LispVal memberOf(LispSymbol x, String... names) {
		List<LispVal> symbols = new ArrayList<>();
		for (String name : names) {
			symbols.add(new LispSymbol(name));
		}
		return callV(LispNames.MEMBER, x, listToCons(List.of(new LispSymbol(LispNames.QUOTE), listToCons(symbols))));
	}

	/**
	 * The {@code #'open} wrapper: {@code (apply #'open path plist)} is the portable way
	 * to build an option list at run time (alexandria's {@code with-input-from-file}),
	 * but the compiled {@code open} needs its direction and element type as LITERALS (the
	 * file mode is picked statically). The wrapper therefore dispatches the plist onto
	 * the four literal shapes rather than forwarding the options.
	 */
	private static WrapperDef openWrapper() {
		LispSymbol opts = new LispSymbol("o");
		LispSymbol path = new LispSymbol("p");
		LispVal binaryType = listToCons(List.of(new LispSymbol(LispNames.QUOTE),
				listToCons(List.of(new LispSymbol(LispNames.UNSIGNED_BYTE), new LispInteger(8)))));
		LispVal isOutput = callV(LispNames.EQ_GENERAL,
				callV(LispNames.GETF, opts, new LispSymbol(LispNames.DIRECTION_KEYWORD)),
				new LispSymbol(LispNames.OUTPUT_KEYWORD));
		LispVal isBinary = callV(LispNames.EQUAL,
				callV(LispNames.GETF, opts, new LispSymbol(LispNames.ELEMENT_TYPE_KEYWORD)), binaryType);
		LispVal binaryBranch = listToCons(List.of(new LispSymbol(LispNames.IF), isOutput,
				callV(LispNames.OPEN, path, new LispSymbol(LispNames.OUTPUT_KEYWORD), binaryType),
				callV(LispNames.OPEN, path, new LispSymbol(LispNames.INPUT_KEYWORD), binaryType)));
		LispVal textBranch = listToCons(List.of(new LispSymbol(LispNames.IF), isOutput,
				callV(LispNames.OPEN, path, new LispSymbol(LispNames.OUTPUT_KEYWORD)),
				callV(LispNames.OPEN, path, new LispSymbol(LispNames.INPUT_KEYWORD))));
		return new WrapperDef(LispNames.OPEN, List.of("p", LispNames.LAMBDA_REST, "o"),
				List.of(listToCons(List.of(new LispSymbol(LispNames.IF), isBinary, binaryBranch, textBranch))));
	}

	private static final List<WrapperDef> WRAPPER_DEFS = List.of(
			// Signal operators and format (gated by REFERENCE_GATED_FUNCTIONS in the
			// backend compilers)
			signalDatum(LispNames.ERROR, LispNames.ERROR), signalDatum(LispNames.SIGNAL, LispNames.SIGNAL),
			signalDatum(LispNames.WARN, LispNames.WARN), cerrorWrapper(), formatWrapper(), concatenateWrapper(),
			// %seq-string: injected only when a concatenate 'string lowering needs it
			// (gated on ConcatenateForms.needsSeqString, not on a #'name reference).
			seqStringWrapper(),
			// #'open (reference-gated): dispatches an option plist onto the four literal
			// direction/element-type shapes the compiled open needs.
			openWrapper(),
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
			unary(LispNames.LENGTH), unary(LispNames.REVERSE), unaryOptionalSecond(LispNames.LAST),
			unary(LispNames.BUTLAST), binary(LispNames.MEMBER), binary(LispNames.MEMBER_IF), binary(LispNames.FIND),
			binary(LispNames.FIND_IF), binary(LispNames.FIND_IF_NOT), positionFamily(LispNames.POSITION, true),
			positionFamily(LispNames.POSITION_IF, false), positionFamily(LispNames.POSITION_IF_NOT, false),
			binary(LispNames.COUNT), binary(LispNames.COUNT_IF), binary(LispNames.ASSOC), binary(LispNames.ASSOC_IF),
			binary(LispNames.RASSOC), binary(LispNames.RASSOC_IF), ternary(LispNames.ACONS), binary(LispNames.PAIRLIS),
			unary(LispNames.COPY_ALIST), binaryOptionalThird(LispNames.GETF), unary(LispNames.REMOVE_DUPLICATES),
			variadicNconc(), unary(LispNames.IDENTITY), unary(LispNames.COPY_LIST), unary(LispNames.NREVERSE),
			unary(LispNames.MAKE_LIST), binary(LispNames.UNION), binary(LispNames.INTERSECTION),
			binary(LispNames.SET_DIFFERENCE), binary(LispNames.ADJOIN),
			// every/some carry ANY number of sequences, the same as in call position.
			everySomeWrapper(LispNames.EVERY, true), everySomeWrapper(LispNames.SOME, false), binary(LispNames.REMOVE),
			binary(LispNames.REMOVE_IF), binary(LispNames.REMOVE_IF_NOT), binary(LispNames.DELETE),
			binary(LispNames.DELETE_IF), binary(LispNames.DELETE_IF_NOT), ternary(LispNames.SUBSTITUTE),
			ternary(LispNames.NSUBSTITUTE), binary(LispNames.SORT), variadicStableSort(), unary(LispNames.COPY_SEQ),
			// The mapping family as first-class values (alexandria hands #'mapcar to
			// its own combinators). Every member carries ANY number of lists, the same
			// as in call position -- see mapFamilyWrapper.
			mapFamilyWrapper(LispNames.MAPCAR, false, MapAccumulation.COLLECT),
			mapFamilyWrapper(LispNames.MAPC, false, MapAccumulation.DISCARD),
			mapFamilyWrapper(LispNames.MAPCAN, false, MapAccumulation.CONCATENATE),
			mapFamilyWrapper(LispNames.MAPLIST, true, MapAccumulation.COLLECT),
			mapFamilyWrapper(LispNames.MAPCON, true, MapAccumulation.CONCATENATE),
			mapFamilyWrapper(LispNames.MAPL, true, MapAccumulation.DISCARD),
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
			binary(LispNames.LOGANDC1), binary(LispNames.LOGANDC2), binary(LispNames.LOGORC1),
			binary(LispNames.LOGORC2), binary(LispNames.ASH), unary(LispNames.INTEGER_LENGTH),
			binary(LispNames.LOGBITP),
			// Byte-field operations (macro-lowered to list/car/ash/logand/logior/lognot)
			binary(LispNames.BYTE), unary(LispNames.BYTE_SIZE), unary(LispNames.BYTE_POSITION), binary(LispNames.LDB),
			ternary(LispNames.DPB), binary(LispNames.MASK_FIELD), binary(LispNames.SCALE_FLOAT),
			// open as a first-class value: (apply #'open path options) is the portable
			// way to build an option list at run time (alexandria's
			// with-input-from-file).
			// The wrapper takes the positional shape the built-in compiles.
			// Lite stream/type introspection stubs (macro-lowered; slot-boundp and
			// slot-makunbound are omitted -- their expansions need a literal slot name)
			unary(LispNames.PROBE_FILE), unary(LispNames.FILE_POSITION), unary(LispNames.FILE_LENGTH),
			unary(LispNames.PATHNAMEP), unary(LispNames.INPUT_STREAM_P), unary(LispNames.OUTPUT_STREAM_P),
			unary(LispNames.STREAM_ELEMENT_TYPE), unary(LispNames.CLASS_OF),
			unary(LispNames.SIMPLE_CONDITION_FORMAT_CONTROL), unary(LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS),
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
			binaryOptionalThird(LispNames.SUBSEQ), stringEquality(LispNames.STRING_EQ),
			stringEquality(LispNames.STRING_EQUAL), binary(LispNames.STRING_TRIM), binary(LispNames.STRING_LEFT_TRIM),
			binary(LispNames.STRING_RIGHT_TRIM),
			// Character operations
			binary(LispNames.CHAR), binary(LispNames.SCHAR), unary(LispNames.CHAR_CODE), unary(LispNames.CODE_CHAR),
			unary(LispNames.CHAR_UPCASE), unary(LispNames.CHAR_DOWNCASE), unary(LispNames.CHARACTERP),
			unary(LispNames.ALPHA_CHAR_P), unary(LispNames.LOWER_CASE_P), unary(LispNames.UPPER_CASE_P),
			unary(LispNames.CONSTANTP), unary(LispNames.STREAMP), unary(LispNames.SIMPLE_STRING_P),
			unary(LispNames.DIGIT_CHAR_P), binary(LispNames.CHAR_EQ), binary(LispNames.CHAR_LT),
			binary(LispNames.CHAR_LE), binary(LispNames.CHAR_GT), binary(LispNames.CHAR_GE), binary(LispNames.CHAR_NE),
			binary(LispNames.CHAR_EQUAL),
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
			// #'aref: variadic (CL aref takes one subscript per dimension) -- fold the
			// subscripts into the row-major index over the array's dimensions. Gated
			// with the fill-pointer array group so array-free programs stay
			// byte-identical.
			new WrapperDef(LispNames.AREF, List.of("a", LispNames.LAMBDA_REST, "idx"), List.of(arefFoldBody())),
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
			// The signalling read family (all three REFERENCE_GATED_FUNCTIONS): read-char
			// keeps its 0-arity stdin shape, peek-char carries the peek-type + stream a
			// funcall would pass, read-byte's stream argument is mandatory.
			new WrapperDef(LispNames.READ_CHAR, List.of(), List.of(call(LispNames.READ_CHAR))),
			new WrapperDef(LispNames.PEEK_CHAR, List.of(LispNames.LAMBDA_OPTIONAL, "a", "b"),
					List.of(call(LispNames.PEEK_CHAR, "a", "b"))),
			unary(LispNames.READ_BYTE),
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
