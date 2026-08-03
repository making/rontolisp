package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * The compile-path half of "a user definition of a BUILT-IN name" (the interpreter half
 * lives in {@code LispEvaluator.defineDispatcher}): <strong>a built-in whose name a
 * program defines a method on becomes that generic's DEFAULT METHOD</strong>. The
 * expression compilers lower a built-in name in call position unconditionally, so the
 * dispatcher defun {@code LispMacroExpander.expandTopLevelDefinitions} emits under the
 * generic's own name is dead there and the user method was silently ignored (fast-io's
 * {@code close}/{@code open-stream-p}/... methods on its stream classes).
 *
 * <p>
 * The Gray-dispatch rewrite ({@code GrayStreamsLibrary.process}) is the model, but where
 * Gray rewrites a fixed protocol name list, the name set here is COMPUTED: every
 * registered generic whose name collides with a compiler-lowered built-in function. For
 * each such name this pass (1) replaces the dead dispatcher defun with the same
 * dispatcher under {@code %<name>--dispatch}, generated with the built-in as the
 * generic's default method (fallback = {@code %<name>--builtin}, exactly the
 * interpreter's stash name), (2) binds that fallback with a forwarder defun whose body
 * spells the ORIGINAL built-in call -- which the compilers still lower -- and (3)
 * rewrites the program's call sites and {@code (function name)} references onto the
 * dispatcher. The walker skips the two generated defuns (rewriting the forwarder's
 * fallback call into the dispatch again would recurse forever -- the
 * {@code GrayStreamsLibrary.DISPATCH_DEFUNS} rule), quoted data, macro-definition bodies,
 * and every non-evaluated binding position it knows ({@code let}/{@code lambda} families,
 * {@code case}/{@code handler-case} clause heads). When {@code close} is shadowed, the
 * {@code with-open-*}/{@code with-*-to-string} forms are pre-expanded so their implicit
 * {@code (close ...)} routes through the dispatcher like it does on the interpreter
 * (where every {@code close} call goes through the global function binding).
 *
 * <p>
 * A program that registers no colliding generic is returned unchanged (byte-identical
 * output). A plain {@code (defun close ...)} of a built-in name is NOT covered here --
 * same boundary as the interpreter half, which only stashes a Java-backed
 * {@code LispFunction} and deliberately lets defuns shadow each other.
 */
public final class ShadowedBuiltins {

	private ShadowedBuiltins() {
	}

	/**
	 * Compiler-lowered built-in FUNCTIONS with no {@link BuiltinFunctionWrappers} entry.
	 * Only names whose lowering accepts general (non-literal) arguments belong here: the
	 * forwarder defun's body hands the generic's parameters straight to the lowered call,
	 * so a literal-only lowering ({@code find-symbol}, {@code symbol-function}) or one
	 * that leans on a runtime injected by a gate that has already run
	 * ({@code typep}/{@code subtypep} inside {@code expandTopLevelDefinitions}) cannot be
	 * spelled as a fallback and keeps the old shadowing behavior.
	 */
	private static final Set<String> LOWERED_WITHOUT_WRAPPER = Set.of(LispNames.CLOSE, LispNames.LISTEN,
			LispNames.OPEN_STREAM_P, LispNames.WRITE_BYTE, LispNames.WRITE_LINE, LispNames.READ,
			LispNames.GET_OUTPUT_STREAM_STRING, LispNames.FORCE_OUTPUT, LispNames.FINISH_OUTPUT, LispNames.ARRAYP,
			LispNames.ARRAY_DIMENSIONS, LispNames.RATIONALP, LispNames.NUMERATOR, LispNames.DENOMINATOR,
			LispNames.RPLACA, LispNames.RPLACD, LispNames.HASH_TABLE_TEST, LispNames.HASH_TABLE_SIZE,
			LispNames.HASH_TABLE_REHASH_SIZE, LispNames.HASH_TABLE_REHASH_THRESHOLD);

	/**
	 * Wrapped names a generic must NOT shadow. The signal operators double as
	 * {@code handler-case} clause heads ({@code (error (e) ...)} is a clause, not a
	 * call), and {@code make-instance}/{@code class-of} have dedicated dispatch machinery
	 * ({@code %mop-make-instance}, the metaobject runtime) a rename would sever.
	 */
	private static final Set<String> NOT_SHADOWABLE = Set.of(LispNames.ERROR, LispNames.CERROR, LispNames.SIGNAL,
			LispNames.WARN, LispNames.MAKE_INSTANCE, LispNames.CLASS_OF);

