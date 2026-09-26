package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.ArrayElementTypes;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

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
	 * / {@code sinh} / {@code cosh}) left when the WASM backend gained every
	 * transcendental built-in (today the fdlibm runtime,
	 * {@code codegen.wasm.WasmFdlibmRuntimeBuilder}), so every {@code #'} first-class
	 * value is supported. Kept as the seam for any future built-in a backend cannot
	 * compile.
	 */
	public static final Set<String> WASM_UNSUPPORTED = Set.of();

	/**
	 * Hash-table operator wrappers whose compiled bodies reference runtime helpers (JVM)
	 * or inline code (WASM) that each backend emits only when the program actually uses a
	 * hash table. They are injected as first-class wrappers only when the program uses
	 * any hash-table operator, so the wrapper and its helpers stay gated together (see
	 * the {@code wrapperExcludes} handling in {@code Jvm/WasmLispCompiler}).
	 * {@code %puthash} is internal and intentionally excluded; {@code make-hash-table}
	 * takes keyword arguments and is exposed here only as the DEFAULT table -- it accepts
	 * the initargs as a {@code &rest} tail and drops them, rather than declaring itself
	 * nullary, so {@code (apply #'make-hash-table initargs)} is not a wrong argument
	 * count.
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
	 *
	 * <p>
	 * {@code fill}/{@code coerce}/{@code vector}/{@code read-sequence}/
	 * {@code write-sequence}/{@code svref}/{@code array-rank}/{@code array-dimension}/
	 * {@code array-total-size}/{@code array-row-major-index} are on the list for the same
	 * reason: their wrapper bodies reach {@code _aset1}/{@code _charVecMake}/
	 * {@code _arrayMake}/{@code _aref1}/{@code _arrayDims}, which JVM emits only under
	 * the array gate. Left off, every non-array program still carried these ten wrappers
	 * (nothing gated them), the finished class called methods it never declared, and the
	 * post-compile self-check answered that by forcing the array gate on and re-running
	 * the whole compile -- for a program with no array in it. Every one of the ten is
	 * already a name the array gate itself scans for on the JVM
	 * ({@code LispMacroExpander.usesGeneralArrayOp}) and on WASM
	 * ({@code WasmLispCompiler.programUsesAnyArrayOp}), so a program that takes one of
	 * them as a value keeps its wrapper: the same reference that would otherwise leave it
	 * excluded is what turns the gate on.
	 */
	public static final Set<String> ARRAY_FILL_POINTER_FUNCTIONS = Set.of(LispNames.FILL_POINTER,
			LispNames.ARRAY_HAS_FILL_POINTER_P, LispNames.ADJUSTABLE_ARRAY_P, LispNames.ARRAY_ELEMENT_TYPE,
			LispNames.VECTOR_PUSH, LispNames.VECTOR_POP, LispNames.VECTOR_PUSH_EXTEND, LispNames.ADJUST_ARRAY,
			LispNames.ARRAY_DISPLACEMENT, LispNames.MAKE_ARRAY, LispNames.AREF, LispNames.MAKE_STRING, LispNames.FILL,
			LispNames.COERCE, LispNames.VECTOR, LispNames.READ_SEQUENCE, LispNames.WRITE_SEQUENCE, LispNames.SVREF,
			LispNames.ARRAY_RANK, LispNames.ARRAY_DIMENSION, LispNames.ARRAY_TOTAL_SIZE,
			LispNames.ARRAY_ROW_MAJOR_INDEX);

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
		gated.add(LispNames.READ_CHAR_NO_HANG);
		gated.add(LispNames.PEEK_CHAR);
		gated.add(LispNames.READ_BYTE);
		// #'read-sequence / #'write-sequence for the same reason: their
		// boundedSequenceIo bodies re-enter the operator's own expansion, whose bounds
		// check constructs a type-error instance -- machinery a program that never
		// takes them as values should not carry (and which the mayCreateInstances
		// gate would not see coming -- it scans the source program, not the injected
		// wrappers; taking one as a value forces instances through the FUNCTION
		// case of constructsInstance instead).
		gated.add(LispNames.READ_SEQUENCE);
		gated.add(LispNames.WRITE_SEQUENCE);
		// #'class-of resolves through the generated %find-class metaobject runtime,
		// which LispMacroExpander injects only for a program that references class-of
		// (or find-class) itself -- the injected wrapper body is outside that scan.
		gated.add(LispNames.CLASS_OF);
		// #'make-instance forwards to the generated %mop-make-instance runtime-class
		// construction defun, which LispMacroExpander injects (with arms widened to
		// every registered class) exactly when the program takes make-instance as a
		// value -- the (apply #'make-instance class args) idiom of postmodern's
		// make-dao.
		gated.add(LispNames.MAKE_INSTANCE);
		// #'file-length / #'file-write-date call JVM runtime helpers the compiler emits
		// only for a program that NAMES the operator -- and that gate scans the source
		// program, not the injected wrappers, so an ungated wrapper would call a method
		// the class does not declare (the self-call check catches it, loudly). Gating the
		// wrappers on the same reference puts both sides on one scan.
		gated.add(LispNames.FILE_LENGTH);
		gated.add(LispNames.FILE_WRITE_DATE);
		// #'file-position joins them for the same reason: its wrapper body lowers to the
		// gated _filePosition JVM helper.
		gated.add(LispNames.FILE_POSITION);
		// #'sleep for a sharper reason: under --component `sleep` lowers to
		// (await (wait-for ms)), which puts the module in async (and therefore EH) mode.
		// An ungated wrapper would do that to EVERY component -- changing the wasmtime
		// flags of programs that never sleep -- and the mode gate, which scans the source
		// program, would not even see it coming.
		gated.add(LispNames.SLEEP);
		// #'find-symbol: the wrapper body lowers to intern (the computed-name
		// deviation, .kb/symbol-runtime-api.md), and the WASM _intern runtime is
		// gated -- so the wrapper is injected only for a program that actually takes
		// find-symbol as a value (trivia level2's (remove-if-not #'find-symbol ...)).
		gated.add(LispNames.FIND_SYMBOL);
		// #'typep: the wrapper's specifier is a PARAMETER, so its body compiles to a
		// call of the shared %typep-runtime dispatch defun -- injected by
		// expandTopLevelDefinitions only for a program whose own source needs it
		// (LispMacroExpander.needsRuntimeTypep, which counts a (function typep) for
		// exactly this reason). Ungated, every program carried a wrapper calling a defun
		// it does not have.
		gated.add(LispNames.TYPEP);
		// #'subtypep for the same reason: its specifiers are parameters, so the body
		// calls the %subtypep-runtime dispatch defun, injected only for a program whose
		// own source needs it (LispMacroExpander.needsRuntimeSubtypep, which counts a
		// (function subtypep)).
		gated.add(LispNames.SUBTYPEP);
		// #'coerce for the same reason, one step removed: the wrapper's result type is a
		// PARAMETER, so its body takes the computed-coerce dispatch, whose "already of
		// that type" arm is a computed typep -- the same %typep-runtime defun, gated by
		// the same scan (which counts a (function coerce) beside the (function typep)).
		gated.add(LispNames.COERCE);
		// #'map-into for the same shape: the wrapper stores through (setf (elt ...)),
		// whose string arm calls the gated %schar-set-runtime helper
		// (LispMacroExpander's reachesScharSet scan, which names map-into).
		gated.add(LispNames.MAP_INTO);
		// #'symbol-value: the wrapper body is the raw eval-mirror probe (or, in a
		// progv-using program, the dynamic-first dispatch), and the _genv/_env_lookup
		// machinery it calls is real only under usesEval -- whose scan sees the source
		// program, not the injected wrappers. The (function symbol-value) spelling that
		// injects this wrapper is ALSO a symbol occurrence that scan counts, so gating
		// on the reference puts both sides on one scan (cl-json's aggregate-scope
		// (mapcar #'symbol-value scope-variables) is the consumer).
		gated.add(LispNames.SYMBOL_VALUE);
		// #'set for the same reason: the wrapper body is the backing-store chain plus
		// the _store mirror call, real only under usesEval, and the (function set)
		// spelling fires the same scan.
		gated.add(LispNames.SET);
		// #'widen-float-bits / #'narrow-float-bits (.todo/671): the wrapper bodies call
		// the JVM's _widenFloatBits/_narrowFloatBits helpers, emitted only for a
		// program whose OWN source names widen-float-bits/narrow-float-bits
		// (JvmLispCompiler's usesFloat16Bits scan) -- a program that only calls
		// float16-bits/bits-float16 (the scalar pair, which needs no helper) would
		// otherwise carry an ungated wrapper calling a method the class does not
		// declare, exactly FILE_LENGTH/FILE_WRITE_DATE's reason above.
		gated.add(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WIDEN_FLOAT_BITS));
		gated.add(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.NARROW_FLOAT_BITS));
		// The quantized-matrix primitives for the same reason: their JVM helpers are
		// emitted only for a program that can build one (JvmLispCompiler's
		// usesQuantized scan), and the WASM backends refuse two of them at compile time.
		gated.add(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZE));
		gated.add(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.DEQUANTIZE));
		gated.add(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_QUANTIZED_MATRIX));
		gated.add(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_ROWS));
		gated.add(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_MATRIX_P));
		REFERENCE_GATED_FUNCTIONS = Set.copyOf(gated);
	}

	/**
	 * The wrappers whose BODY calls {@code apply}: the {@code map*} family, {@code every}
	 * / {@code some}, {@code funcall} and {@code apply} themselves. All of them forward a
	 * runtime number of arguments, which is what {@code apply} is for.
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
			LispNames.MAPLIST, LispNames.MAPCON, LispNames.MAPL, LispNames.EVERY, LispNames.SOME, LispNames.NOTANY,
			LispNames.NOTEVERY, LispNames.MAP, LispNames.MAP_INTO, LispNames.FUNCALL, LispNames.APPLY);

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
	 * The multiple-value producers other than the floor family -- the operators whose
	 * wrapper publishes its second value ({@code LispMacroExpander.settleWrapperLambdas})
	 * only in a program that names it as a designator.
	 */
	public static final Set<String> VALUE_PUBLISHING_PRODUCERS = Set.of(LispNames.GETHASH, LispNames.FIND_SYMBOL,
			LispNames.INTERN, LispNames.SUBTYPEP, LispNames.READ_FROM_STRING, LispNames.ARRAY_DISPLACEMENT);

	/**
	 * The {@link #VALUE_PUBLISHING_PRODUCERS} a program names as a function designator
	 * ({@link #referencesFunctionDesignator}) -- the ones whose wrapper publishes its
	 * second value. Every other program carries the one-value wrapper it always did, so
	 * its output stays byte-identical; what it gives up is the second value of a wrapper
	 * reached through a designator computed at run time or through {@code eval}.
	 * @param program the top-level forms
	 * @param extraForms further forms that count as the program's (a condition's
	 * {@code :report} lambdas)
	 * @return the named producers
	 */
	public static Set<String> designatedValueProducers(List<LispVal> program,
			java.util.Collection<LispVal> extraForms) {
		Set<String> spelled = functionDesignatorNames(program);
		spelled.addAll(functionDesignatorNames(extraForms));
		Set<String> named = new java.util.HashSet<>();
		for (String op : VALUE_PUBLISHING_PRODUCERS) {
			if (spelled.contains(op)) {
				named.add(op);
			}
		}
		return named;
	}

	/**
	 * Whether the expression names the operator as a function DESIGNATOR -- either the
	 * {@code #'name} shape {@link #referencesFunctionValue} scans for or the
	 * {@code 'name} one, which {@code compiler.FunctionDesignators.normalize} turns into
	 * the first in designator position. A gate that decides whether to inject a wrapper
	 * AT ALL has to count both: dropping the wrapper leaves
	 * {@code (funcall 'mapcar #'1+ l)} with no function to call, and the failure is a
	 * compile error, not a fallback.
	 *
	 * <p>
	 * Deliberately blind to POSITION -- a {@code 'name} inside quoted data counts too.
	 * The cost of over-counting is one injected wrapper; the cost of under-counting is a
	 * program that no longer compiles.
	 * @param expr the expression to scan
	 * @param name the operator name
	 * @return {@code true} when a {@code (function name)} or {@code (quote name)} occurs
	 */
	public static boolean referencesFunctionDesignator(LispVal expr, String name) {
		LispVal node = expr;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol op
					&& (LispNames.FUNCTION.equals(op.name()) || LispNames.QUOTE.equals(op.name()))
					&& cons.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol sym
					&& name.equals(sym.name())) {
				return true;
			}
			if (referencesFunctionDesignator(cons.car(), name)) {
				return true;
			}
			node = cons.cdr();
		}
		return false;
	}

	/**
	 * Whether the expression spells one of the names as a SYMBOL CONSTANT: anywhere
	 * inside a {@code (quote ...)} datum, at any depth (list, dotted tail or vector
	 * element), or as {@code (function name)}. That is exactly how a symbol designator
	 * naming a built-in can exist at run time in a program with no symbol builder and no
	 * data evaluator -- the name registry answers only names the program loads as values
	 * -- so a computed {@code funcall} target can reach one of the names' wrappers only
	 * when this answers true.
	 * @param expr the expression to scan
	 * @param names the operator names
	 * @return {@code true} when one of the names occurs as a symbol constant
	 */
	public static boolean spellsSymbolConstant(LispVal expr, Set<String> names) {
		LispVal node = expr;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol op
					&& (LispNames.QUOTE.equals(op.name()) || LispNames.FUNCTION.equals(op.name()))) {
				return mentionsSymbol(cons.cdr(), names);
			}
			if (spellsSymbolConstant(cons.car(), names)) {
				return true;
			}
			node = cons.cdr();
		}
		return false;
	}

	private static boolean mentionsSymbol(LispVal datum, Set<String> names) {
		while (datum instanceof LispCons cons) {
			if (mentionsSymbol(cons.car(), names)) {
				return true;
			}
			datum = cons.cdr();
		}
		if (datum instanceof LispSymbol sym) {
			return names.contains(sym.name());
		}
		if (datum instanceof am.ik.rontolisp.LispArray array) {
			for (LispVal element : array.data()) {
				if (mentionsSymbol(element, names)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Every name the forms take as a first-class function value: the answers of
	 * {@link #referencesFunctionValue} for all names in one walk, for a gate that asks
	 * about many operators (each asking walked the whole program again).
	 * @param forms the expressions to scan
	 * @return the names some {@code (function name)} form spells
	 */
	public static Set<String> functionValueNames(Iterable<? extends LispVal> forms) {
		Set<String> names = new java.util.HashSet<>();
		for (LispVal form : forms) {
			collectDesignatorNames(form, false, names);
		}
		return names;
	}

	/**
	 * Every name the forms spell as a function designator: the answers of
	 * {@link #referencesFunctionDesignator} for all names in one walk.
	 * @param forms the expressions to scan
	 * @return the names some {@code (function name)} or {@code (quote name)} form spells
	 */
	public static Set<String> functionDesignatorNames(Iterable<? extends LispVal> forms) {
		Set<String> names = new java.util.HashSet<>();
		for (LispVal form : forms) {
			collectDesignatorNames(form, true, names);
		}
		return names;
	}

	// The walk of referencesFunctionValue / referencesFunctionDesignator (every cons
	// reachable through car and cdr), collecting instead of matching one name.
	private static void collectDesignatorNames(LispVal expr, boolean quoted, Set<String> names) {
		LispVal x = expr;
		while (x instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol op
					&& (LispNames.FUNCTION.equals(op.name()) || quoted && LispNames.QUOTE.equals(op.name()))
					&& cons.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol sym) {
				names.add(sym.name());
			}
			collectDesignatorNames(cons.car(), quoted, names);
			x = cons.cdr();
		}
	}

	/**
	 * Whether the expression takes the named operator as a first-class function value --
	 * a {@code (function name)} form (the {@code #'name} reader shape).
	 * @param expr the expression to scan
	 * @param name the operator name
	 * @return {@code true} when a {@code (function name)} reference occurs
	 */
	public static boolean referencesFunctionValue(LispVal expr, String name) {
		LispVal node = expr;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol op && LispNames.FUNCTION.equals(op.name())
					&& cons.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol sym
					&& name.equals(sym.name())) {
				return true;
			}
			if (referencesFunctionValue(cons.car(), name)) {
				return true;
			}
			node = cons.cdr();
		}
		return false;
	}

	/**
	 * Every wrapped operator name -- by construction a built-in FUNCTION both expression
	 * compilers lower in call position (each wrapper body uses its operator exactly
	 * there). {@code ShadowedBuiltins} unions this with its own list of lowered-but-
	 * unwrappable names to decide when a user {@code defmethod} shadows a built-in.
	 * @return the wrapped operator names
	 */
	public static Set<String> names() {
		return WRAPPER_NAMES;
	}

	/**
	 * The operator a wrong-argument-count report names for a function of this name: the
	 * name itself when it is a wrapped built-in, {@code null} (reported as
	 * {@code ClosRegistry.ARITY_ANONYMOUS_OPERATOR}) for anything else. One rule for all
	 * four backends -- the interpreter asks it of a lambda's name, the compiled backends
	 * of a callable's -- decided by the NAME, because the interpreter's catalog lambda
	 * and the compilers' injected wrapper defun are the same function under it.
	 * @param functionName the callee's name, or {@code null} for an anonymous one
	 * @return the operator to report, or {@code null}
	 */
	public static @Nullable String arityOperator(@Nullable String functionName) {
		return functionName != null && WRAPPER_NAMES.contains(functionName) ? functionName : null;
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
	/**
	 * Every wrapper catalog name, for the apply-runtime gate
	 * ({@code LispMacroExpander.needsApplyRuntime}): an {@code apply} whose literal
	 * target is one of these compiles to a direct call of the injected wrapper. The
	 * caller subtracts its backend's exclusions.
	 * @return the names of every {@code WRAPPER_DEFS} entry
	 */
	public static Set<String> wrapperNames() {
		Set<String> names = new java.util.HashSet<>();
		for (WrapperDef def : WRAPPER_DEFS) {
			names.add(def.name);
		}
		return names;
	}

	/**
	 * The {@code (setq name (lambda ...))} wrapper forms to splice, one per
	 * {@code WRAPPER_DEFS} entry the program does not define itself and the caller has
	 * not excluded.
	 * @param userDefinedNames names the program defines (its own definition wins)
	 * @param excludedNames names the caller's gates keep out (e.g. wrappers whose bodies
	 * would force a runtime tier the program otherwise avoids)
	 * @return the wrapper forms
	 */
	public static List<LispVal> generate(Set<String> userDefinedNames, Set<String> excludedNames) {
		return generate(userDefinedNames, excludedNames, Set.of());
	}

	/**
	 * As {@link #generate(Set, Set)}, with the full lambda list ({@link #VALUE_SHAPES})
	 * for the multiple-value producers the program names as a designator
	 * ({@link #designatedValueProducers}).
	 * @param userDefinedNames names the program defines (its own definition wins)
	 * @param excludedNames names the caller's gates keep out
	 * @param designatedProducers the producers whose value shape is injected
	 * @return the wrapper forms
	 */
	public static List<LispVal> generate(Set<String> userDefinedNames, Set<String> excludedNames,
			Set<String> designatedProducers) {
		List<LispVal> wrappers = new ArrayList<>();
		for (WrapperDef def : WRAPPER_DEFS) {
			if (!userDefinedNames.contains(def.name) && !excludedNames.contains(def.name)) {
				WrapperDef valueShape = designatedProducers.contains(def.name) ? VALUE_SHAPES.get(def.name) : null;
				wrappers.add((valueShape != null ? valueShape : def).toSetqLambda());
			}
		}
		return wrappers;
	}

	private static final String DEFAULT_TRUE = "=t";

	// (read-char &optional s (e t) v r) -- CL's whole optional tail: the eof-error-p
	// default is true and recursive-p is accepted and dropped. read-char's wrapper is
	// REFERENCE_GATED, so the end-of-file construction a computed eof-error-p implies
	// is gated with it.
	private static WrapperDef optionalStreamEof(String name) {
		return new WrapperDef(name, List.of(LispNames.LAMBDA_OPTIONAL, "s", "e" + DEFAULT_TRUE, "v", "r"),
				List.of(call(name, "s", "e", "v")));
	}

	// (read-line &optional s (e t) v r): read-line's wrapper is NOT reference-gated, so a
	// computed eof-error-p -- an end-of-file construction the instance gate cannot see
	// coming -- must not reach its body. A true one keeps the one-argument call (the
	// wrapper's shape before it took the tail), a false one the literal-nil call that
	// answers the eof-value.
	private static WrapperDef readLineWrapper() {
		LispVal dispatch = listToCons(List.of(new LispSymbol(LispNames.IF), new LispSymbol("e"),
				call(LispNames.READ_LINE, "s"), listToCons(List.of(new LispSymbol(LispNames.READ_LINE),
						new LispSymbol("s"), LispNil.INSTANCE, new LispSymbol("v")))));
		return new WrapperDef(LispNames.READ_LINE,
				List.of(LispNames.LAMBDA_OPTIONAL, "s", "e" + DEFAULT_TRUE, "v", "r"), List.of(dispatch));
	}

	private record WrapperDef(String name, List<String> params, List<LispVal> body) {

		// (lambda (params...) body...) -- the function VALUE itself, without the setq
		// that binds it to the operator's name on the compile paths.
		LispVal toLambda() {
			LispVal paramList = listToCons(params.stream().map(WrapperDef::param).toList());
			List<LispVal> lambdaParts = new ArrayList<>();
			lambdaParts.add(new LispSymbol(LispNames.LAMBDA));
			lambdaParts.add(paramList);
			lambdaParts.addAll(body);
			return listToCons(lambdaParts);
		}

		// A parameter spelled "name=t" is the optional (name t): the read family's
		// eof-error-p, whose omitted default is TRUE, unlike a bound nil.
		private static LispVal param(String p) {
			if (p.endsWith(DEFAULT_TRUE)) {
				return listToCons(
						List.of(new LispSymbol(p.substring(0, p.length() - DEFAULT_TRUE.length())), LispTrue.INSTANCE));
			}
			return new LispSymbol(p);
		}

		LispVal toSetqLambda() {
			// Build (setq name (lambda (params...) body...))
			return listToCons(List.of(new LispSymbol(LispNames.SETQ), new LispSymbol(name), toLambda()));
		}

	}

	/**
	 * The {@code (lambda ...)} form of one wrapper, or {@code null} when the name has no
	 * entry. This is the INTERPRETER's half of the catalog: {@code LispEvaluator}
	 * evaluates the form on the first {@code #'name} / {@code (symbol-function 'name)}
	 * resolution of a built-in it lowers in {@code evalCons} but never binds as a
	 * {@code LispFunction}, so one table answers "what is the function value of this
	 * built-in" on all four backends instead of the compile paths having a catalog and
	 * the interpreter a separate list of Java builtins that drifted from it
	 * ({@code .kb/core-representation.md}).
	 * @param name the operator name
	 * @return the wrapper lambda form, or {@code null} when there is no wrapper
	 */
	public static @org.jspecify.annotations.Nullable LispVal lambdaFor(String name) {
		for (WrapperDef def : WRAPPER_DEFS) {
			if (def.name.equals(name)) {
				return def.toLambda();
			}
		}
		return null;
	}

	/**
	 * {@code #'apply}: {@code (lambda (f a &rest r) ...)} spreads its LAST argument -- a
	 * when r is empty, else the last of r -- behind the arguments in front of it, and
	 * refuses a last argument that is no proper list with the interpreter's text (a
	 * dotted one would otherwise reach the dispatcher's list walk). Its lambda list is
	 * what a wrong count is reported from ({@code APPLY expects at least 2 arguments}),
	 * which is also how the compiled {@code eval} reaches it: through the registry, like
	 * any other catalog name.
	 *
	 * <pre>
	 * (let ((l (if r (car (last r)) a)))
	 *   (if (do ((c l (cdr c))) ((atom c) (null c)))
	 *       (apply f (if r (cons a (append (butlast r) l)) l))
	 *       (error "APPLY: last argument must be a list")))
	 * </pre>
	 */
	private static WrapperDef applyWrapper() {
		LispSymbol f = new LispSymbol("f");
		LispSymbol a = new LispSymbol("a");
		LispSymbol r = new LispSymbol("r");
		LispSymbol l = new LispSymbol("l");
		LispSymbol c = new LispSymbol("c");
		LispVal last = listToCons(
				List.of(new LispSymbol(LispNames.IF), r, callV(LispNames.CAR, callV(LispNames.LAST, r)), a));
		LispVal proper = listToCons(List.of(new LispSymbol(LispNames.DO),
				listToCons(List.of((LispVal) listToCons(List.of(c, l, callV(LispNames.CDR, c))))),
				listToCons(List.of(callV(LispNames.ATOM, c), callV(LispNames.NULL, c)))));
		LispVal spread = listToCons(List.of(new LispSymbol(LispNames.IF), r,
				callV(LispNames.CONS, a, callV(LispNames.APPEND, callV(LispNames.BUTLAST, r), l)), l));
		LispVal checked = listToCons(List.of(new LispSymbol(LispNames.IF), proper, callV(LispNames.APPLY, f, spread),
				callV(LispNames.ERROR, new LispString("APPLY: last argument must be a list"))));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET),
				listToCons(List.of((LispVal) listToCons(List.of(l, last)))), checked));
		return new WrapperDef(LispNames.APPLY, List.of("f", "a", LispNames.LAMBDA_REST, "r"), List.of(body));
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

	// The Horner fold of a RUNTIME subscript list over the array's dimensions -- the
	// shape both #'aref and #'array-row-major-index need, because CL gives each of them
	// one subscript per dimension and the rank is static only in call position.
	// (do ((rm 0) (ds (array-dimensions a)) (is idx))
	// ((null is) <result>)
	// (setq rm (+ (* rm (car ds)) (car is)))
	// (setq ds (cdr ds))
	// (setq is (cdr is)))
	private static LispVal rowMajorFoldBody(LispVal result) {
		LispVal bindings = listToCons(List.of(callV("rm", new LispInteger(0)),
				callV("ds", call(LispNames.ARRAY_DIMENSIONS, "a")), callV("is", new LispSymbol("idx"))));
		LispVal exit = listToCons(List.of(call(LispNames.NULL, "is"), result));
		LispVal step = callV(LispNames.SETQ, new LispSymbol("rm"), callV(LispNames.ADD,
				callV(LispNames.MUL, new LispSymbol("rm"), call(LispNames.CAR, "ds")), call(LispNames.CAR, "is")));
		LispVal stepDs = callV(LispNames.SETQ, new LispSymbol("ds"), call(LispNames.CDR, "ds"));
		LispVal stepIs = callV(LispNames.SETQ, new LispSymbol("is"), call(LispNames.CDR, "is"));
		return listToCons(List.of(new LispSymbol(LispNames.DO), bindings, exit, step, stepDs, stepIs));
	}

	// The #'aref wrapper body: fold the subscript list into the row-major index, then
	// read there.
	private static LispVal arefFoldBody() {
		return rowMajorFoldBody(call(LispNames.ROW_MAJOR_AREF, "a", "rm"));
	}

	// #'array-row-major-index: the same fold, answering the index itself.
	private static WrapperDef arrayRowMajorIndexWrapper() {
		return new WrapperDef(LispNames.ARRAY_ROW_MAJOR_INDEX, List.of("a", LispNames.LAMBDA_REST, "idx"),
				List.of(rowMajorFoldBody(new LispSymbol("rm"))));
	}

	// #'vector: (lambda (&rest r) (coerce r 'vector)). The element COUNT is static in
	// call position (expandVector emits one %aset per element) and a runtime property
	// here, so the rest list is converted rather than spliced.
	private static WrapperDef vectorWrapper() {
		return new WrapperDef(LispNames.VECTOR, List.of(LispNames.LAMBDA_REST, "r"), List.of(coerceTo("r", "VECTOR")));
	}

	// #'list*: (lambda (&rest r) ...) -- the last argument is the TAIL, the preceding
	// ones are consed onto it, so the fold runs right to left over the reversed rest
	// list. expandListStar can only do that with a static argument count.
	//
	// (let ((__ls_r (reverse r)))
	// (do ((__ls_tail (cdr __ls_r) (cdr __ls_tail))
	// (__ls_acc (car __ls_r) (cons (car __ls_tail) __ls_acc)))
	// ((null __ls_tail) __ls_acc)))
	private static WrapperDef listStarWrapper() {
		LispSymbol reversed = new LispSymbol("__ls_r");
		LispSymbol tail = new LispSymbol("__ls_tail");
		LispSymbol acc = new LispSymbol("__ls_acc");
		LispVal bindings = listToCons(List
			.of(listToCons(List.of(tail, callV(LispNames.CDR, reversed), callV(LispNames.CDR, tail))), listToCons(List
				.of(acc, callV(LispNames.CAR, reversed), callV(LispNames.CONS, callV(LispNames.CAR, tail), acc)))));
		LispVal exit = listToCons(List.of(callV(LispNames.NULL, tail), acc));
		LispVal loop = listToCons(List.of(new LispSymbol(LispNames.DO), bindings, exit));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET),
				listToCons(List.of(listToCons(List.of(reversed, call(LispNames.REVERSE, "r"))))), loop));
		return new WrapperDef(LispNames.LIST_STAR, List.of(LispNames.LAMBDA_REST, "r"), List.of(body));
	}

	// #'map: (lambda (type f s &rest more) ...). Both of the operator's static facts are
	// runtime values here -- the result type (a literal designator in call position) and
	// the sequence COUNT -- so the wrapper walks the sequences itself, in the
	// everySomeWrapper shape, and converts the collected list through the same runtime
	// family dispatch a computed coerce uses. A nil result type means "for effect", which
	// coerce has no designator for, so it is tested first.
	//
	// (lambda (type f s &rest more)
	// (let ((__map_r (do ((ss (mapcar (lambda (x) (coerce x 'list)) (cons s more))) (acc
	// nil))
	// ((member nil ss) (reverse acc))
	// (setq acc (cons (apply f (mapcar (lambda (x) (car x)) ss)) acc))
	// (setq ss (mapcar (lambda (x) (cdr x)) ss)))))
	// (if (null type) nil
	// (let ((__map_t (if (consp type) (car type) type)))
	// (if (member __map_t '(string ...)) (coerce __map_r 'string)
	// (if (member __map_t '(list cons)) (coerce __map_r 'list)
	// (coerce __map_r 'vector)))))))
	private static WrapperDef mapWrapper() {
		LispSymbol seqs = new LispSymbol("__map_ss");
		LispSymbol acc = new LispSymbol("__map_acc");
		LispSymbol collected = new LispSymbol("__map_r");
		LispVal asLists = callV(LispNames.MAPCAR, coerceToListLambda(),
				callV(LispNames.CONS, new LispSymbol("s"), new LispSymbol("more")));
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(seqs, asLists)), listToCons(List.of(acc, LispNil.INSTANCE))));
		LispVal exit = listToCons(
				List.of(callV(LispNames.MEMBER, LispNil.INSTANCE, seqs), callV(LispNames.REVERSE, acc)));
		LispVal apply = callV(LispNames.APPLY, new LispSymbol("f"),
				callV(LispNames.MAPCAR, projection(LispNames.CAR), seqs));
		LispVal collect = callV(LispNames.SETQ, acc, callV(LispNames.CONS, apply, acc));
		LispVal advance = callV(LispNames.SETQ, seqs, callV(LispNames.MAPCAR, projection(LispNames.CDR), seqs));
		LispVal walk = listToCons(List.of(new LispSymbol(LispNames.DO), bindings, exit, collect, advance));
		// The result type is dispatched HERE, onto three LITERAL coerces, rather than
		// handed to coerce as a computed designator. map's result type is a sequence
		// designator by contract, so the three arms are the whole surface -- and a
		// computed coerce would end in its "already of that type" arm, which is a
		// computed typep, i.e. the %typep-runtime defun; that defun's gate scans the
		// SOURCE program and would never see this wrapper body. The (function typep)
		// rule in LispMacroExpander.needsRuntimeTypep answers the same problem by
		// counting the reference that injects the wrapper -- which cannot work here,
		// where the map wrapper rides the eval runtime's gate instead of a name.
		LispSymbol head = new LispSymbol("__map_t");
		LispVal headOf = listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.CONSP, "type"),
				callV(LispNames.CAR, new LispSymbol("type")), new LispSymbol("type")));
		LispVal toVector = coerceTo(collected.name(), "VECTOR");
		LispVal toList = coerceTo(collected.name(), "LIST");
		LispVal toString = coerceTo(collected.name(), "STRING");
		LispVal byFamily = listToCons(List.of(new LispSymbol(LispNames.IF),
				memberOf(head, "STRING", "SIMPLE-STRING", "BASE-STRING", "SIMPLE-BASE-STRING"), toString,
				listToCons(List.of(new LispSymbol(LispNames.IF), memberOf(head, "LIST", "CONS"), toList, toVector))));
		LispVal convert = listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.NULL, "type"),
				LispNil.INSTANCE, listToCons(List.of(new LispSymbol(LispNames.LET),
						listToCons(List.of(listToCons(List.of(head, headOf)))), byFamily))));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET),
				listToCons(List.of(listToCons(List.of(collected, walk)))), convert));
		return new WrapperDef(LispNames.MAP, List.of("type", "f", "s", LispNames.LAMBDA_REST, "more"), List.of(body));
	}

	// #'map-into: (lambda (r f &rest seqs) ...). The sequence count is a runtime property
	// here too, so the wrapper walks the sources in lockstep and stores through the
	// runtime-dispatching (setf (elt ...) ...) place expandMapInto uses. With NO source
	// sequence the function is called with no arguments for every element of the result,
	// which is exactly what (member nil nil) answering nil makes the loop do.
	//
	// The RESULT is walked with a cons cursor beside the index, the shape
	// LispMacroExpander.mapIntoDispatch has carried since it was written: (setf (elt r i)
	// v) lowers to (rplaca (nthcdr i r) v), an O(i) head-walk, so a LIST destination made
	// this wrapper O(n^2) where the call-position lowering beside it was linear. The
	// cursor holds the remaining conses of a list destination and stays pinned to a
	// non-cons for a vector one, whose (setf (elt ...)) store is O(1) and is kept exactly
	// as it was -- so nothing but the list arm moves.
	//
	// (lambda (r f &rest seqs)
	// (let ((__mi_ss (mapcar (lambda (x) (coerce x 'list)) seqs))
	// (__mi_i 0) (__mi_n (length r)) (__mi_rc r))
	// (do () ((if (member nil __mi_ss) t (>= __mi_i __mi_n)) r)
	// (let ((__mi_v (apply f (mapcar (lambda (x) (car x)) __mi_ss))))
	// (if (consp __mi_rc) (rplaca __mi_rc __mi_v) (setf (elt r __mi_i) __mi_v)))
	// (setq __mi_ss (mapcar (lambda (x) (cdr x)) __mi_ss))
	// (setq __mi_rc (if (consp __mi_rc) (cdr __mi_rc) __mi_rc))
	// (setq __mi_i (+ __mi_i 1)))))
	private static WrapperDef mapIntoWrapper() {
		LispSymbol seqs = new LispSymbol("__mi_ss");
		LispSymbol index = new LispSymbol("__mi_i");
		LispSymbol limit = new LispSymbol("__mi_n");
		LispSymbol rcur = new LispSymbol("__mi_rc");
		LispSymbol value = new LispSymbol("__mi_v");
		LispVal asLists = callV(LispNames.MAPCAR, coerceToListLambda(), new LispSymbol("seqs"));
		LispVal bindings = listToCons(List.of(listToCons(List.of(seqs, asLists)),
				listToCons(List.of(index, new LispInteger(0))), listToCons(List.of(limit, call(LispNames.LENGTH, "r"))),
				listToCons(List.of(rcur, new LispSymbol("r")))));
		LispVal done = listToCons(List.of(new LispSymbol(LispNames.IF), callV(LispNames.MEMBER, LispNil.INSTANCE, seqs),
				LispTrue.INSTANCE, callV(LispNames.GE, index, limit)));
		LispVal exit = listToCons(List.of(done, new LispSymbol("r")));
		LispVal apply = callV(LispNames.APPLY, new LispSymbol("f"),
				callV(LispNames.MAPCAR, projection(LispNames.CAR), seqs));
		LispVal storeList = callV(LispNames.RPLACA, rcur, value);
		LispVal storeVec = callV(LispNames.SETF, callV(LispNames.ELT, new LispSymbol("r"), index), value);
		LispVal store = listToCons(List.of(new LispSymbol(LispNames.LET),
				listToCons(List.of(listToCons(List.of(value, apply)))),
				listToCons(List.of(new LispSymbol(LispNames.IF), callV(LispNames.CONSP, rcur), storeList, storeVec))));
		LispVal advance = callV(LispNames.SETQ, seqs, callV(LispNames.MAPCAR, projection(LispNames.CDR), seqs));
		LispVal stepCursor = callV(LispNames.SETQ, rcur, listToCons(
				List.of(new LispSymbol(LispNames.IF), callV(LispNames.CONSP, rcur), callV(LispNames.CDR, rcur), rcur)));
		LispVal bump = callV(LispNames.SETQ, index, callV(LispNames.ADD, index, new LispInteger(1)));
		LispVal walk = listToCons(
				List.of(new LispSymbol(LispNames.DO), LispNil.INSTANCE, exit, store, advance, stepCursor, bump));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET), bindings, walk));
		return new WrapperDef(LispNames.MAP_INTO, List.of("r", "f", LispNames.LAMBDA_REST, "seqs"), List.of(body));
	}

	// The bounding-index shape of #'read-sequence / #'write-sequence: the operator reads
	// :start / :end as LITERAL keywords (parseSequenceArgs), so the wrapper re-extracts
	// the runtime plist with getf and feeds the values back into the literal spelling.
	// An absent :end must not become an explicit nil -- the expansion defaults it to
	// (length seq) -- so it selects a different call shape rather than a nil argument.
	private static WrapperDef boundedSequenceIo(String name) {
		// ONE call: an :end of nil is the whole sequence in every expansion this reaches,
		// so the absent keyword needs no second copy of the (large) inline expansion.
		LispVal start = getfKwOr(LispNames.START_KEYWORD, new LispInteger(0));
		LispVal body = listToCons(List.of(new LispSymbol(name), new LispSymbol("seq"), new LispSymbol("st"),
				new LispSymbol(LispNames.START_KEYWORD), start, new LispSymbol(LispNames.END_KEYWORD),
				getfKw(LispNames.END_KEYWORD)));
		return new WrapperDef(name, List.of("seq", "st", LispNames.LAMBDA_REST, "kw"), List.of(body));
	}

	// write-line: the same runtime keyword re-extraction boundedSequenceIo does for
	// write-string, but the stream stays optional -- a one-argument funcall keeps
	// working, and an omitted stream forwards as nil, the standard-output designator
	// the call position treats like the omitted argument.
	private static WrapperDef writeLineWrapper() {
		LispVal start = getfKwOr(LispNames.START_KEYWORD, new LispInteger(0));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.WRITE_LINE), new LispSymbol("s"),
				new LispSymbol("st"), new LispSymbol(LispNames.START_KEYWORD), start,
				new LispSymbol(LispNames.END_KEYWORD), getfKw(LispNames.END_KEYWORD)));
		return new WrapperDef(LispNames.WRITE_LINE,
				List.of("s", LispNames.LAMBDA_OPTIONAL, "st", LispNames.LAMBDA_REST, "kw"), List.of(body));
	}

	// The readtable trio, whose call-position lowering is a no-op returning the lite
	// answer (nil / :upcase / t -- .kb/reader-case-upcase.md): the wrapper takes any
	// arity and answers the same constant, because the arguments cannot have an effect
	// the reader would ever see.
	private static WrapperDef readtableStub(String name) {
		return new WrapperDef(name, List.of(LispNames.LAMBDA_REST, "r"), List.of(call(name)));
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
	 *       (do ((ls (mapcar (lambda (x) (%check-list x 'mapcar)) (cons l more)))
	 *            (acc nil))                        ; N lists: shortest-list walk
	 *           ((member nil ls) (reverse acc))
	 *         (setq acc (cons (apply f (mapcar (lambda (x) (car x)) ls)) acc))
	 *         (setq ls (mapcar (lambda (x) (%check-list (cdr x) 'mapcar)) ls)))))
	 * </pre>
	 *
	 * The inner {@code mapcar}s are single-list, so they compile as the primitive. Every
	 * cursor is checked to be a list as it is taken, so {@code (member nil ls)} is
	 * exactly "some list is exhausted", CL's termination rule, and a list that is no list
	 * or ends dotted is the operator's type-error, as in call position.
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
		bindings.add(callV("ls", callV(LispNames.MAPCAR, checkedProjection(name, null),
				callV(LispNames.CONS, new LispSymbol("l"), new LispSymbol("more")))));
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
			case CONCATENATE -> callV(LispNames.SETQ, new LispSymbol("acc"),
					callV(LispNames.APPEND, new LispSymbol("acc"), checkList(apply, name)));
			case DISCARD -> apply;
		};
		LispVal advance = callV(LispNames.SETQ, new LispSymbol("ls"),
				callV(LispNames.MAPCAR, checkedProjection(name, LispNames.CDR), new LispSymbol("ls")));
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
		return everySomeWrapper(name, every, false);
	}

	/**
	 * As {@link #everySomeWrapper(String, boolean)}, with {@code notany} /
	 * {@code notevery} riding on the same walk: each is the complement of a member of the
	 * pair ({@code (notany p s) = (not (some p s))}), exactly as
	 * {@code LispMacroExpander.expandNotany} lowers it in call position, so only the
	 * value of the multi-sequence walk is negated.
	 * @param name the operator, called in the single-sequence case and named by the
	 * wrapper
	 * @param every true when the walk is {@code every}'s, false when it is {@code some}'s
	 * @param negated true for the {@code not-} member of the pair
	 */
	private static WrapperDef everySomeWrapper(String name, boolean every, boolean negated) {
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
		LispVal multi = negated ? callV(LispNames.NOT, walk) : walk;
		LispVal dispatch = listToCons(
				List.of(new LispSymbol(LispNames.IF), call(LispNames.NULL, "more"), call(name, "p", "s"), multi));
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
	/**
	 * {@code (lambda (x) (%check-list (op x) 'operator))}, or of {@code x} itself without
	 * an {@code op}: a {@code map*} wrapper's cursors, each checked to be a list under
	 * the operator the wrapper stands for.
	 */
	private static LispVal checkedProjection(String operator, @Nullable String op) {
		LispVal x = new LispSymbol("x");
		return listToCons(List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(x)),
				checkList(op == null ? x : callV(op, x), operator)));
	}

	/** {@code (%check-list form 'operator)}. */
	private static LispVal checkList(LispVal form, String operator) {
		return callV(LispNames.CHECK_LIST_INTERNAL, form, callV(LispNames.QUOTE, new LispSymbol(operator)));
	}

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

	// (lambda (a b &rest kw) (name a b :k1 (getf kw :k1) ...)) -- a two-operand operator
	// whose keywords are all bounds, where nil means "the default" in the call-position
	// lowering, so an absent keyword forwards as nil.
	private static WrapperDef boundingKeywords(String name, String... keywords) {
		List<LispVal> callParts = new ArrayList<>(
				List.of(new LispSymbol(name), new LispSymbol("a"), new LispSymbol("b")));
		for (String keyword : keywords) {
			callParts.add(new LispSymbol(keyword));
			callParts.add(getfKw(keyword));
		}
		return new WrapperDef(name, List.of("a", "b", LispNames.LAMBDA_REST, "kw"), List.of(listToCons(callParts)));
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

	// (name a &optional s) where the optional argument is a STREAM DESIGNATOR: an omitted
	// stream and an explicit nil are the SAME designator
	// (.kb/standard-output-redirect.md),
	// so the body forwards the parameter unconditionally and no presence test is needed
	// --
	// a bound nil reaches the operator's own *standard-output* / *standard-input*
	// resolution, exactly like the (defun emit (x &optional stream) (princ x stream))
	// forwarding StreamDesignators was written for (unlike unaryOptionalSecond, whose nil
	// is not a legal value and whose presence dispatch is therefore required).
	private static WrapperDef unaryOptionalStream(String name) {
		return new WrapperDef(name, List.of("a", LispNames.LAMBDA_OPTIONAL, "s"), List.of(call(name, "a", "s")));
	}

	// (name &optional s) -- the stream-only operators: terpri, fresh-line, force-output,
	// finish-output, clear-output, read-line, read-char, listen. Same designator rule as
	// unaryOptionalStream: the omitted and the nil stream are one designator.
	private static WrapperDef optionalStream(String name) {
		return new WrapperDef(name, List.of(LispNames.LAMBDA_OPTIONAL, "s"), List.of(call(name, "s")));
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

	// Variadic wrapper for append, the same shape as nconc's below. It has to be
	// variadic, not binary: CL's (append) with ZERO arguments is nil, and that call is
	// REACHABLE -- reduce over an empty sequence with no :initial-value calls its
	// function with no arguments, which is exactly how esrap's
	// (reduce #'append all-children) answers nil for a result node with no children.
	private static WrapperDef variadicAppend() {
		LispVal reduce = listToCons(
				List.of(new LispSymbol(LispNames.REDUCE), foldLambda(LispNames.APPEND), new LispSymbol("r")));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.IF), new LispSymbol("r"), reduce, LispNil.INSTANCE));
		return new WrapperDef(LispNames.APPEND, List.of(LispNames.LAMBDA_REST, "r"), List.of(body));
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

	/**
	 * Variadic wrapper for the {@code count} / {@code remove} / {@code substitute}
	 * family, on {@code positionFamily}'s model: the runtime keywords are re-extracted
	 * with {@code getf} and fed back into the CALL-POSITION expansion, so first-class use
	 * through {@code apply} carries the whole
	 * {@code :test}/{@code :test-not}/{@code :key}/{@code :start}/{@code :end}/
	 * {@code :count}/{@code :from-end} set instead of the two or three fixed arguments
	 * the wrapper used to take. A {@code :test-not} is normalized to a complemented
	 * {@code :test}; the {@code -if}/{@code -if-not} spellings take neither ({@code item}
	 * false), and {@code count} itself has no {@code :count} ({@code counted} false).
	 *
	 * <p>
	 * {@code remove-duplicates} / {@code delete-duplicates} ride the same wrapper with
	 * {@code operands} 0 -- they read the same keyword set minus {@code :count}, only
	 * with the window meaning their own expansion gives it. The {@code :start} defaulted
	 * to 0 and the computed {@code :from-end} this emits both land in that expansion's
	 * GENERAL rendering rather than its index-free one, which is correct but never the
	 * cheap shape: it is the price of one scan form per surface, paid only by a program
	 * that names the function rather than calling it.
	 * @param name the operator
	 * @param item whether the operator compares an ITEM (so takes :test / :test-not)
	 * @param counted whether the operator takes :count
	 * @param operands how many arguments precede the sequence (2 for the substitute
	 * family's new item plus its item/predicate, 0 for the duplicates pair)
	 */
	private static WrapperDef sequenceScanFamily(String name, boolean item, boolean counted, int operands) {
		List<String> lambdaList = new ArrayList<>();
		List<LispVal> callParts = new ArrayList<>();
		callParts.add(new LispSymbol(name));
		for (int i = 0; i < operands; i++) {
			String arg = "a" + i;
			lambdaList.add(arg);
			callParts.add(new LispSymbol(arg));
		}
		lambdaList.add("seq");
		lambdaList.add(LispNames.LAMBDA_REST);
		lambdaList.add("kw");
		callParts.add(new LispSymbol("seq"));
		if (item) {
			callParts.add(new LispSymbol(LispNames.TEST_KEYWORD));
			callParts.add(listToCons(List.of(new LispSymbol(LispNames.IF), getfKw(LispNames.TEST_NOT_KEYWORD),
					LispMacroExpander.twoArgumentComplement(getfKw(LispNames.TEST_NOT_KEYWORD)),
					getfKwOr(LispNames.TEST_KEYWORD, sharpQuote(LispNames.EQL)))));
		}
		callParts.add(new LispSymbol(LispNames.KEY_KEYWORD));
		callParts.add(getfKwOr(LispNames.KEY_KEYWORD, sharpQuote(LispNames.IDENTITY)));
		callParts.add(new LispSymbol(LispNames.START_KEYWORD));
		callParts.add(getfKwOr(LispNames.START_KEYWORD, new LispInteger(0)));
		callParts.add(new LispSymbol(LispNames.END_KEYWORD));
		callParts.add(getfKw(LispNames.END_KEYWORD));
		if (counted) {
			callParts.add(new LispSymbol(LispNames.COUNT_KEYWORD));
			callParts.add(getfKw(LispNames.COUNT_KEYWORD));
		}
		callParts.add(new LispSymbol(LispNames.FROM_END_KEYWORD));
		callParts.add(getfKw(LispNames.FROM_END_KEYWORD));
		return new WrapperDef(name, lambdaList, List.of(listToCons(callParts)));
	}

	/**
	 * Variadic wrapper for the DESIGNATOR-only scans: {@code member}/{@code assoc}/
	 * {@code rassoc}, the five set operations, and -- with {@code item} false -- the
	 * {@code -if} spellings, which take {@code :key} alone because the predicate IS the
	 * test. Same shape as {@link #sequenceScanFamily} minus CLHS 17.2.1's bounding set:
	 * the runtime keywords are re-extracted with {@code getf} and fed back into the
	 * CALL-POSITION expansion, so the expansion stays the only implementation and a
	 * {@code (apply #'set-difference x y :test f)} decides the way the call form does. A
	 * {@code :test-not} is normalized to a complemented {@code :test} the
	 * {@code twoArgumentComplement} way, so no {@code apply} reaches the injected body
	 * ({@code .kb/sequence-designator-evaluation.md}).
	 * @param name the operator
	 * @param item whether the operator compares an ITEM (so takes :test / :test-not)
	 */
	private static WrapperDef designatorFamily(String name, boolean item) {
		List<LispVal> callParts = new ArrayList<>();
		callParts.add(new LispSymbol(name));
		callParts.add(new LispSymbol("a"));
		callParts.add(new LispSymbol("b"));
		if (item) {
			callParts.add(new LispSymbol(LispNames.TEST_KEYWORD));
			callParts.add(listToCons(List.of(new LispSymbol(LispNames.IF), getfKw(LispNames.TEST_NOT_KEYWORD),
					LispMacroExpander.twoArgumentComplement(getfKw(LispNames.TEST_NOT_KEYWORD)),
					getfKwOr(LispNames.TEST_KEYWORD, sharpQuote(LispNames.EQL)))));
		}
		callParts.add(new LispSymbol(LispNames.KEY_KEYWORD));
		callParts.add(getfKw(LispNames.KEY_KEYWORD));
		return new WrapperDef(name, List.of("a", "b", LispNames.LAMBDA_REST, "kw"), List.of(listToCons(callParts)));
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
					LispMacroExpander.twoArgumentComplement(getfKw(LispNames.TEST_NOT_KEYWORD)),
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
				getfKwOr(LispNames.DISPLACED_INDEX_OFFSET_KEYWORD, new LispInteger(0)),
				new LispSymbol(LispNames.ADJUSTABLE_KEYWORD), getfKw(LispNames.ADJUSTABLE_KEYWORD),
				new LispSymbol(LispNames.FILL_POINTER_KEYWORD), getfKw(LispNames.FILL_POINTER_KEYWORD)));
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
		LispVal strings = stringFamilyBuild();
		// The vector arm honours an (unsigned-byte 8|16|32) element type, a packed FLOAT
		// one and CHARACTER, exactly like the call-position lowering: (apply
		// #'concatenate '(simple-array (unsigned-byte 8) (*)) ...) is http-body's own
		// spelling and ironclad's HKDF, and a designator must not mean two different
		// things depending on the call form. The element type sits in position 1 of
		// (vector T ...) / (array T ...) / (simple-array T ...); (simple-vector SIZE)
		// carries a SIZE there, which no (unsigned-byte N) list, no width NAME and no
		// CHARACTER spelling can be equal to, so one test per member covers every head
		// without reading the shape. The float tests are DERIVED from the closed code
		// space (packedFloatElementTypeCodes), so this dispatch and
		// %seq-float-vector's allocations cannot name different sets.
		LispSymbol elements = new LispSymbol("__cc_elts");
		LispSymbol elementType = new LispSymbol("__cc_elt");
		LispSymbol width = new LispSymbol("__cc_w");
		LispSymbol floatCode = new LispSymbol("__cc_f");
		LispVal widthOfElementType = new LispInteger(0);
		for (int bits : new int[] { 32, 16, 8 }) {
			LispVal unsignedByte = listToCons(List.of(new LispSymbol(LispNames.QUOTE),
					listToCons(List.of(new LispSymbol(LispNames.UNSIGNED_BYTE), new LispInteger(bits)))));
			widthOfElementType = listToCons(List.of(new LispSymbol(LispNames.IF),
					callV(LispNames.EQUAL, elementType, unsignedByte), new LispInteger(bits), widthOfElementType));
		}
		LispVal floatCodeOfElementType = new LispInteger(0);
		for (int code : packedFloatElementTypeCodes()) {
			LispVal name = listToCons(List.of(new LispSymbol(LispNames.QUOTE), ArrayElementTypes.valueOf(code)));
			floatCodeOfElementType = listToCons(List.of(new LispSymbol(LispNames.IF),
					callV(LispNames.EQUAL, elementType, name), new LispInteger(code), floatCodeOfElementType));
		}
		LispVal vectorBindings = listToCons(List.of(listToCons(List.of(elements, concatenatedElements())),
				listToCons(List.of(elementType,
						listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.CONSP, "type"),
								callV(LispNames.CAR, call(LispNames.CDR, "type")), LispNil.INSTANCE)))),
				listToCons(List.of(width, widthOfElementType)),
				listToCons(List.of(floatCode, floatCodeOfElementType))));
		LispVal isCharacter = memberOf(elementType, ArrayElementTypes.CHARACTER_SPELLINGS.toArray(new String[0]));
		LispVal generalOrFloat = listToCons(
				List.of(new LispSymbol(LispNames.IF), callV(LispNames.EQ, floatCode, new LispInteger(0)),
						listToCons(List.of(new LispSymbol(LispNames.COERCE), elements,
								listToCons(List.of(new LispSymbol(LispNames.QUOTE), new LispSymbol("VECTOR"))))),
						callV(LispNames.SEQ_FLOAT_VECTOR, elements, floatCode)));
		LispVal characterOrGeneralOrFloat = listToCons(List.of(new LispSymbol(LispNames.IF), isCharacter,
				callV(LispNames.SEQ_STRING, elements), generalOrFloat));
		LispVal vector = listToCons(List.of(new LispSymbol(LispNames.LET_STAR), vectorBindings,
				listToCons(List.of(new LispSymbol(LispNames.IF), callV(LispNames.EQ, width, new LispInteger(0)),
						characterOrGeneralOrFloat, callV(LispNames.SEQ_INT_VECTOR, elements, width)))));
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
		// The INTERNAL string designator: this normalizer feeds %string-concat, not the
		// program, so its result must not pick up the mutable-result wrap a
		// program-written (coerce x 'string) gets -- every concatenate argument would
		// pay a conversion for nothing.
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.STRINGP, "x"),
				new LispSymbol("x"), coerceTo("x", LispNames.SEQ_STRING_RESULT)));
		return new WrapperDef(LispNames.SEQ_STRING, List.of("x"), List.of(body));
	}

	// %seq-int-vector (gated by ConcatenateForms.needsSeqIntVector, plus a #'concatenate
	// reference -- the wrapper above calls it): one sequence of integers as a PACKED
	// (unsigned-byte 8|16|32) vector. It exists for the same reason %seq-string does --
	// the concatenate lowering must not plant an allocate-and-fill loop at every call
	// site (.kb/wasm-function-body-size.md) -- and it additionally walks the element list
	// LINEARLY, which the equivalent inline (make-array n :element-type '(unsigned-byte
	// 8) :initial-contents list) would not: that lowering indexes its contents with elt,
	// which is O(n) on a list.
	//
	// (lambda (seq w)
	// (let* ((l (coerce seq 'list))
	// (v (if (= w 8) (make-array (length l) :element-type '(unsigned-byte 8))
	// (if (= w 16) (make-array (length l) :element-type '(unsigned-byte 16))
	// (make-array (length l) :element-type '(unsigned-byte 32))))))
	// (do ((tail l (cdr tail)) (i 0 (+ i 1))) ((null tail) v)
	// (%aset v i (car tail)))))
	//
	// The three make-array calls are what makes the representation packed at all: the
	// element type has to be a LITERAL for every backend's recognizer to pick the packed
	// representation, so the runtime width dispatches onto three literal allocations
	// rather than passing the designator along.
	private static WrapperDef seqIntVectorWrapper() {
		LispSymbol list = new LispSymbol("__iv_l");
		LispSymbol vec = new LispSymbol("__iv_v");
		LispSymbol tail = new LispSymbol("__iv_tail");
		LispSymbol index = new LispSymbol("__iv_i");
		LispVal alloc = makeIntVectorAlloc(list, 32);
		for (int bits : new int[] { 16, 8 }) {
			alloc = listToCons(List.of(new LispSymbol(LispNames.IF),
					callV(LispNames.EQ, new LispSymbol("w"), new LispInteger(bits)), makeIntVectorAlloc(list, bits),
					alloc));
		}
		LispVal fill = listToCons(List.of(new LispSymbol(LispNames.DO),
				listToCons(List.of(listToCons(List.of(tail, list, callV(LispNames.CDR, tail))),
						listToCons(
								List.of(index, new LispInteger(0), callV(LispNames.ADD, index, new LispInteger(1)))))),
				listToCons(List.of(callV(LispNames.NULL, tail), vec)),
				callV(LispNames.ASET, vec, index, callV(LispNames.CAR, tail))));
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(list, coerceTo("seq", "LIST"))), listToCons(List.of(vec, alloc))));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET_STAR), bindings, fill));
		return new WrapperDef(LispNames.SEQ_INT_VECTOR, List.of("seq", "w"), List.of(body));
	}

	// (make-array (length l) :element-type '(unsigned-byte BITS))
	private static LispVal makeIntVectorAlloc(LispSymbol list, int bits) {
		return listToCons(List.of(new LispSymbol(LispNames.MAKE_ARRAY), callV(LispNames.LENGTH, list),
				new LispSymbol(LispNames.ELEMENT_TYPE_KEYWORD), listToCons(List.of(new LispSymbol(LispNames.QUOTE),
						listToCons(List.of(new LispSymbol(LispNames.UNSIGNED_BYTE), new LispInteger(bits)))))));
	}

	// %seq-float-vector (gated by ConcatenateForms.needsSeqFloatVector, plus a
	// #'concatenate reference -- the wrapper above calls it): one sequence of reals as a
	// PACKED FLOAT array of the width an ArrayElementTypes code names. The exact shape of
	// %seq-int-vector above, for the exact same reasons (one call site's worth of body,
	// and a LINEAR walk of the element list), with the code standing in for the width:
	//
	// (lambda (seq c)
	// (let* ((l (coerce seq 'list))
	// (v (if (= c 5) (make-array (length l) :element-type 'single-float)
	// (if (= c 7) (make-array (length l) :element-type 'bfloat16)
	// (make-array (length l) :element-type 'double-float)))))
	// (do ((tail l (cdr tail)) (i 0 (+ i 1))) ((null tail) v)
	// (%aset v i (car tail)))))
	//
	// The arms are DERIVED, one per packed float code of ArrayElementTypes, and each
	// spells the element type that code answers -- so a fourth width is reachable here
	// the moment it is reachable from make-array, with nothing to add. The allocations
	// have to be LITERAL for every backend's recognizer to pick the packed representation
	// (the make-array element type is read statically), which is why the runtime code
	// dispatches onto one allocation per width rather than passing the designator along.
	//
	// The bfloat16 arm is compiled on every backend and is DEAD on the ones that do not
	// carry the width: wasm-GC lowers its make-array to the call-time signal
	// UnsupportedFloatWidth names (WasmArrayCompiler), so a program that never asks for
	// bfloat16 builds and runs exactly as before while one that does gets that sentence
	// at the call -- the same shape vec.lisp's width-dispatching cond already has
	// (.kb/bfloat16.md).
	private static WrapperDef seqFloatVectorWrapper() {
		LispSymbol list = new LispSymbol("__fv_l");
		LispSymbol vec = new LispSymbol("__fv_v");
		LispSymbol tail = new LispSymbol("__fv_tail");
		LispSymbol index = new LispSymbol("__fv_i");
		int[] codes = packedFloatElementTypeCodes();
		LispVal alloc = makeFloatVectorAlloc(list, codes[codes.length - 1]);
		for (int i = codes.length - 2; i >= 0; i--) {
			alloc = listToCons(List.of(new LispSymbol(LispNames.IF),
					callV(LispNames.EQ, new LispSymbol("c"), new LispInteger(codes[i])),
					makeFloatVectorAlloc(list, codes[i]), alloc));
		}
		LispVal fill = listToCons(List.of(new LispSymbol(LispNames.DO),
				listToCons(List.of(listToCons(List.of(tail, list, callV(LispNames.CDR, tail))),
						listToCons(
								List.of(index, new LispInteger(0), callV(LispNames.ADD, index, new LispInteger(1)))))),
				listToCons(List.of(callV(LispNames.NULL, tail), vec)),
				callV(LispNames.ASET, vec, index, callV(LispNames.CAR, tail))));
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(list, coerceTo("seq", "LIST"))), listToCons(List.of(vec, alloc))));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.LET_STAR), bindings, fill));
		return new WrapperDef(LispNames.SEQ_FLOAT_VECTOR, List.of("seq", "c"), List.of(body));
	}

	// (make-array (length l) :element-type '<the name the code answers>)
	private static LispVal makeFloatVectorAlloc(LispSymbol list, int elementTypeCode) {
		return listToCons(List.of(new LispSymbol(LispNames.MAKE_ARRAY), callV(LispNames.LENGTH, list),
				new LispSymbol(LispNames.ELEMENT_TYPE_KEYWORD),
				listToCons(List.of(new LispSymbol(LispNames.QUOTE), ArrayElementTypes.valueOf(elementTypeCode)))));
	}

	// The packed FLOAT members of the closed element-type code space, in the order a
	// generated dispatch reads them. Filtered out of ArrayElementTypes.specializedCodes()
	// by asking the representation itself (ConcatenateForms.isPackedFloat), never by a
	// list written here -- a second list of the float widths is exactly what .todo/707
	// found missing from this file.
	private static int[] packedFloatElementTypeCodes() {
		int[] all = ArrayElementTypes.specializedCodes();
		int[] floats = new int[all.length];
		int n = 0;
		for (int code : all) {
			if (ConcatenateForms.isPackedFloat(code)) {
				floats[n++] = code;
			}
		}
		return java.util.Arrays.copyOf(floats, n);
	}

	// The string family, built in ONE pass over a result sized once:
	//
	// (let* ((__cc_strs (mapcar (lambda (x) (if (stringp x) x (coerce x
	// '%seq-string-result))) seqs))
	// (__cc_out (make-string (reduce (lambda (n s) (+ n (length s))) __cc_strs
	// :initial-value 0))))
	// (reduce (lambda (i s) (replace __cc_out s :start1 i) (+ i (length s))) __cc_strs
	// :initial-value 0)
	// __cc_out)
	//
	// NOT a fold through %string-concat: an append copies its accumulator, so folding n
	// arguments costs the sum of the prefixes -- quadratic in the total length, and
	// (apply #'concatenate 'string lines) over a file's lines is exactly that shape
	// (.kb/string-accumulate-cost.md). The per-argument normalization keeps the INTERNAL
	// %seq-string-result designator (it feeds replace, never the program). make-string
	// already answers a fresh mutable buffer, so no %str-fresh wrap is needed for the
	// mutable-result contract the call-position lowering satisfies
	// (.kb/string-write-runtime.md); the reduce's own accumulator carries the write
	// offset, so nothing setqs a captured variable.
	private static LispVal stringFamilyBuild() {
		LispSymbol strs = new LispSymbol("__cc_strs");
		LispSymbol out = new LispSymbol("__cc_out");
		LispVal normalize = listToCons(
				List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(new LispSymbol("x"))),
						listToCons(List.of(new LispSymbol(LispNames.IF), call(LispNames.STRINGP, "x"),
								new LispSymbol("x"), coerceTo("x", LispNames.SEQ_STRING_RESULT)))));
		LispVal normalized = callV(LispNames.MAPCAR, normalize, new LispSymbol("seqs"));
		LispVal sumStep = listToCons(
				List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(new LispSymbol("n"), new LispSymbol("s"))),
						callV(LispNames.ADD, new LispSymbol("n"), call(LispNames.LENGTH, "s"))));
		LispVal total = listToCons(List.of(new LispSymbol(LispNames.REDUCE), sumStep, strs,
				new LispSymbol(LispNames.INITIAL_VALUE_KEYWORD), new LispInteger(0)));
		LispVal copyStep = listToCons(
				List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(new LispSymbol("i"), new LispSymbol("s"))),
						listToCons(List.of(new LispSymbol(LispNames.REPLACE), out, new LispSymbol("s"),
								new LispSymbol(LispNames.START1_KEYWORD), new LispSymbol("i"))),
						callV(LispNames.ADD, new LispSymbol("i"), call(LispNames.LENGTH, "s"))));
		LispVal copyAll = listToCons(List.of(new LispSymbol(LispNames.REDUCE), copyStep, strs,
				new LispSymbol(LispNames.INITIAL_VALUE_KEYWORD), new LispInteger(0)));
		LispVal bindings = listToCons(List.of(listToCons(List.of(strs, normalized)),
				listToCons(List.of(out, callV(LispNames.MAKE_STRING, total)))));
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), bindings, copyAll, out));
	}

	// Every argument's elements, in order, in a FRESH list:
	// (reduce (lambda (x a) (append (coerce x 'list) a)) seqs :from-end t :initial-value
	// nil)
	// -- a RIGHT fold, so each step's append copies only that argument's own elements and
	// the whole build is linear in the total length; a left fold re-copies the
	// accumulator per argument and is quadratic, the list family's twin of the string
	// one (.kb/string-accumulate-cost.md). The nil seed is what copies the LAST argument
	// too. Built per use so the two dispatch arms never share one AST node.
	private static LispVal concatenatedElements() {
		LispVal step = listToCons(
				List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(new LispSymbol("x"), new LispSymbol("a"))),
						callV(LispNames.APPEND, coerceTo("x", "LIST"), new LispSymbol("a"))));
		return listToCons(List.of(new LispSymbol(LispNames.REDUCE), step, new LispSymbol("seqs"),
				new LispSymbol(LispNames.FROM_END_KEYWORD), LispTrue.INSTANCE,
				new LispSymbol(LispNames.INITIAL_VALUE_KEYWORD), LispNil.INSTANCE));
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
	 * file mode is picked statically). The wrapper therefore hands the plist to the SAME
	 * runtime dispatch a computed {@code with-open-file} option lowers to
	 * ({@code LispMacroExpander.lowerRuntimeOpenOptions}) instead of re-deriving one:
	 * that is what gives the wrapper {@code :if-exists :append}, the unsized
	 * {@code unsigned-byte} element type and the refusal of an option value the native
	 * behavior does not implement, none of which its own two-way dispatch had.
	 */
	private static WrapperDef openWrapper() {
		LispSymbol opts = new LispSymbol("o");
		// getf's DEFAULT is what makes an absent option mean the CL default rather than
		// nil -- the dispatch rejects nil like any other unsupported value.
		List<LispVal> options = List.of(new LispSymbol(LispNames.DIRECTION_KEYWORD),
				getfWithDefault(opts, LispNames.DIRECTION_KEYWORD, new LispSymbol(LispNames.INPUT_KEYWORD)),
				new LispSymbol(LispNames.ELEMENT_TYPE_KEYWORD),
				getfWithDefault(opts, LispNames.ELEMENT_TYPE_KEYWORD,
						listToCons(List.of(new LispSymbol(LispNames.QUOTE), new LispSymbol(LispNames.CHARACTER_TYPE)))),
				new LispSymbol(":IF-EXISTS"), getfWithDefault(opts, ":IF-EXISTS", new LispSymbol(":SUPERSEDE")),
				new LispSymbol(":IF-DOES-NOT-EXIST"),
				// CL's default depends on the DIRECTION: an output open creates the file,
				// an input open signals. Passing one fixed keyword down made the dispatch
				// read the wrong half of the table for whichever direction it was not.
				getfWithDefault(opts, ":IF-DOES-NOT-EXIST",
						listToCons(List.of(new LispSymbol(LispNames.IF),
								listToCons(List.of(new LispSymbol(LispNames.EQ_GENERAL),
										getfWithDefault(opts, LispNames.DIRECTION_KEYWORD,
												new LispSymbol(LispNames.INPUT_KEYWORD)),
										new LispSymbol(LispNames.OUTPUT_KEYWORD))),
								new LispSymbol(":CREATE"), new LispSymbol(":ERROR")))),
				new LispSymbol(":EXTERNAL-FORMAT"),
				getfWithDefault(opts, ":EXTERNAL-FORMAT", new LispSymbol(":UTF-8")));
		LispVal body = LispMacroExpander.lowerRuntimeOpenOptions(LispNames.OPEN, new LispSymbol("p"), options);
		return new WrapperDef(LispNames.OPEN, List.of("p", LispNames.LAMBDA_REST, "o"), List.of(body));
	}

	// (getf o :option <default>)
	private static LispVal getfWithDefault(LispSymbol plist, String option, LispVal fallback) {
		return listToCons(List.of(new LispSymbol(LispNames.GETF), plist, new LispSymbol(option), fallback));
	}

	// #'find-symbol: (lambda (n &rest p) (if (consp p) (find-symbol n (car p))
	// (find-symbol n))) -- both branches are call positions the backends lower.
	private static WrapperDef findSymbolWrapper() {
		LispSymbol n = new LispSymbol("n");
		LispSymbol p = new LispSymbol("p");
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.IF), callV(LispNames.CONSP, p),
				callV(LispNames.FIND_SYMBOL, n, callV(LispNames.CAR, p)), callV(LispNames.FIND_SYMBOL, n)));
		return new WrapperDef(LispNames.FIND_SYMBOL, List.of("n", LispNames.LAMBDA_REST, "p"), List.of(body));
	}

	/**
	 * The full lambda lists of the multiple-value producers whose catalog wrapper is
	 * narrower -- {@code (gethash key table &optional default)},
	 * {@code (intern name &optional package)} -- injected in place of the catalog entry
	 * for a program that names the operator as a designator
	 * ({@link #designatedValueProducers}); every other program keeps the catalog's
	 * wrapper, and its bytes.
	 */
	private static final Map<String, WrapperDef> VALUE_SHAPES = Map.of(LispNames.GETHASH,
			binaryOptionalThird(LispNames.GETHASH), LispNames.INTERN, unaryOptionalSecond(LispNames.INTERN));

	private static final List<WrapperDef> WRAPPER_DEFS = List.of(
			// Signal operators and format (gated by REFERENCE_GATED_FUNCTIONS in the
			// backend compilers)
			signalDatum(LispNames.ERROR, LispNames.ERROR), signalDatum(LispNames.SIGNAL, LispNames.SIGNAL),
			signalDatum(LispNames.WARN, LispNames.WARN), cerrorWrapper(), formatWrapper(), concatenateWrapper(),
			// %seq-string: injected only when a concatenate 'string lowering needs it
			// (gated on ConcatenateForms.needsSeqString, not on a #'name reference).
			seqStringWrapper(),
			// %seq-int-vector: the packed (unsigned-byte 8|16|32) vector builder the
			// concatenate vector family calls (gated on
			// ConcatenateForms.needsSeqIntVector OR a #'concatenate reference, since the
			// wrapper above calls it too).
			seqIntVectorWrapper(),
			// %seq-float-vector: the same for the packed float widths, on its own gate
			// (ConcatenateForms.needsSeqFloatVector OR a #'concatenate reference), so a
			// program that asks for one packed family never carries the other's
			// allocations.
			seqFloatVectorWrapper(),
			// #'open (reference-gated): dispatches an option plist onto the four literal
			// direction/element-type shapes the compiled open needs.
			openWrapper(),
			// #'find-symbol (reference-gated): dispatches on argument count onto the
			// two call-position lowerings; the computed name lowers to intern, so the
			// documented unknown-name-yields-a-symbol deviation applies to the wrapper
			// too (harmless in trivia's visibility filter, the driving consumer).
			findSymbolWrapper(),
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
			variadicAppend(),
			// List access (arity 1; first/rest/second/... compile via macro expansion)
			unary(LispNames.CAR), unary(LispNames.CDR), unary(LispNames.FIRST), unary(LispNames.REST),
			unary(LispNames.SECOND), unary(LispNames.THIRD), unary(LispNames.FOURTH), unary(LispNames.FIFTH),
			unary(LispNames.SIXTH), unary(LispNames.SEVENTH), unary(LispNames.EIGHTH), unary(LispNames.NINTH),
			unary(LispNames.TENTH), binary(LispNames.NTH), binary(LispNames.RPLACA), binary(LispNames.RPLACD),
			// Sequence operations (compiled via macro expansion in call position)
			unary(LispNames.LENGTH), unary(LispNames.REVERSE), unaryOptionalSecond(LispNames.LAST),
			unaryOptionalSecond(LispNames.BUTLAST), designatorFamily(LispNames.MEMBER, true),
			designatorFamily(LispNames.MEMBER_IF, false), binary(LispNames.FIND), binary(LispNames.FIND_IF),
			binary(LispNames.FIND_IF_NOT), positionFamily(LispNames.POSITION, true),
			positionFamily(LispNames.POSITION_IF, false), positionFamily(LispNames.POSITION_IF_NOT, false),
			sequenceScanFamily(LispNames.COUNT, true, false, 1),
			sequenceScanFamily(LispNames.COUNT_IF, false, false, 1), designatorFamily(LispNames.ASSOC, true),
			designatorFamily(LispNames.ASSOC_IF, false), designatorFamily(LispNames.RASSOC, true),
			designatorFamily(LispNames.RASSOC_IF, false), ternary(LispNames.ACONS), binary(LispNames.PAIRLIS),
			unary(LispNames.COPY_ALIST), binaryOptionalThird(LispNames.GETF),
			sequenceScanFamily(LispNames.REMOVE_DUPLICATES, true, false, 0),
			sequenceScanFamily(LispNames.DELETE_DUPLICATES, true, false, 0), variadicNconc(), unary(LispNames.IDENTITY),
			unary(LispNames.COPY_LIST), unary(LispNames.COPY_STRUCTURE), unary(LispNames.NREVERSE),
			unary(LispNames.MAKE_LIST), designatorFamily(LispNames.UNION, true),
			designatorFamily(LispNames.INTERSECTION, true), designatorFamily(LispNames.SET_DIFFERENCE, true),
			designatorFamily(LispNames.ADJOIN, true), designatorFamily(LispNames.SUBSETP, true),
			// every/some carry ANY number of sequences, the same as in call position,
			// and notany/notevery are their complements over the same walk.
			everySomeWrapper(LispNames.EVERY, true), everySomeWrapper(LispNames.SOME, false),
			everySomeWrapper(LispNames.NOTANY, false, true), everySomeWrapper(LispNames.NOTEVERY, true, true),
			sequenceScanFamily(LispNames.REMOVE, true, true, 1),
			sequenceScanFamily(LispNames.REMOVE_IF, false, true, 1),
			sequenceScanFamily(LispNames.REMOVE_IF_NOT, false, true, 1),
			sequenceScanFamily(LispNames.DELETE, true, true, 1),
			sequenceScanFamily(LispNames.DELETE_IF, false, true, 1),
			sequenceScanFamily(LispNames.DELETE_IF_NOT, false, true, 1),
			sequenceScanFamily(LispNames.SUBSTITUTE, true, true, 2),
			sequenceScanFamily(LispNames.NSUBSTITUTE, true, true, 2),
			sequenceScanFamily(LispNames.SUBSTITUTE_IF, false, true, 2),
			sequenceScanFamily(LispNames.SUBSTITUTE_IF_NOT, false, true, 2),
			sequenceScanFamily(LispNames.NSUBSTITUTE_IF, false, true, 2),
			sequenceScanFamily(LispNames.NSUBSTITUTE_IF_NOT, false, true, 2), binary(LispNames.SORT),
			variadicStableSort(), unary(LispNames.COPY_SEQ),
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
			applyWrapper(),
			// #'make-instance (gated by REFERENCE_GATED_FUNCTIONS): forwards to the
			// generated %mop-make-instance runtime-class construction defun.
			new WrapperDef(LispNames.MAKE_INSTANCE, List.of("class", LispNames.LAMBDA_REST, "r"),
					List.of(callV(LispNames.APPLY,
							listToCons(List.of(new LispSymbol(LispNames.FUNCTION),
									new LispSymbol(LispNames.MOP_MAKE_INSTANCE))),
							new LispSymbol("class"), new LispSymbol("r")))),
			// Predicates (arity 1)
			unary(LispNames.NULL), unary(LispNames.NOT), unary(LispNames.ATOM),
			// Type predicates (arity 1)
			unary(LispNames.NUMBERP), unary(LispNames.INTEGERP), unary(LispNames.FLOATP), unary(LispNames.REALP),
			unary(LispNames.COMPLEXP), unary(LispNames.SYMBOLP), unary(LispNames.STRINGP), unary(LispNames.LISTP),
			unary(LispNames.CONSP), unary(LispNames.KEYWORDP), unary(LispNames.FUNCTIONP), unary(LispNames.VALUES_LIST),
			unary(LispNames.VECTORP),
			// Type conversion (arity 1)
			unary(LispNames.FLOAT), unary(LispNames.RATIONAL), unary(LispNames.NUMERATOR), unary(LispNames.DENOMINATOR),
			// The floor family takes its optional divisor as a function too; with the
			// spill global present, LispMacroExpander.settleWrapperLambdas makes the
			// tail publish the remainder (.kb/multiple-values.md).
			unaryOptionalSecond(LispNames.TRUNCATE), unaryOptionalSecond(LispNames.FLOOR),
			unaryOptionalSecond(LispNames.CEILING), unaryOptionalSecond(LispNames.ROUND),
			unaryOptionalSecond(LispNames.FFLOOR), unaryOptionalSecond(LispNames.FCEILING),
			unaryOptionalSecond(LispNames.FROUND), unaryOptionalSecond(LispNames.FTRUNCATE),
			// Math/IO/list (arity 1)
			// print / prin1 / princ carry the optional stream: the wrapper forwards it
			// unconditionally, since an omitted stream and an explicit nil are the same
			// designator (.kb/standard-output-redirect.md).
			unary(LispNames.ABS), unaryOptionalStream(LispNames.PRINT), unaryOptionalStream(LispNames.PRIN1),
			unaryOptionalStream(LispNames.PRINC), unary(LispNames.PRINC_TO_STRING), unary(LispNames.PRIN1_TO_STRING),
			// list is variadic: the rest list IS the result
			new WrapperDef(LispNames.LIST, List.of(LispNames.LAMBDA_REST, "r"), List.of(new LispSymbol("r"))),
			// Complex numbers: (complex real &optional imag), the accessors and the
			// predicates above. The wrapper calls the function, so #'complex works
			// before any backend compiles the call itself (.todo/752, .todo/753).
			unaryOptionalSecond(LispNames.COMPLEX), unary(LispNames.REALPART), unary(LispNames.IMAGPART),
			unary(LispNames.CONJUGATE), unary(LispNames.PHASE), unary(LispNames.UPGRADED_COMPLEX_PART_TYPE),
			// Math functions (arity 1)
			unary(LispNames.SQRT), unary(LispNames.ISQRT), unary(LispNames.SIGNUM), unary(LispNames.EXP),
			// log and atan carry the optional SECOND argument (the logarithm's base,
			// atan's atan2 x): the dispatch on its presence is exact, since neither an
			// omitted base nor an omitted x can be spelled nil.
			unaryOptionalSecond(LispNames.LOG), unary(LispNames.SIN), unary(LispNames.COS), unary(LispNames.TAN),
			unary(LispNames.ASIN), unary(LispNames.ACOS), unaryOptionalSecond(LispNames.ATAN), unary(LispNames.SINH),
			unary(LispNames.COSH), unary(LispNames.TANH), unary(LispNames.CIS), unary(LispNames.ASINH),
			unary(LispNames.ACOSH), unary(LispNames.ATANH), unary(LispNames.RANDOM),
			// Math functions (arity 2)
			// gcd/lcm are variadic in Common Lisp -- (gcd) is 0, (lcm) is 1, a single
			// argument is its absolute value -- unlike the compiled call-position form,
			// which is always binary (LispMacroExpander.expandReduction folds a variadic
			// call into nested binary ones before either backend ever sees it). The
			// wrapper has to fold the same way so a #'gcd/#'lcm VALUE answers what the
			// call answers, rather than requiring exactly two arguments.
			binary(LispNames.EXPT), variadicIdentity(LispNames.GCD, new LispInteger(0)),
			variadicIdentity(LispNames.LCM, new LispInteger(1)),
			// Bitwise integer operations
			binary(LispNames.LOGAND), binary(LispNames.LOGIOR), binary(LispNames.LOGXOR), unary(LispNames.LOGNOT),
			binary(LispNames.LOGANDC1), binary(LispNames.LOGANDC2), binary(LispNames.LOGORC1),
			binary(LispNames.LOGORC2), binary(LispNames.LOGNAND), binary(LispNames.LOGNOR),
			variadicIdentity(LispNames.LOGEQV, new LispInteger(-1)), binary(LispNames.ASH),
			unary(LispNames.INTEGER_LENGTH), binary(LispNames.LOGBITP), binary(LispNames.LOGTEST),
			// Byte-field operations (macro-lowered to list/car/ash/logand/logior/lognot)
			binary(LispNames.BYTE), unary(LispNames.BYTE_SIZE), unary(LispNames.BYTE_POSITION), binary(LispNames.LDB),
			ternary(LispNames.DPB), ternary(LispNames.DEPOSIT_FIELD), binary(LispNames.MASK_FIELD),
			binary(LispNames.SCALE_FLOAT), unary(LispNames.FLOAT_RADIX),
			// bfloat16 bit reinterpretation, a real function on all four backends --
			// unlike the %ieee754-* quartet, which WASM cannot carry. The only
			// PACKAGE-QUALIFIED entries here: the pair belongs to rontolisp, not to cl,
			// so the wrapper has to spell the name the resolver leaves behind or a
			// #'reference would bind a lambda nothing calls.
			unary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.BFLOAT16_BITS)),
			unary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.BITS_BFLOAT16)),
			// The IEEE binary16 scalar pair, same reasoning as bfloat16-bits above
			// (.todo/671). widen-float-bits/narrow-float-bits wrap only their three
			// REQUIRED positional args (bits/src, format, dst) -- #'widen-float-bits
			// used this way narrows from :start 0, matching DPB/MASK-FIELD's own
			// required-arity-only wrapper above for a &key-bearing built-in.
			unary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FLOAT16_BITS)),
			unary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.BITS_FLOAT16)),
			ternary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WIDEN_FLOAT_BITS)),
			ternary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.NARROW_FLOAT_BITS)),
			// The block-quantized weight matrix (.kb/quantized-matrix.md).
			binary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZE)),
			binary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.DEQUANTIZE)),
			binary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_QUANTIZED_MATRIX)),
			binary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_ROWS)),
			unary(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_MATRIX_P)),
			// open as a first-class value: (apply #'open path options) is the portable
			// way to build an option list at run time (alexandria's
			// with-input-from-file).
			// The wrapper takes the positional shape the built-in compiles.
			// Lite stream/type introspection stubs (macro-lowered; slot-boundp and
			// slot-makunbound are omitted -- their expansions need a literal slot name).
			// probe-file is NOT here: it is a prelude defun now now, so
			// #'probe-file
			// resolves to the real definition.
			unary(LispNames.SLEEP), unary(LispNames.FILE_POSITION), unary(LispNames.FILE_LENGTH),
			unary(LispNames.FILE_WRITE_DATE), unary(LispNames.PATHNAMEP),
			// make-broadcast-stream as a VALUE stays the discarding sink: the call
			// position expands to the Gray class, but this body is injected into
			// every program (ungated) and must not pull the broadcast prelude entry
			// along -- the interpreter's Java built-in answers the same sink.
			new WrapperDef(LispNames.MAKE_BROADCAST_STREAM, List.of(),
					List.of(call(LispNames.MAKE_STRING_OUTPUT_STREAM_INTERNAL))),
			unary(LispNames.INPUT_STREAM_P), unary(LispNames.OUTPUT_STREAM_P), unary(LispNames.STREAM_ELEMENT_TYPE),
			unary(LispNames.CLASS_OF), unary(LispNames.SIMPLE_CONDITION_FORMAT_CONTROL),
			unary(LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS),
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
			unary(LispNames.STRING_CAPITALIZE), unary(LispNames.MAKE_STRING),
			boundingKeywords(LispNames.REPLACE, LispNames.START1_KEYWORD, LispNames.END1_KEYWORD,
					LispNames.START2_KEYWORD, LispNames.END2_KEYWORD),
			boundingKeywords(LispNames.FILL, LispNames.START_KEYWORD, LispNames.END_KEYWORD),
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
			// HASH_FUNCTIONS). gethash here is the 2-arg form (no default; the
			// VALUE_SHAPES one takes it); %puthash is internal and omitted.
			// #'make-hash-table
			// builds the DEFAULT
			// table and drops its initargs -- the same lite forwarding as the signal
			// operators -- but it takes them as a &rest tail rather than declaring
			// itself nullary: (apply #'make-hash-table initargs) is how alexandria's
			// alist-hash-table/plist-hash-table construct, and a nullary wrapper makes
			// that call a wrong-argument-count program-error
			// (JvmRuntimeBuilder.ArityReporting). Honouring a RUNTIME :test is a
			// separate matter: the test is what picks the table's representation, and
			// that choice is made at compile time (.kb/hash-tables.md).
			new WrapperDef(LispNames.MAKE_HASH_TABLE, List.of(LispNames.LAMBDA_REST, "initargs"),
					List.of(call(LispNames.MAKE_HASH_TABLE))),
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
			// terpri / fresh-line / read-line: the optional stream, forwarded
			// unconditionally (omitted == nil == the standard stream designator).
			optionalStream(LispNames.TERPRI), optionalStream(LispNames.FRESH_LINE), readLineWrapper(),
			// write-line: the optional stream plus the :start / :end keyword bounds,
			// the same runtime keyword re-extraction boundedSequenceIo does for
			// write-string. force-output / finish-output / clear-output keep the
			// plain optional stream. WASM flushes nothing (every write goes out
			// synchronously) and clear-output evaluates its designator for effect,
			// but every backend accepts the argument in call position, so the
			// wrapper body compiles everywhere.
			writeLineWrapper(), optionalStream(LispNames.FORCE_OUTPUT), optionalStream(LispNames.FINISH_OUTPUT),
			optionalStream(LispNames.CLEAR_OUTPUT),
			// listen: the interpreter and the JVM probe the designator. On WASM any
			// listen
			// CALL SITE lowers to the compiler's call-time unsupported stub, and the
			// --component sockets rewrite runs before this catalog is injected, so
			// #'listen reaches that stub on WASM even where (listen s) in call position
			// would reach %io-listen (the same WASM-side limit as the read family).
			optionalStream(LispNames.LISTEN),
			// The signalling read family (all three REFERENCE_GATED_FUNCTIONS): read-line
			// and read-char carry CL's whole optional tail, peek-char the peek-type in
			// front of it -- a wrapper shorter than the operator's lambda list is a
			// program-error on a legal funcall -- and read-byte's stream argument is
			// mandatory.
			optionalStreamEof(LispNames.READ_CHAR),
			new WrapperDef(LispNames.PEEK_CHAR,
					List.of(LispNames.LAMBDA_OPTIONAL, "a", "b", "e" + DEFAULT_TRUE, "v", "r"),
					List.of(call(LispNames.PEEK_CHAR, "a", "b", "e", "v"))),
			// read-char-no-hang keeps its 0-arity stdin shape (the non-blocking probe has
			// no stream-forwarded implementation behind the wrapper); unread-char is
			// binary (character + stream) -- its body signals on a handle, and a Gray
			// instance reaches it only through a rewritten CALL site, so #'unread-char is
			// the handle answer by construction.
			new WrapperDef(LispNames.READ_CHAR_NO_HANG, List.of(), List.of(call(LispNames.READ_CHAR_NO_HANG))),
			binary(LispNames.UNREAD_CHAR), unary(LispNames.READ_BYTE),
			// gensym: 0-arity (the literal-prefix form cannot be a first-class value;
			// macroexpand/macroexpand-1 have no wrapper at all -- the macro table does
			// not exist at runtime in compiled output)
			new WrapperDef(LispNames.GENSYM, List.of(), List.of(call(LispNames.GENSYM))),
			// values: variadic; the function value spreads its arguments like the
			// operator does -- values-list publishes the rest through %mv-spill and
			// says "no value" for an empty list -- so (funcall #'values 1 2) and
			// (apply #'values '()) answer to a consumer exactly as (values 1 2) and
			// (values) do (.kb/multiple-values.md)
			new WrapperDef(LispNames.VALUES, List.of(LispNames.LAMBDA_REST, "r"),
					List.of(call(LispNames.VALUES_LIST, "r"))),
			// write-string: the optional stream plus the :start / :end keyword bounds the
			// interpreter's function accepts -- the same runtime keyword re-extraction
			// boundedSequenceIo does for read-sequence / write-sequence, which
			// write-string
			// matches (string, stream positional; :start / :end keywords).
			// write-to-string
			// is a prin1-to-string alias
			boundedSequenceIo(LispNames.WRITE_STRING),
			new WrapperDef(LispNames.WRITE_TO_STRING, List.of("a"), List.of(call(LispNames.PRIN1_TO_STRING, "a"))),
			// symbol runtime API: the pure string<->symbol converters get plain
			// wrappers. find-symbol folds at compile time (literal-only, like
			// symbol-function) and boundp/fboundp need the eval runtime, which is only
			// emitted when the program calls them directly -- so neither can be a
			// first-class value in compiled output (macroexpand precedent).
			unary(LispNames.SYMBOL_NAME), unary(LispNames.MAKE_SYMBOL), unary(LispNames.INTERN),
			// symbol-value is the exception among those four since the progv work: its
			// wrapper is REFERENCE-GATED (see above), and the #'symbol-value reference
			// that injects it also fires the usesEval scan that makes its body real.
			unary(LispNames.SYMBOL_VALUE),
			// set beside it, reference-gated for the same reason: the body is the
			// backing-store chain plus the mirror store.
			binary(LispNames.SET),
			// find-package IS one of them, because its wrapper body is a COMPUTED
			// designator and that already has a real lowering: the per-expression
			// compilers rewrite it to a lookup in the package table the backend bakes in
			// (LispMacroExpander.expandRuntimeFindPackage), which is exactly what
			// #'find-package must answer. esrap's resolve-function reaches it with
			// (mapcar #'find-package '(#:cl #:esrap)).
			unary(LispNames.FIND_PACKAGE),
			// The three package-registry queries, for the same reason: their bodies are
			// the plain calls, which the per-expression compilers answer from the baked
			// use table (LispMacroExpander.expandPackageQuery).
			new WrapperDef(LispNames.LIST_ALL_PACKAGES, List.of(), List.of(call(LispNames.LIST_ALL_PACKAGES))),
			unary(LispNames.PACKAGE_USE_LIST), unary(LispNames.PACKAGE_USED_BY_LIST),
			// CL FUNCTIONS both expression compilers lower in operator position that
			// had no wrapper, so #'name answered "undefined" on every backend -- a
			// whole-catalog sweep, not two fixes, and the pin that keeps the catalog
			// closed is BuiltinFunctionWrapperCatalogTest. Each body is the plain call
			// the compilers lower; where the operator reads something STATICALLY that
			// is a runtime value here, the wrapper does the work itself instead (see
			// the builders above).
			//
			// coerce and typep need no such treatment even though their second argument
			// is a type DESIGNATOR: both lowerings already have a computed-designator
			// arm (expandComputedCoerce / %typep-runtime), and a wrapper parameter is
			// exactly that shape.
			binary(LispNames.ELT), binary(LispNames.COERCE), binary(LispNames.TYPEP),
			// #'subtypep (gated by REFERENCE_GATED_FUNCTIONS): its specifiers are
			// parameters, so the body is the computed %subtypep-runtime dispatch; the
			// optional environment is accepted and ignored, as in call position.
			new WrapperDef(LispNames.SUBTYPEP, List.of("a", "b", LispNames.LAMBDA_OPTIONAL, "e"),
					List.of(call(LispNames.SUBTYPEP, "a", "b"))),
			unary(LispNames.ENDP), listStarWrapper(), binary(LispNames.REVAPPEND), binary(LispNames.NRECONC),
			vectorWrapper(), binary(LispNames.SVREF), unary(LispNames.ARRAY_RANK), binary(LispNames.ARRAY_DIMENSION),
			unary(LispNames.ARRAY_TOTAL_SIZE), arrayRowMajorIndexWrapper(), mapWrapper(), mapIntoWrapper(),
			boundedSequenceIo(LispNames.READ_SEQUENCE), boundedSequenceIo(LispNames.WRITE_SEQUENCE),
			readtableStub(LispNames.COPY_READTABLE), readtableStub(LispNames.READTABLE_CASE),
			readtableStub(LispNames.SET_DISPATCH_MACRO_CHARACTER));

	/** Backing set of {@link #names()}; initialized after {@code WRAPPER_DEFS}. */
	private static final Set<String> WRAPPER_NAMES;
	static {
		Set<String> names = new java.util.HashSet<>();
		for (WrapperDef def : WRAPPER_DEFS) {
			names.add(def.name());
		}
		WRAPPER_NAMES = Set.copyOf(names);
	}

	private static LispVal listToCons(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
