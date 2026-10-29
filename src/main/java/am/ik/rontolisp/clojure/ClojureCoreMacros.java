package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code clojure.core} macros' expansions as data ({@code .kb/clojure-frontend.md},
 * "Macros"): the shipped namespace {@value #NAMESPACE}
 * ({@code lib/rontolisp/internal/core_macros.clj}) defines an expander per macro it
 * expands, in the oracle's macro calling convention -- the call, its environment, then
 * the call's arguments. {@code macroexpand-1}, {@code macroexpand} and a core macro's var
 * answer through them; a program's own call sites keep their lowering rows and never run
 * one. The namespace loads where code the program runs first expands at run time or takes
 * a core macro's var root ({@link #need}); the macro-time evaluator holds the expanders
 * from its start ({@link #macroTimeForms}), so a program expanding only in its macro
 * bodies, while it lowers, loads nothing and lowers as before.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureCoreMacros {

	/** The shipped namespace of the expanders: no user surface. */
	static final String NAMESPACE = "rontolisp.internal.core-macros";

	/**
	 * The core macros {@link #NAMESPACE} expands, each to the var of its expander:
	 * {@code expand-} and the macro's name, {@code ..}'s {@code expand-dotdot}.
	 * {@code ClojureCoreNamesTest} keeps the file's expanders and this table equal.
	 */
	private static final Map<String, String> EXPANDERS = expanders();

	/** The namespace's definitions lowered once, for every macro-time evaluator. */
	private static volatile @Nullable List<LispVal> macroTimeForms;

	private ClojureCoreMacros() {
	}

	private static Map<String, String> expanders() {
		Map<String, String> out = new TreeMap<>();
		for (String macro : List.of("let", "loop", "fn", "letfn", "when", "when-not", "if-not", "cond", "condp", "and",
				"or", "case", "if-let", "when-let", "if-some", "when-some", "when-first", "->", "->>", "as->", "cond->",
				"cond->>", "some->", "some->>", "doto", "dotimes", "while", "doseq", "for", "comment", "declare",
				"defn", "defn-", "binding", "assert", "locking", "with-open", "future")) {
			out.put(macro, "expand-" + macro);
		}
		out.put("..", "expand-dotdot");
		return Map.copyOf(out);
	}

	/**
	 * The core macros the namespace expands.
	 * @return their names
	 */
	static Set<String> expanded() {
		return EXPANDERS.keySet();
	}

	/**
	 * The expander var of each expanded macro.
	 * @return macro name to var name
	 */
	static Map<String, String> expanderVars() {
		return EXPANDERS;
	}

	/**
	 * The expanders are reached where the lowering stands: code the program runs loads
	 * {@link #NAMESPACE} ({@link #load}); a whole program's macro body only notes it
	 * ({@link ClojureLowering#coreMacrosInBodies}) -- the macro-time evaluator running
	 * the body holds them already, and the program needs them only where a program
	 * macro's root makes its bodies reachable as it runs, which loads them once every
	 * datum has lowered.
	 * @param ctx the hub
	 */
	static void need(ClojureLowering ctx) {
		if (ctx.macroBodyDepth > 0 && !ctx.open) {
			ctx.coreMacrosInBodies = true;
			return;
		}
		load(ctx);
	}

	/**
	 * Loads {@link #NAMESPACE} once, its definitions ahead of the datum at hand (and
	 * handed to the macro-time evaluator).
	 * @param ctx the hub
	 */
	static void load(ClojureLowering ctx) {
		if (!ctx.loadedNamespaces.contains(NAMESPACE) && !ctx.loadingNamespaces.contains(NAMESPACE)) {
			ClojureNamespaceLowering.preload(ctx, NAMESPACE);
		}
	}

	/**
	 * The namespace's definitions, lowered once for the macro-time evaluators: a macro
	 * body expanding a core macro, calling a core macro's var or destructuring runs them
	 * while the program lowers, whether the program loads the namespace or not.
	 * @return the definitions
	 */
	static List<LispVal> macroTimeForms() {
		List<LispVal> cached = macroTimeForms;
		if (cached == null) {
			synchronized (ClojureCoreMacros.class) {
				cached = macroTimeForms;
				if (cached == null) {
					List<LispVal> forms = new ArrayList<>();
					for (LispVal form : Clojure.read("(macroexpand-1 nil)", null)) {
						if (form instanceof LispCons cons && ClojureLowerUtil.isSymbolNamed(cons.car(), "DEFUN")
								&& cons.cdr() instanceof LispCons named && named.car() instanceof LispSymbol name
								&& ClojureLowering.isCoreMacroDefinition(name.name())) {
							forms.add(form);
						}
					}
					cached = List.copyOf(forms);
					macroTimeForms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * The defun of an expanded macro's expander.
	 * @param macro the macro's name, one of {@link #expanded}
	 * @return its symbol
	 */
	static LispSymbol expander(String macro) {
		return ClojureLowering.varSym(NAMESPACE + "/" + EXPANDERS.get(macro));
	}

	/**
	 * A core macro's var root, the oracle's macro function: an expanded macro's expander
	 * ({@link #need}), and for any other macro a function answering the call itself --
	 * the call this front end does not expand as data, as {@code macroexpand-1} of it
	 * answers it.
	 * @param ctx the hub
	 * @param macro the macro's name
	 * @return the root's form
	 */
	static LispVal root(ClojureLowering ctx, String macro) {
		if (EXPANDERS.containsKey(macro)) {
			need(ctx);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), expander(macro));
		}
		LispSymbol form = new LispSymbol("form");
		LispSymbol env = new LispSymbol("env");
		LispSymbol args = new LispSymbol("args");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(form, env, ClojureLowering.AMPERSAND_REST, args), form);
	}

	/**
	 * {@code (destructure bindings)}: the function the expanders destructure with,
	 * {@code clojure.core}'s own ({@link #need}).
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal destructureCall(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("destructure", items.size() - 1, 1, 1);
		need(ctx);
		return ClojureLowerUtil.list(destructure(), ctx.lower(items.get(1)));
	}

	/**
	 * {@code destructure} as a value ({@link #need}).
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal destructureValue(ClojureLowering ctx) {
		need(ctx);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), destructure());
	}

	private static LispSymbol destructure() {
		return ClojureLowering.varSym(NAMESPACE + "/destructure");
	}

	/**
	 * The macro runtime's {@code (defun name (macro) ...)}: the expander of the core
	 * macro named by the string {@code macro}, or nil for one the namespace does not
	 * expand.
	 * @param name the defun's name
	 * @return the definition
	 */
	static LispVal lookupDefinition(String name) {
		LispSymbol macro = new LispSymbol("macro");
		List<LispVal> clauses = new ArrayList<>();
		clauses.add(ClojureLowerUtil.sym("cond"));
		for (String expanded : EXPANDERS.keySet()) {
			clauses
				.add(ClojureLowerUtil.list(ClojureHierarchyLowering.hfn("STRING=", macro, LispString.literal(expanded)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), expander(expanded))));
		}
		clauses.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowering.NIL_CONST));
		return ClojureHierarchyLowering.hdefun(name, List.of(macro), ClojureLowerUtil.list(clauses));
	}

}