	/**
	 * Wrapped names the INTERPRETER evaluates through an {@code evalCons} case or a macro
	 * expansion rather than a global {@code LispFunction} binding. Its half of todo-237
	 * stashes only a Java-backed {@code LispFunction}
	 * ({@code LispEvaluator.builtinDefaultMethodFor}), so it stashes nothing for these --
	 * dispatching a user method on them HERE would diverge from the interpreter in the
	 * opposite direction. Pinned by {@code ShadowedBuiltinsTest}: every name the set
	 * keeps must be a {@code LispFunction} in a fresh global environment.
	 */
	private static final Set<String> EXPANSION_LOWERED = Set.of(LispNames.NE, LispNames.ASSOC, LispNames.ASSOC_IF,
			LispNames.COUNT_IF, LispNames.DELETE_IF, LispNames.DELETE_IF_NOT, LispNames.EVERY,
			LispNames.FILE_WRITE_DATE, LispNames.FIND, LispNames.FIND_IF, LispNames.FIND_IF_NOT, LispNames.FIND_PACKAGE,
			LispNames.FORMAT, LispNames.FUNCALL, LispNames.MAPC, LispNames.MAPCAN, LispNames.MAPCAR, LispNames.MAPCON,
			LispNames.MAPHASH, LispNames.MAPL, LispNames.MAPLIST, LispNames.MEMBER, LispNames.MEMBER_IF,
			LispNames.NSUBSTITUTE_IF, LispNames.NSUBSTITUTE_IF_NOT, LispNames.POSITION, LispNames.POSITION_IF,
			LispNames.POSITION_IF_NOT, LispNames.PROBE_FILE, LispNames.RASSOC, LispNames.RASSOC_IF, LispNames.REMOVE_IF,
			LispNames.REMOVE_IF_NOT, LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS,
			LispNames.SIMPLE_CONDITION_FORMAT_CONTROL, LispNames.SOME, LispNames.SORT, LispNames.STABLE_SORT,
			LispNames.SUBSTITUTE_IF, LispNames.SUBSTITUTE_IF_NOT);

	private static volatile @org.jspecify.annotations.Nullable Set<String> lowered;

	/**
	 * The compiler-lowered built-in FUNCTION names a user {@code defmethod} can shadow:
	 * every {@link BuiltinFunctionWrappers} name (minus internals and
	 * {@link #NOT_SHADOWABLE}) plus {@link #LOWERED_WITHOUT_WRAPPER}.
	 * @return the shadowable lowered built-in function names
	 */
	public static Set<String> loweredBuiltinFunctions() {
		Set<String> cached = lowered;
		if (cached == null) {
			Set<String> names = new HashSet<>(BuiltinFunctionWrappers.names());
			names.removeIf(
					name -> name.startsWith("%") || NOT_SHADOWABLE.contains(name) || EXPANSION_LOWERED.contains(name));
			names.addAll(LOWERED_WITHOUT_WRAPPER);
			cached = Set.copyOf(names);
			lowered = cached;
		}
		return cached;
	}

	/**
	 * Runs the pass over an {@code expandTopLevelDefinitions}-processed program: replaces
	 * each colliding generic's dead dispatcher defun with the forwarder + renamed
	 * dispatcher pair and rewrites the program's call sites onto the dispatcher. Returns
	 * the program unchanged when no registered generic collides with a lowered built-in.
	 * @param program the top-level forms, after {@code expandTopLevelDefinitions}
	 * @param closRegistry the completed registry (classes, generics, methods)
	 * @return the program with the shadowed built-ins dispatched
	 */
	public static List<LispVal> process(List<LispVal> program, ClosRegistry closRegistry) {
		return process(program, closRegistry, Map.of());
	}

	/**
	 * As {@link #process(List, ClosRegistry)}, composing with a backend pre-pass that
	 * already redirected built-in call sites onto dispatch defuns of its own
	 * ({@code WasmSocketsRewrite}'s {@code rontolisp::%io-close} family): a call whose
	 * head is such an alias of a shadowed name is rewritten onto the dispatcher too, and
	 * the forwarder's fall-through calls the ALIAS -- keeping the pre-pass's bookkeeping
	 * (the socket table) in the loop -- instead of the raw built-in.
	 * @param program the top-level forms, after {@code expandTopLevelDefinitions}
	 * @param closRegistry the completed registry (classes, generics, methods)
	 * @param builtinAliases backend dispatch-defun name -&gt; the native built-in name it
	 * stands for (empty when the backend has no such pre-pass)
	 * @return the program with the shadowed built-ins dispatched
	 */
	public static List<LispVal> process(List<LispVal> program, ClosRegistry closRegistry,
			Map<String, String> builtinAliases) {
		Map<String, String> shadowed = new LinkedHashMap<>();
		for (ClosRegistry.GenericInfo generic : closRegistry.generics().values()) {
			if (loweredBuiltinFunctions().contains(generic.name())) {
				shadowed.put(generic.name(), LispMacroExpander.shadowedDispatcherName(generic.name()));
			}
		}
		if (shadowed.isEmpty()) {
			return program;
		}
		// The walker matches the alias spellings alongside the native ones, and the
		// forwarder targets the alias where one exists.
		Map<String, String> callHeads = new LinkedHashMap<>(shadowed);
		Map<String, String> fallbackTargets = new LinkedHashMap<>();
		for (Map.Entry<String, String> alias : builtinAliases.entrySet()) {
			String dispatch = shadowed.get(alias.getValue());
			if (dispatch != null) {
				callHeads.put(alias.getKey(), dispatch);
				fallbackTargets.put(alias.getValue(), alias.getKey());
			}
		}
		// The dead dispatchers are identified structurally: regenerating the 2-arg
		// dispatcher against the same completed registry reproduces the exact defun
		// expandTopLevelDefinitions placed (LispCons equality is structural), so a user
		// (defun close ...) of the same name can never be mistaken for one.
		Map<String, LispVal> plainDispatchers = new LinkedHashMap<>();
		for (String name : shadowed.keySet()) {
			plainDispatchers.put(name, LispMacroExpander.generateDispatcher(name, closRegistry));
		}
		boolean closeShadowed = shadowed.containsKey(LispNames.CLOSE);
		List<LispVal> out = new ArrayList<>(program.size() + shadowed.size());
		for (LispVal form : program) {
			String replaced = null;
			for (Map.Entry<String, LispVal> plain : plainDispatchers.entrySet()) {
				if (plain.getValue().equals(form)) {
					replaced = plain.getKey();
					break;
				}
			}
			if (replaced != null) {
				out.add(LispMacroExpander.builtinForwarderDefun(replaced, closRegistry,
						fallbackTargets.getOrDefault(replaced, replaced)));
				out.add(LispMacroExpander.shadowedBuiltinDispatcher(replaced, closRegistry));
			}
			else {
				out.add(rewrite(form, callHeads, closeShadowed));
			}
		}
		return out;
	}

	private static LispVal rewrite(LispVal form, Map<String, String> shadowed, boolean closeShadowed) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol op && cons.isProperList()) {
			String opName = op.name();
			switch (opName) {
				// Quoted data is data; a macro-definition body is a template the
				// expander instantiates, not evaluated code (the WasmSocketsRewrite
				// rule).
				case LispNames.QUOTE, LispNames.DEFMACRO, LispNames.MACROLET:
					return form;
				case LispNames.FUNCTION: {
					if (cons.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol named) {
						String dispatch = shadowed.get(named.name());
						if (dispatch != null) {
							return listOf(op, new LispSymbol(dispatch));
						}
					}
					break;
				}
				default:
					break;
			}
			List<LispVal> parts = cons.toList();
			if (closeShadowed) {
				// The stream-closing macros spell (close ...) only after expansion;
				// pre-expand them (unwind-protect shape, the interpreter/JVM
				// semantics) so that close routes through the dispatcher exactly like
				// the interpreter's global-binding lookup does.
				switch (opName) {
					case LispNames.WITH_OPEN_FILE:
						return rewrite(LispMacroExpander.expandWithOpenFile(cons), shadowed, true);
					case LispNames.WITH_OPEN_STREAM:
						return rewrite(LispMacroExpander.expandWithOpenStream(cons, true), shadowed, true);
					case LispNames.WITH_OUTPUT_TO_STRING:
						return rewrite(LispMacroExpander.expandWithOutputToString(cons, true), shadowed, true);
					case LispNames.WITH_INPUT_FROM_STRING:
						return rewrite(LispMacroExpander.expandWithInputFromString(cons, true), shadowed, true);
					default:
						break;
				}
			}
			// Non-evaluated positions of the binding/clause forms the walker can meet
			// at this stage (built-in macros expand later, in Pass 2): a parameter,
			// binding variable or clause head named like a shadowed built-in must not
			// read as a call.
			switch (opName) {
				case LispNames.DEFUN: {
					if (parts.size() >= 3) {
						List<LispVal> rebuilt = new ArrayList<>(parts.size());
						rebuilt.add(parts.get(0));
						rebuilt.add(parts.get(1));
						rebuilt.add(rewriteLambdaList(parts.get(2), shadowed, closeShadowed));
						for (int i = 3; i < parts.size(); i++) {
							rebuilt.add(rewrite(parts.get(i), shadowed, closeShadowed));
						}
						return listToCons(rebuilt);
					}
					break;
				}
				case LispNames.LAMBDA: {
					if (parts.size() >= 2) {
						List<LispVal> rebuilt = new ArrayList<>(parts.size());
						rebuilt.add(parts.get(0));
						rebuilt.add(rewriteLambdaList(parts.get(1), shadowed, closeShadowed));
						for (int i = 2; i < parts.size(); i++) {
							rebuilt.add(rewrite(parts.get(i), shadowed, closeShadowed));
						}
						return listToCons(rebuilt);
					}
					break;
				}
				case LispNames.FLET, LispNames.LABELS: {
					if (parts.size() >= 2) {
						List<LispVal> rebuilt = new ArrayList<>(parts.size());
						rebuilt.add(parts.get(0));
						rebuilt.add(rewriteLocalFunctionDefs(parts.get(1), shadowed, closeShadowed));
						for (int i = 2; i < parts.size(); i++) {
							rebuilt.add(rewrite(parts.get(i), shadowed, closeShadowed));
						}
						return listToCons(rebuilt);
					}
					break;
				}
				case LispNames.LET, LispNames.LET_STAR, LispNames.DO, LispNames.DO_STAR, LispNames.HANDLER_BIND,
						LispNames.RESTART_BIND: {
					if (parts.size() >= 2) {
						List<LispVal> rebuilt = new ArrayList<>(parts.size());
						rebuilt.add(parts.get(0));
						rebuilt.add(rewriteBindings(parts.get(1), shadowed, closeShadowed));
						for (int i = 2; i < parts.size(); i++) {
							rebuilt.add(rewrite(parts.get(i), shadowed, closeShadowed));
						}
						return listToCons(rebuilt);
					}
					break;
				}
				case LispNames.DOLIST, LispNames.DOTIMES, LispNames.WITH_OPEN_FILE, LispNames.WITH_OPEN_STREAM,
						LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING: {
					// (op (var expr...) body...): the spec's var is a binding, the
					// rest of the spec evaluates. The with-* forms reach here only
					// when close is NOT shadowed (pre-expansion above ate the other
					// case).
					if (parts.size() >= 2) {
						List<LispVal> rebuilt = new ArrayList<>(parts.size());
						rebuilt.add(parts.get(0));
						rebuilt.add(rewriteBindingSpec(parts.get(1), shadowed, closeShadowed));
						for (int i = 2; i < parts.size(); i++) {
							rebuilt.add(rewrite(parts.get(i), shadowed, closeShadowed));
						}
						return listToCons(rebuilt);
					}
					break;
				}
				case LispNames.MULTIPLE_VALUE_BIND, LispNames.DESTRUCTURING_BIND: {
					if (parts.size() >= 3) {
						List<LispVal> rebuilt = new ArrayList<>(parts.size());
						rebuilt.add(parts.get(0));
						rebuilt.add(parts.get(1));
						for (int i = 2; i < parts.size(); i++) {
							rebuilt.add(rewrite(parts.get(i), shadowed, closeShadowed));
						}
						return listToCons(rebuilt);
					}
					break;
				}
				case LispNames.CASE, LispNames.ECASE, LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE: {
					if (parts.size() >= 2) {
						List<LispVal> rebuilt = new ArrayList<>(parts.size());
						rebuilt.add(parts.get(0));
						rebuilt.add(rewrite(parts.get(1), shadowed, closeShadowed));
						for (int i = 2; i < parts.size(); i++) {
							rebuilt.add(rewriteClause(parts.get(i), shadowed, closeShadowed, 1));
						}
						return listToCons(rebuilt);
					}
					break;
				}
				case LispNames.HANDLER_CASE, LispNames.RESTART_CASE: {
					// Clause shape (type-or-name (var...) body...): head and binding
					// list are both non-evaluated.
					if (parts.size() >= 2) {
						List<LispVal> rebuilt = new ArrayList<>(parts.size());
						rebuilt.add(parts.get(0));
						rebuilt.add(rewrite(parts.get(1), shadowed, closeShadowed));
						for (int i = 2; i < parts.size(); i++) {
							rebuilt.add(rewriteClause(parts.get(i), shadowed, closeShadowed, 2));
						}
						return listToCons(rebuilt);
					}
					break;
				}
				default:
					break;
			}
			String dispatch = shadowed.get(opName);
			if (dispatch != null) {
				return new LispCons(new LispSymbol(dispatch), rewriteTail(cons.cdr(), shadowed, closeShadowed));
			}
		}
		// Generic: rewrite the operator/elements individually. The tail is walked
		// element-wise (NOT re-checked as a call), the GrayStreamsLibrary rule.
		LispVal car = rewrite(cons.car(), shadowed, closeShadowed);
		LispVal cdr = rewriteTail(cons.cdr(), shadowed, closeShadowed);
		if (car == cons.car() && cdr == cons.cdr()) {
			return form;
		}
		return new LispCons(car, cdr);
	}

	private static LispVal rewriteTail(LispVal tail, Map<String, String> shadowed, boolean closeShadowed) {
		if (!(tail instanceof LispCons cons)) {
			return tail;
		}
		LispVal car = rewrite(cons.car(), shadowed, closeShadowed);
		LispVal cdr = rewriteTail(cons.cdr(), shadowed, closeShadowed);
		if (car == cons.car() && cdr == cons.cdr()) {
			return tail;
		}
		return new LispCons(car, cdr);
	}

	/** A lambda list: parameter names stay, only default-value forms evaluate. */
	private static LispVal rewriteLambdaList(LispVal params, Map<String, String> shadowed, boolean closeShadowed) {
		if (!(params instanceof LispCons cons) || !cons.isProperList()) {
			return params;
		}
		List<LispVal> rebuilt = new ArrayList<>();
		for (LispVal param : cons.toList()) {
			rebuilt.add(rewriteClause(param, shadowed, closeShadowed, 1));
		}
		return listToCons(rebuilt);
	}

	/** A let-style binding list: each binding's var stays, its init forms evaluate. */
	private static LispVal rewriteBindings(LispVal bindings, Map<String, String> shadowed, boolean closeShadowed) {
		if (!(bindings instanceof LispCons cons) || !cons.isProperList()) {
			return bindings;
		}
		List<LispVal> rebuilt = new ArrayList<>();
		for (LispVal binding : cons.toList()) {
			rebuilt.add(rewriteClause(binding, shadowed, closeShadowed, 1));
		}
		return listToCons(rebuilt);
	}

	/** A single (var expr...) spec: the var stays, the rest evaluates. */
	private static LispVal rewriteBindingSpec(LispVal spec, Map<String, String> shadowed, boolean closeShadowed) {
		return rewriteClause(spec, shadowed, closeShadowed, 1);
	}

	/** A (flet ((name params body...) ...)) definition list. */
	private static LispVal rewriteLocalFunctionDefs(LispVal defs, Map<String, String> shadowed, boolean closeShadowed) {
		if (!(defs instanceof LispCons cons) || !cons.isProperList()) {
			return defs;
		}
		List<LispVal> rebuilt = new ArrayList<>();
		for (LispVal def : cons.toList()) {
			if (def instanceof LispCons defCons && defCons.isProperList() && defCons.toList().size() >= 2) {
				List<LispVal> defParts = defCons.toList();
				List<LispVal> newDef = new ArrayList<>(defParts.size());
				newDef.add(defParts.get(0));
				newDef.add(rewriteLambdaList(defParts.get(1), shadowed, closeShadowed));
				for (int i = 2; i < defParts.size(); i++) {
					newDef.add(rewrite(defParts.get(i), shadowed, closeShadowed));
				}
				rebuilt.add(listToCons(newDef));
			}
			else {
				rebuilt.add(def);
			}
		}
		return listToCons(rebuilt);
	}

	/**
	 * A clause whose first {@code keep} elements are non-evaluated (a case key, a
	 * handler-case type + binding list, a lambda-list parameter name); everything after
	 * them evaluates. A non-cons clause is inert.
	 */
	private static LispVal rewriteClause(LispVal clause, Map<String, String> shadowed, boolean closeShadowed,
			int keep) {
		if (!(clause instanceof LispCons cons) || !cons.isProperList()) {
			return clause;
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> rebuilt = new ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) {
			rebuilt.add(i < keep ? parts.get(i) : rewrite(parts.get(i), shadowed, closeShadowed));
		}
		return listToCons(rebuilt);
	}

	private static LispVal listOf(LispVal... items) {
		LispVal result = am.ik.rontolisp.LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			result = new LispCons(items[i], result);
		}
		return result;
	}

	private static LispVal listToCons(List<LispVal> elements) {
		LispVal result = am.ik.rontolisp.LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
